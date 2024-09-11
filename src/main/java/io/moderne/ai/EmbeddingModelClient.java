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
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class EmbeddingModelClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    @Nullable
    private static EmbeddingModelClient INSTANCE;

    private final ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Map<String, float[]> embeddingCache = Collections.synchronizedMap(new LinkedHashMap<String, float[]>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
            return size() > 1000;
        }
    });

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized EmbeddingModelClient getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new EmbeddingModelClient();
            if (INSTANCE.checkForUpRequest() != 200) {
                String cmd = "python3 'import gradio\ngradio.'";
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
        Path pyLauncher = MODELS_DIR.resolve("get_embedding.py");
        try {
            Files.copy(requireNonNull(EmbeddingModelClient.class.getResourceAsStream("/get_embedding.py")), pyLauncher, StandardCopyOption.REPLACE_EXISTING);
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            String cmd = String.format("python3 %s/get_embedding.py", MODELS_DIR);
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7860/embeddings").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    public Relatedness getRelatedness(String t1, String t2, double threshold) {
        List<Duration> timings = new ArrayList<>(2);
        float[] e1 = embeddingCache.computeIfAbsent(t1, timeEmbedding(timings));
        float[] e2 = embeddingCache.computeIfAbsent(t2.replace("\n", ""), timeEmbedding(timings));
        return new Relatedness(dist(e1, e2) <= threshold, timings);
    }

    private Function<String, float[]> timeEmbedding(List<Duration> timings) {
        return t -> {
            long start = System.nanoTime();
            float[] em = getEmbedding(t);
            if (timings.isEmpty()) {
                timings.add(Duration.ofNanos(System.nanoTime() - start));
            }
            return em;
        };
    }

    public double getDistance(String t1, String t2) {
        List<Duration> timings = new ArrayList<>(2);
        float[] e1 = embeddingCache.computeIfAbsent(t1, timeEmbedding(timings));
        float[] e2 = embeddingCache.computeIfAbsent(t2, timeEmbedding(timings));
        return dist(e1, e2);

    }

    private static double dist(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension");
        }
        float sumOfSquaredDifferences = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sumOfSquaredDifferences += diff * diff;
        }
        return Math.sqrt(sumOfSquaredDifferences);
    }

    public float[] getEmbedding(String text) {

        HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(30));
        HttpSender.Response raw = null;

        try {
            raw = http
                    .post("http://127.0.0.1:7860/embeddings")
                    .withContent("application/json", mapper.writeValueAsBytes(new EmbeddingModelClient.Request(text)))
                    .send();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        if (!raw.isSuccessful()) {
            throw new IllegalStateException("Unable to get embedding. HTTP " + raw.getClass());
        }

        float[] embeddings = null;
        try {
            embeddings = mapper.readValue(raw.getBodyAsBytes(), EmbeddingModelClient.Response.class).getEmbedding();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return embeddings;
    }

    @Value
    private static class Request {
        @JsonProperty("model")
        String model = "bge-small";

        @JsonProperty("input")
        String input;

        Request(String input) {
            this.input = input;
        }
    }

    @Value
    private static class Response {
        @JsonProperty("data")
        List<EmbeddingData> data;

        public float[] getEmbedding() {
            if (data == null || data.isEmpty()) {
                return new float[0];
            }
            return data.get(0).embedding;
        }

        @Value
        private static class EmbeddingData {
            @JsonProperty("embedding")
            float[] embedding;
        }
    }

    @Value
    public static class Relatedness {
        boolean isRelated;
        List<Duration> embeddingTimings;
    }
}
