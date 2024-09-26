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

import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;

import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentGenerativeModelClient {
    @Nullable
    private static AgentGenerativeModelClient INSTANCE;

    private final ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    static String pathToModel = "/MODELS/codellama.gguf";
    static String pathToLLama = "/app/llama.cpp";
    static String maxContextLength = "1024";
    static String pathToFiles = "/app/";
    static String port = "7878";

    public static synchronized AgentGenerativeModelClient getInstance() {
        if (INSTANCE == null) {
            //Check if llama.cpp is already built
            File f = new File(pathToLLama + "/llama-server");
            if (!(f.exists() && !f.isDirectory())) {
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
            }

            INSTANCE = new AgentGenerativeModelClient();

            //Start server
            if (INSTANCE.checkForUpRequest() != 200) {
                StringWriter sw = new StringWriter();
                PrintWriter procOut = new PrintWriter(sw);
                try {
                    Runtime runtime = Runtime.getRuntime();
                    Process proc_server = runtime.exec((new String[]
                            {"/bin/sh", "-c", pathToLLama + "/llama-server -m " + pathToModel + " --port " + port +
                                    " -c " + maxContextLength + " --metrics"}));

                    EXECUTOR_SERVICE.submit(() -> {
                        new BufferedReader(new InputStreamReader(proc_server.getInputStream())).lines()
                                .forEach(procOut::println);
                        new BufferedReader(new InputStreamReader(proc_server.getErrorStream())).lines()
                                .forEach(procOut::println);
                    });

                    if (!INSTANCE.checkForUp()) {
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:" + port).asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    private boolean checkForUp() {
        for (int i = 0; i < 60; i++) {
            try {
                if (checkForUpRequest() == 200) {
                    return true;
                }
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    public ArrayList<String> getRecommendations(String code) {
        try (
                BufferedReader bufferedReader = new BufferedReader(new FileReader(pathToFiles + "prompt.txt"))
        ) {
            // Write a temporary file for input which includes prompt and relevant code snippet
            String line;
            StringBuilder promptContent = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                promptContent.append(line).append("\n");
            }
            String text = "<|im_start|>user\n" + promptContent + code + "```\n<|im_end|>\n<|im_start|>assistant\n1.";
            HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(90));
            HttpSender.Response raw;

            HashMap<String, Object> input = new HashMap<>();
            input.put("stream", false);
            input.put("prompt", text);
            input.put("temperature", 0.5);
            input.put("n_predict", 150);

            try {
                raw = http
                        .post("http://127.0.0.1:" + port + "/completion")
                        .withContent("application/json",
                                mapper.writeValueAsBytes(input)).send();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }


            if (!raw.isSuccessful()) {
                throw new IllegalStateException("Unable to get recommendations. HTTP " + raw.getClass());
            }
            String textResponse;
            textResponse = mapper.readValue(raw.getBodyAsBytes(), LlamaResponse.class).getResponse();


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
            throw new RuntimeException(e);
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

    public TimedRelatedness isRelatedTiming(String query, String code, double threshold) {
        long start = System.nanoTime();
        boolean isRelated = isRelated(query, code, threshold);
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        return new TimedRelatedness(isRelated, duration);
    }

    public boolean isRelated(String query, String code, double threshold) {
        String promptContent = "<|im_start|>system\nYou are tasked with predicting whether a certain code snippet matches the search query. Answer as 'ANS: Yes' or 'ANS: No'<|im_end|>\n";
        promptContent += "<|im_start|>user\n";
        promptContent += "Code: '" + code + "'\n";
        promptContent += "Query: " + query + "\n";
        promptContent += "<|im_end|>\n<|im_start|>assistant\n";
        promptContent += "ANS:";
        HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(60));
        HttpSender.Response raw;

        HashMap<String, Object> input = new HashMap<>();
        input.put("stream", false);
        input.put("prompt", promptContent);
        input.put("temperature", -0.00001); // temperature at 0, makes the model's probabilities only 0 or 1
        input.put("n_predict", 1);
        input.put("n_probs", 5);

        try {
            raw = http
                    .post("http://127.0.0.1:" + port + "/completion")
                    .withContent("application/json",
                            mapper.writeValueAsBytes(input)).send();

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        if (!raw.isSuccessful()) {
            throw new IllegalStateException("Unable to get response from server. HTTP " + raw.getClass());
        }
        boolean relatedResponse;
        try {
            relatedResponse = mapper.readValue(raw.getBodyAsBytes(), LlamaResponseProbabilities.class).isRelated(threshold);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return relatedResponse;
    }
    @Value
    private static class LlamaResponse {
        String content;

        public String getResponse() {
            return content;
        }
    }

    @Value
    public static class LlamaResponseProbabilities {
        List<CompletionProbability> completionProbabilities;
        String content;
        boolean multimodal;
        int slotId;
        boolean stop;

        @JsonProperty("completion_probabilities")
        public List<CompletionProbability> getCompletionProbabilities() {
            return completionProbabilities;
        }

        public boolean isRelated(double threshold) {
            for (CompletionProbability cp : completionProbabilities) {
                if (cp.getContent().equals(" Yes")) {
                    return cp.getProbs().get(0).getProb() >= threshold;
                }
            }
            return false;
        }
    }

    @Value
    public static class CompletionProbability {
        String content;
        List<TokenProbability> probs;

        public String getContent() {
            return content;
        }


    }

    @Value
    public static class TokenProbability {
        double prob;
        String tokStr;

        public double getProb() {
            return prob;
        }

    }

    @Value
    public static class TimedRelatedness {
        boolean isRelated;
        Duration duration;
    }
}
