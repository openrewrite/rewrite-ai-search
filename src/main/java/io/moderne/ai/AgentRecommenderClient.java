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

public class AgentRecommenderClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    @Nullable
    private static AgentRecommenderClient INSTANCE;

    private final Map<String, ArrayList<String>> recommendationsCache = Collections.synchronizedMap(new LinkedHashMap<String, ArrayList<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayList<String>> eldest) {
            return size() > 1000;
        }
    });
    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized AgentRecommenderClient getInstance()  {
        if (INSTANCE == null) {
            INSTANCE = new AgentRecommenderClient();
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
        Path pyLauncher = MODELS_DIR.resolve("get_recommendations.py");
        try {
            Files.copy(requireNonNull(AgentRecommenderClient.class.getResourceAsStream("/get_recommendations.py")), pyLauncher, StandardCopyOption.REPLACE_EXISTING);
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            String cmd = String.format("/usr/bin/python3 %s/get_recommendations.py", MODELS_DIR);
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7864").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }



    private Function<String, ArrayList<String>> timeEmbedding(List<Duration> timings) {
        return t -> {
            long start = System.nanoTime();
            ArrayList<String> recs= getRecommendations(t);
            if (timings.isEmpty()) {
                timings.add(Duration.ofNanos(System.nanoTime() - start));
            }
            return recs;
        };
    }

    public ArrayList<String> getRecommendations(String text) {
        Unirest.config().reset().connectTimeout(5000).socketTimeout(100000);
        HttpResponse<GradioResponse> response = Unirest.post("http://127.0.0.1:7864/run/predict")
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .body(new GradioRequest(text))
                .asObject(GradioResponse.class);
        if (!response.isSuccess()) {
            throw new IllegalStateException("Unable to get embedding. HTTP " + response.getStatus());
        }
        return response.getBody().getRecommendations();
    }

    private static class GradioRequest {
        private final String[] data;

        GradioRequest(String... data) {
            this.data = data;
        }
    }

    @Value
    private static class GradioResponse {
        List<String> data;

        public ArrayList<String> getRecommendations() {
            String d = data.get(0);
            if (d.isEmpty()){
              return new ArrayList<String>();
            }
            String[] recsStr = d.substring(1, d.length() - 1).split(",");
            ArrayList<String> recs = new ArrayList<>();
            for (String s : recsStr) {
                recs.add(s.trim());
            }
            return recs;
        }
    }

    @Value
    public static class Relatedness {
        ArrayList<String> isRelated;
        List<Duration> embeddingTimings;
    }
}
