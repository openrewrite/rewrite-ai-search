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

import kong.unirest.HeaderNames;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import lombok.Value;
import org.openrewrite.internal.lang.Nullable;

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

public class RelatedModelClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    @Nullable
    private static RelatedModelClient INSTANCE;

    private HashMap<String, Float[]> thresholds = new HashMap<String, Float[]>() {{
        put("HF", new Float[] {1.0f - 0.3815f, 1.0f - 0.1624f});
        put("Distance", new Float[] {1.0f - 0.84894f, 1.0f - 0.84572f});
    }};

    private final Map<Related, Integer> relatedCache = Collections.synchronizedMap(new LinkedHashMap<Related, Integer>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Related, Integer> eldest) {
            return size() > 1000;
        }
    });

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized RelatedModelClient getInstance()  {
        if (INSTANCE == null) {
            INSTANCE = new RelatedModelClient();
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
        Path pyLauncher = MODELS_DIR.resolve("get_is_related.py");
        try {
            Files.copy(requireNonNull(RelatedModelClient.class.getResourceAsStream("/get_is_related.py")), pyLauncher, StandardCopyOption.REPLACE_EXISTING);
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            String cmd = String.format("/usr/bin/python3 %s/get_is_related.py", MODELS_DIR);
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7871").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    public Relatedness getRelatedness(String t1, String t2) {
        List<Duration> timings = new ArrayList<>(2);
        Related related = new Related(t1, t2, this.thresholds);
        int b1 = relatedCache.computeIfAbsent(related, timeEmbedding(timings));
        return new Relatedness(b1, timings);
    }

    private Function<Related, Integer> timeEmbedding(List<Duration> timings) {
        return t -> {
            long start = System.nanoTime();
            int b = getRelated(t.t1, t.t2, t.thresholds);
            if (timings.isEmpty()) {
                timings.add(Duration.ofNanos(System.nanoTime() - start));
            }
            return b;
        };
    }

    public int getRelated(String s1, String s2, HashMap<String, Float[]> thresholds) {
        HttpResponse<GradioResponse> response = Unirest.post("http://127.0.0.1:7871/run/predict")
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .body(new GradioRequest(new Object[]{s1, s2, thresholds}))
                .asObject(GradioResponse.class);
        if (!response.isSuccess()) {
            throw new IllegalStateException("Unable to get if related. HTTP " + response.getStatus());
        }
        return response.getBody().isRelated();
    }

    @Value
    private static class GradioRequest {
        Object[] data;
    }

    @Value
    private static class GradioResponse {
        String[] data;

        public int isRelated() {
            return Integer.parseInt(data[0]);
        }
    }

    @Value
    public static class Relatedness {
        int isRelated;
        List<Duration> embeddingTimings;

        public int isRelated() {
            return isRelated;
        }
    }

    @Value
    public static class Related {
        String t1;
        String t2;
        HashMap<String, Float[]> thresholds;
    }


}