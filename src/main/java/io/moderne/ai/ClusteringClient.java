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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

public class ClusteringClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    @Nullable
    private static ClusteringClient INSTANCE;

    private ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized ClusteringClient getInstance()  {
        if (INSTANCE == null) {
            INSTANCE = new ClusteringClient();
            if (INSTANCE.checkForUpRequest() != 200) {
                String cmd = String.format("/usr/bin/python3 'import gradio\ngradio.'", MODELS_DIR);
                try {
                    Process proc = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                INSTANCE.start();
            }
        }
        return INSTANCE;
    }

    private void start() {
        Path pyLauncher = MODELS_DIR.resolve("get_centers.py");
        try {
            Files.copy(requireNonNull(ClusteringClient.class.getResourceAsStream("/get_centers.py")), pyLauncher, StandardCopyOption.REPLACE_EXISTING);
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            String cmd = String.format("/usr/bin/python3 %s/get_centers.py", MODELS_DIR);
            Process proc = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});
            EXECUTOR_SERVICE.submit(() -> {
                new BufferedReader(new InputStreamReader(proc.getInputStream())).lines()
                        .forEach(procOut::println);
                new BufferedReader(new InputStreamReader(proc.getErrorStream())).lines()
                        .forEach(procOut::println);
            });

            if (!checkForUp(proc)) {
                throw new IllegalStateException("Unable to start model daemon. Output of process is:\n" + sw);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean checkForUp(Process proc) {
        for (int i = 0; i < 60; i++) {
            try {
                if (!proc.isAlive() && proc.exitValue() != 0) {
                    return false;
                }
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

    private int checkForUpRequest() {
        try {
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7876").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    public String embeddingsToString(ArrayList<float[]> embeddings) {
        StringBuilder embeddingsToString = new StringBuilder();
        for (int i = 0; i < embeddings.size(); i++) {
            // Convert each float array to a string and append it
            embeddingsToString.append(Arrays.toString(embeddings.get(i)));
            // Add a comma only if it's not the last element
            if (i < embeddings.size() - 1) {
                embeddingsToString.append(",");
            }
        }
        return "[" + embeddingsToString.toString() + "]";
    }



    public int[] getCenters(ArrayList<float[]> embeddings, int numberOfCenters)  {

        String embeddingsToString = embeddingsToString(embeddings);

        HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(30));
        HttpSender.Response raw = null;

        try {
            raw = http
                    .post("http://127.0.0.1:7876/run/predict")
                    .withContent("application/json" ,
                            mapper.writeValueAsBytes(new ClusteringClient.GradioRequest(new Object[]{embeddingsToString, numberOfCenters})))
                    .send();
        } catch (JsonProcessingException e) {

            throw new RuntimeException(e);
        }


        if (!raw.isSuccessful()) {
            throw new IllegalStateException("Unable to get embedding. HTTP " + raw.getClass());
        }
        int[] centersIndex = null;
        try {
            centersIndex = mapper.readValue(raw.getBodyAsBytes(), ClusteringClient.GradioResponse.class).getCenters();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return centersIndex;
    }

    @Value
    private static class GradioRequest {
        Object[] data;
    }

    @Value
    private static class GradioResponse {
        List<String> data;

        public int[] getCenters() {
            return Arrays.stream(data.get(0).substring(1, data.get(0).length() - 1).split(" "))
                    .mapToInt(Integer::parseInt)
                    .toArray();
        }
    }

}
