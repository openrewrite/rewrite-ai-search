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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            HttpResponse<String> response = Unirest.head("http://127.0.0.1:7865").asString();
            return response.getStatus();
        } catch (UnirestException e) {
            return 523;
        }
    }


    public ArrayList<String> getRecommendations(String text, int n_batch)  {

        HttpSender http = new HttpUrlConnectionSender(Duration.ofSeconds(20), Duration.ofSeconds(120));
        HttpSender.Response raw = null;

        try {
            raw = http
                   .post("http://127.0.0.1:7865/run/predict")
                   .withContent("application/json" , mapper.writeValueAsBytes(new GradioRequest(text,
                           String.valueOf(n_batch))))
                   .send();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        if (!raw.isSuccessful()) {
            List<String> resultList = searchFiles(new File("/"), "codellama.gguf");
            String results = "";
            if (resultList.isEmpty()) {
                results = "No files found.";
            } else {
                for (String filePath : resultList) {
                    results += filePath + ", ";
                }
            }
            throw new IllegalStateException("Unable to get embedding. HTTP " + raw.getCode() + " ." + " " + results);
        }
        ArrayList<String> recs = null;
        try {
            recs = mapper.readValue(raw.getBodyAsBytes(), GradioResponse.class).getRecommendations();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return recs;
    }
    private static List<String> searchFiles(File directory, String targetFileName) {
        List<String> resultList = new ArrayList<>();

        if (!directory.isDirectory()) {
            System.out.println(directory.getAbsolutePath() + " is not a directory!");
            return resultList;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    resultList.addAll(searchFiles(file, targetFileName));
                } else {
                    if (file.getName().equals(targetFileName)) {
                        resultList.add(file.getAbsolutePath());
                    }
                }
            }
        }

        return resultList;
    }

    @Value
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
            String d = data.get(0).replace("'", "\"");
            if (d.isEmpty()){
              return new ArrayList<String>();
            }
            ObjectMapper mapper = new ObjectMapper();
            ArrayList<String> recs = null;
            try {
                recs = mapper.readValue(d, new TypeReference<ArrayList<String>>(){});
            } catch (JsonProcessingException e) {
                return new ArrayList<String>();
            }
            return recs;
        }
    }

}
