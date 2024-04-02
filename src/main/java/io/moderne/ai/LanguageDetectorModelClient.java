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
import kong.unirest.HeaderNames;
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

public class LanguageDetectorModelClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    private ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Nullable
    private static LanguageDetectorModelClient INSTANCE;

    private final Map<Comment, String> languageCache = Collections.synchronizedMap(new LinkedHashMap<Comment, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Comment, String> eldest) {
            return size() > 1000;
        }
    });

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized LanguageDetectorModelClient getInstance()  {
        if (INSTANCE == null) {
            INSTANCE = new LanguageDetectorModelClient();
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
        Path pyLauncher = MODELS_DIR.resolve("get_language.py");
        try {
            Files.copy(requireNonNull(LanguageDetectorModelClient.class.getResourceAsStream("/get_language.py")), pyLauncher, StandardCopyOption.REPLACE_EXISTING);
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            String cmd = String.format("/usr/bin/python3 %s/get_language.py", MODELS_DIR);
//            String cmd = String.format("python %s/get_language.py", MODELS_DIR);
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7861").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }

    public Language getLanguage(String t1) {
        List<Duration> timings = new ArrayList<>(2);
        Comment comment = new Comment(t1);
        String b1 = languageCache.computeIfAbsent(comment, timeLanguage(timings));
        return new Language(b1, timings);
    }

    private Function<Comment, String> timeLanguage(List<Duration> timings) {
        return t -> {
            long start = System.nanoTime();
            String b = getLanguageGradio(t.t1);
            if (timings.isEmpty()) {
                timings.add(Duration.ofNanos(System.nanoTime() - start));
            }
            return b;
        };
    }


    public String getLanguageGradio(String text)  {


        HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(30));
        HttpSender.Response raw = null;

        try {
            raw = http
                    .post("http://127.0.0.1:7861/run/predict")
                    .withContent("application/json" ,
                            mapper.writeValueAsBytes(new LanguageDetectorModelClient.GradioRequest(new String[]{text})))
                    .send();
        } catch (JsonProcessingException e) {

            throw new RuntimeException(e);
        }


        if (!raw.isSuccessful()) {
            throw new IllegalStateException("Unable to get embedding. HTTP " + raw.getClass());
        }
        String language = null;
        try {
            language = mapper.readValue(raw.getBodyAsBytes(), LanguageDetectorModelClient.GradioResponse.class).getLanguage();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return language;
    }

    @Value
    private static class GradioRequest {
        Object[] data;
    }

    @Value
    private static class GradioResponse {
        String[] data;
        public String getLanguage() {
            return data[0];
        }
    }

    @Value
    public static class Language {
        String language;
        List<Duration> LanguageTimings;
    }

    @Value
    public static class Comment {
        String t1;
    }
}
