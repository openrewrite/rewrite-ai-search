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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ipc.http.HttpSender;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

public class SpellCheckerClient {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    private final ObjectMapper mapper = JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .build()
            .registerModule(new ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Nullable
    private static SpellCheckerClient INSTANCE;


    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public static synchronized SpellCheckerClient getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SpellCheckerClient();
            if (INSTANCE.checkForUpRequest() != 200) {
                INSTANCE.start();
            }
        }
        return INSTANCE;
    }

    private void start() {
        Path pyLauncher = MODELS_DIR.resolve("spellcheck_comment_french.py");
        try {
            Files.copy(requireNonNull(SpellCheckerClient.class.getResourceAsStream("/spellcheck_comment_french.py")), pyLauncher, StandardCopyOption.REPLACE_EXISTING);
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            String cmd = String.format("python3 %s/spellcheck_comment_french.py", MODELS_DIR);
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7866").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }


    public String getCommentGradio(String text) {

        HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(30));
        HttpSender.Response raw = null;

        try {
            raw = http
                    .post("http://127.0.0.1:7866/run/predict")
                    .withContent("application/json",
                            mapper.writeValueAsBytes(new SpellCheckerClient.GradioRequest(new String[]{text})))
                    .send();
        } catch (JsonProcessingException e) {

            throw new RuntimeException(e);
        }


        if (!raw.isSuccessful()) {
            throw new IllegalStateException("Unable to get embedding. HTTP " + raw.getClass());
        }
        String comment = null;
        try {
            comment = mapper.readValue(raw.getBodyAsBytes(), SpellCheckerClient.GradioResponse.class).getSpellCheck();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return comment;
    }

    @Value
    private static class GradioRequest {
        Object[] data;
    }

    @Value
    private static class GradioResponse {
        String[] data;

        public String getSpellCheck() {
            return data[0];
        }
    }

    @Value
    public static class CommentFixed {
        String comment;
        List<Duration> commentTimings;
    }

    @Value
    public static class Comment {
        String t1;
    }
}
