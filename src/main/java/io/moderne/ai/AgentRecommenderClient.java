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
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");
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

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized AgentRecommenderClient getInstance() {
        if (INSTANCE == null) {
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            try {
                Runtime runtime = Runtime.getRuntime();
                Process proc_curl = runtime.exec(new String[]{"/bin/sh", "-c",
                        "curl -L https://github.com/ggerganov/llama.cpp/archive/refs/tags/b1961.zip" +
                                " --output /app/llama.cpp-b1961.zip"});
                proc_curl.waitFor();

                Process proc_jar = runtime.exec(new String[]{"/bin/sh", "-c", "jar xvf /app/llama.cpp-b1961.zip"});
                proc_jar.waitFor();

                Process proc_mv = runtime.exec(new String[]{"/bin/sh", "-c", "mv /app/llama.cpp-b1961 /app/llama.cpp"});
                proc_mv.waitFor();

                Process proc_make = runtime.exec(new String[]{"/bin/sh", "-c", "cd /app/llama.cpp && make"});
                proc_make.waitFor();




            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            INSTANCE = new AgentRecommenderClient();
            return INSTANCE;
            }
        return INSTANCE;
    }

    public ArrayList<String> getRecommendations(String code, int batch_size) {
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        Runtime runtime = Runtime.getRuntime();
        String tokenLength = String.valueOf((int)((code.length()/3.5)) + 400);
        String cmd = "/app/llama.cpp/main -m /MODELS/codellama.gguf";
        try (FileWriter fileWriter = new FileWriter("/app/prompt.txt", false)) {
            fileWriter.write("[INST]"+prompt);
            fileWriter.write(code + "```\n[/INST]1.");

        } catch (IOException e){
            throw new RuntimeException(e);
        }
        String flags = " -f /app/prompt.txt"
            + " -n 200 -c " + tokenLength + " 2>/dev/null --no-display-prompt -b " + String.valueOf(batch_size);

        String bogus = cmd + flags ;
        try {
            Process proc_llama = runtime.exec(new String[]{"/bin/sh", "-c", cmd + flags});
            new BufferedReader(new InputStreamReader(proc_llama.getInputStream())).lines()
                    .forEach(procOut::println);
            proc_llama.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
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
