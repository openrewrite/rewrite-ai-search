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

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import kong.unirest.HeaderNames;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import lombok.Getter;
import lombok.Value;
import org.openrewrite.internal.MetricsHelper;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class EmbeddingModelClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");
    private static final double RELATED_THRESHOLD = 0.0755;

    @Nullable
    private static EmbeddingModelClient INSTANCE;

    private final LinkedHashMap<String, float[]> embeddingCache = new LinkedHashMap<String, float[]>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
            return size() > 1000;
        }
    };

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized EmbeddingModelClient getInstance(String huggingFaceToken) {
        if (INSTANCE == null) {
            INSTANCE = new EmbeddingModelClient();
            if (INSTANCE.checkForUpRequest() != 200) {
                INSTANCE.start(huggingFaceToken);
            }
        }
        return INSTANCE;
    }

    private void start(String huggingFaceToken) {
        if (StringUtils.isBlank(huggingFaceToken)) {
            throw new IllegalArgumentException("Hugging face token is empty.");
        }

        Path pyLauncher = MODELS_DIR.resolve("get_em.py");
        try {
            if (!Files.exists(pyLauncher)) {
                Files.copy(requireNonNull(EmbeddingModelClient.class.getResourceAsStream("/get_em.py")), pyLauncher);
            }
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);

            String cmd = String.format("HUGGING_FACE_TOKEN=%s /usr/bin/python3 %s/get_em.py", huggingFaceToken, MODELS_DIR);
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7860").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    public boolean isRelated(String t1, String t2) {
        return isRelated(t1, t2, RELATED_THRESHOLD);
    }

    public boolean isRelated(String t1, String t2, double threshold) {
        float[] e1 = embeddingCache.computeIfAbsent(t1, this::getEmbedding);
        float[] e2 = embeddingCache.computeIfAbsent(t2.replace("\n", ""), this::getEmbedding);
        return dist(e1, e2) <= threshold;
    }

    public Relatedness getRelatedness(String t1, String t2) {
        return getRelatedness(t1, t2, RELATED_THRESHOLD);
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
        Timer.Sample sample = Timer.start();
        HttpResponse<GradioResponse> response = Unirest.post("http://127.0.0.1:7860/run/predict")
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .body(new GradioRequest(text))
                .asObject(GradioResponse.class);
        if (!response.isSuccess()) {
            IllegalStateException t = new IllegalStateException("Unable to get embedding. HTTP " + response.getStatus());
            sample.stop(MetricsHelper.errorTags(Timer.builder("rewrite.ai.get.embedding"), t)
                    .register(Metrics.globalRegistry));
            throw t;
        }
        float[] em = response.getBody().getEmbedding();
        sample.stop(MetricsHelper.successTags(Timer.builder("rewrite.ai.get.embedding"))
                .register(Metrics.globalRegistry));
        return em;
    }

    @Getter
    private static class GradioRequest {
        private final String[] data;

        GradioRequest(String... data) {
            this.data = data;
        }
    }

    @Value
    private static class GradioResponse {
        List<String> data;

        public float[] getEmbedding() {
            String d = data.get(0);
            String[] emStr = d.substring(1, d.length() - 1).split(",");
            float[] em = new float[emStr.length];
            for (int i = 0; i < emStr.length; i++) {
                em[i] = Float.parseFloat(emStr[i]);
            }
            return em;
        }
    }

    @Value
    public static class Relatedness {
        boolean isRelated;
        List<Duration> embeddingTimings;
    }
}
