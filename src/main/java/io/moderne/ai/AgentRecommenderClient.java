/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.moderne.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.ConstructorDetector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import kong.unirest.*;
import lombok.Value;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.ipc.http.HttpSender;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openrewrite.ipc.http.HttpUrlConnectionSender;

import static java.util.Objects.requireNonNull;

public class AgentRecommenderClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    @Nullable
    private static AgentRecommenderClient INSTANCE;
    private String prompt = "\n" +
            "Here is the task based on the code below: You are a software engineer responsible for keeping your source" +
            " code up to date with the latest third party and open source library. Given a piece of Java code, provide" +
            " a list of library upgrades you could perform. Only list 3 improvements for modernization.\n" +
            "\n" +
            "Here is the code snippet enclosed by backticks: \n ```";
    private final Map<String, ArrayList<String>> recommendationsCache = Collections.synchronizedMap(new LinkedHashMap<String, ArrayList<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayList<String>> eldest) {
            return size() > 1; // Was 1000. Setting it to 1, to get latency metrics without cache
        }
    });



    public static synchronized AgentRecommenderClient getInstance() {
        if (INSTANCE == null) {
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            try {
                Runtime runtime = Runtime.getRuntime();
                Process proc_make = runtime.exec(new String[]{"/bin/sh", "-c", "cd /app/llama.cpp && make"});
                proc_make.waitFor();
                new BufferedReader(new InputStreamReader(proc_make.getInputStream())).lines()
                        .forEach(procOut::println);
                new BufferedReader(new InputStreamReader(proc_make.getErrorStream())).lines()
                        .forEach(procOut::println);

            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e + "\nOutput: "+ sw);
            }
            INSTANCE = new AgentRecommenderClient();
            return INSTANCE;
            }
        return INSTANCE;
    }

    public ArrayList<String> getRecommendations(String code, int batch_size) {
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        StringWriter errorSw = new StringWriter();
        PrintWriter errorOut = new PrintWriter(errorSw);

        Runtime runtime = Runtime.getRuntime();
        String tokenLength = String.valueOf((int)((code.length()/3.5)) + 400);
        String cmd = "/app/llama.cpp/main -m /MODELS/codellama.gguf";

        try (
                BufferedReader bufferedReader = new BufferedReader(new FileReader("/app/prompt.txt"));
                FileWriter fileWriter = new FileWriter("/app/input.txt", false)
        ) {
            String line;
            StringBuilder promptContent = new StringBuilder();

            // Read lines from prompt.txt and append to StringBuilder
            while ((line = bufferedReader.readLine()) != null) {
                promptContent.append(line).append("\n");
            }

            fileWriter.write("[INST]" + promptContent.toString());
            fileWriter.write(code + "```\n[/INST]1.");

            String flags = " -f /app/input.txt"
            + " -n 150 --temp 0.50 -c " + tokenLength + " 2>/dev/null --no-display-prompt -b " + String.valueOf(batch_size);

            Process proc_llama = runtime.exec(new String[]{"/bin/sh", "-c", cmd + flags});
            new BufferedReader(new InputStreamReader(proc_llama.getInputStream())).lines()
                    .forEach(procOut::println);

            new BufferedReader(new InputStreamReader(proc_llama.getErrorStream())).lines()
                    .forEach(errorOut::println);
            proc_llama.waitFor();
            if (parseRecommendations("1."+sw).isEmpty()){
                throw new RuntimeException("Output: "+ sw + "\n Error output: " + errorSw);
            }

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e+"\nOutput: "+ errorSw);
        }
        return parseRecommendations("1."+sw);

    }

    public ArrayList<String> parseRecommendations(String recommendations) {
        if (recommendations.equals("[]")) {
            return new ArrayList<String>();
        } else {
            String patternString = "\\b\\d+[.:\\-]\\s+(.*?)\\s*(?=\\b\\d+[.:\\-]|\\Z)";
            Pattern pattern = Pattern.compile(patternString, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(recommendations);
            ArrayList<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(matcher.group(1));
            }
            return matches;
        }
    }


}
