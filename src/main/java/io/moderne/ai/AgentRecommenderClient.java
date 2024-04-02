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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.ConstructorDetector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import lombok.Value;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;

import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentRecommenderClient {
    @Nullable
    private static AgentRecommenderClient INSTANCE;
    private static HashMap<String, String> methodsToSample;

    private ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static String pathToModel = "/MODELS/codellama.gguf";
    static String pathToLLama = "/app/llama.cpp";

    static String pathToFiles = "/app/";

    static String port = "7878";
    public static synchronized AgentRecommenderClient getInstance() {
        if (INSTANCE == null) {
            //Check if llama.cpp is already built
            File f = new File(pathToLLama + "main");
            if(!(f.exists() && !f.isDirectory()) ) {
                //Build llama.cpp
                StringWriter sw = new StringWriter();
                PrintWriter procOut = new PrintWriter(sw);
                try {
                    Runtime runtime = Runtime.getRuntime();
                    Process proc_make = runtime.exec(new String[]{"/bin/sh", "-c", "make -C " + pathToLLama});
                    proc_make.waitFor();
                    new BufferedReader(new InputStreamReader(proc_make.getInputStream())).lines()
                            .forEach(procOut::println);
                    new BufferedReader(new InputStreamReader(proc_make.getErrorStream())).lines()
                            .forEach(procOut::println);
                    if (proc_make.exitValue() != 0) {
                        throw new RuntimeException("Failed to make llama.cpp at " + pathToLLama + "\n" + sw);
                    }

                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e + "\nOutput: " + sw);
                }
                INSTANCE = new AgentRecommenderClient();
            }

            //Start server
            if (INSTANCE.checkForUpRequest() != 200) {
                StringWriter sw = new StringWriter();
                PrintWriter procOut = new PrintWriter(sw);
                try {
                    Runtime runtime = Runtime.getRuntime();
                    Process proc_server = runtime.exec((new String[]
                            {"/bin/sh", "-c", pathToLLama + "server -m " + pathToModel + " --port " + port + " &"}));
                    new BufferedReader(new InputStreamReader(proc_server.getInputStream())).lines()
                            .forEach(procOut::println);
                    new BufferedReader(new InputStreamReader(proc_server.getErrorStream())).lines()
                            .forEach(procOut::println);
                    if (proc_server.exitValue() != 0) {
                        throw new RuntimeException("Failed to start server\n" + sw);
                    }

                } catch (IOException e) {
                    throw new RuntimeException(e + "\nOutput: " + sw);
                }
            }

            return INSTANCE;
        }
        return INSTANCE;
    }

    private int checkForUpRequest() {
        try {
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:"+port.toString()).asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    public static void populateMethodsToSample(String pathToCenters) {
        HashMap<String, String> tempMethodsToSample = new HashMap<>();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(pathToCenters))){
            String line;
            String source;
            String methodCall;
            while ((line = bufferedReader.readLine()) != null) {
                source = line.split(" ")[0];
                methodCall = line.split(" ")[1];
                tempMethodsToSample.put(source, methodCall);
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't read which methods to sample. " + e);
        }

        methodsToSample = tempMethodsToSample;

    }

    public static HashMap<String, String> getMethodsToSample() {
        return methodsToSample;
    }

    public ArrayList<String> getRecommendations(String code, int batch_size) {
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        StringWriter errorSw = new StringWriter();

        try (
                BufferedReader bufferedReader = new BufferedReader(new FileReader(pathToFiles + "prompt.txt"));
        ) {
            // Write a temporary file for input which includes prompt and relevant code snippet
            String line;
            StringBuilder promptContent = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                promptContent.append(line).append("\n");
            }
            String text = "[INST]" + promptContent + code + "```\n[/INST]1." ;
            HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(60));
            HttpSender.Response raw = null;

            HashMap <String, Object> input = new HashMap<>();
            input.put("stream", false);
            input.put("prompt", text);
            input.put("temperature", Double.valueOf(0.5));
            input.put("n_predict", 150);

            try {
                raw = http
                        .post("http://127.0.0.1:" + port + "/completion")
                        .withContent("application/json" ,
                                mapper.writeValueAsBytes(input)).send();

            } catch (JsonProcessingException e) {

                throw new RuntimeException(e);
            }


            if (!raw.isSuccessful()) {
                throw new IllegalStateException("Unable to get embedding. HTTP " + raw.getClass());
            }
            String textResponse = null;
            try {
                textResponse = mapper.readValue(raw.getBodyAsBytes(), LlamaResponse.class).getResponse();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            ArrayList<String> recommendations = parseRecommendations("1." + textResponse);

            if (recommendations.isEmpty()) {
                BufferedReader bufferedReaderLog = new BufferedReader(new FileReader(pathToFiles + "llama_log.txt"));
                String logLine;
                StringBuilder logContent = new StringBuilder();
                while ((logLine = bufferedReaderLog.readLine()) != null) {
                    logContent.append(logLine).append("\n");
                }
                bufferedReaderLog.close();
                throw new RuntimeException("Logs: " + logContent);
            }

            return recommendations;

        } catch (IOException e) {
            throw new RuntimeException(e + "\nOutput: " + errorSw);
        }
    }

    public ArrayList<String> parseRecommendations(String recommendations) {
        if (recommendations.equals("[]")) {
            return new ArrayList<>();
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

    public boolean isRelated(String query, String code) {
        //Make call to model
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        StringWriter errorSw = new StringWriter();

        try (
                FileWriter fileWriter = new FileWriter("/app/inputRelated.txt", false)
        ) {
            // Write a temporary file for input which includes prompt and relevant code snippet
            String line;
            String promptContent = "Does this query match the code snippet?\n";
            promptContent += "Query: " + query + "\n";
            promptContent += "Code: " + code + "\n";
            promptContent += "Answer as 'ANS: Yes' or 'ANS: No'.\n";
            fileWriter.write("[INST]" + promptContent + "[/INST]ANS:" );
            fileWriter.close();

            // Arguments to send to model
            String contextLength = String.valueOf((int) ((code.length() + query.length()) / 3.5) + 100); //buffer of 100
            String cmd = "/app/llama.cpp/main -m /MODELS/codellama.gguf";
            String flags = " -f /app/inputRelated.txt --temp 0.01"
                    + " -n 2 -c " + contextLength +
                    " 2>/app/llama_log.txt --no-display-prompt";

            // Call llama.cpp
            Runtime runtime = Runtime.getRuntime();
            Process proc_llama = runtime.exec(new String[]{"/bin/sh", "-c", cmd + flags});
            proc_llama.waitFor();
            new BufferedReader(new InputStreamReader(proc_llama.getInputStream())).lines()
                    .forEach(procOut::println);

            return parseRelated(sw);

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e + "\nOutput: " + errorSw);
        }

    }

    private boolean parseRelated(StringWriter sw) {
        // check if Yes or yes in output, return true if it does
        return (sw.toString().contains("Yes") || sw.toString().contains("yes"));

    }

    @Value
    private static class LlamaResponse {
        String content;
        public String getResponse() {
            return content;
        }
    }

}
