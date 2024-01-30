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
import org.openrewrite.internal.lang.Nullable;
import java.io.*;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentRecommenderClient {
    @Nullable
    private static AgentRecommenderClient INSTANCE;
    public static synchronized AgentRecommenderClient getInstance() {
        if (INSTANCE == null) {
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            try {
                Runtime runtime = Runtime.getRuntime();
                Process proc_make = runtime.exec(new String[]{"/bin/sh", "-c", "make -C /app/llama.cpp"});
                proc_make.waitFor();

                new BufferedReader(new InputStreamReader(proc_make.getInputStream())).lines()
                        .forEach(procOut::println);
                new BufferedReader(new InputStreamReader(proc_make.getErrorStream())).lines()
                        .forEach(procOut::println);
                if (proc_make.exitValue() != 0) {
                    throw new RuntimeException("Failed to make llama.cpp\n" + sw);
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e + "\nOutput: " + sw);
            }
            INSTANCE = new AgentRecommenderClient();
            return INSTANCE;
        }
        return INSTANCE;
    }

    public ArrayList<String> getRecommendations(String code, int batch_size) {
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        StringWriter errorSw = new StringWriter();

        Runtime runtime = Runtime.getRuntime();
        String contextLength = String.valueOf((int) ((code.length() / 3.5)) + 400);
        String cmd = "/app/llama.cpp/main -m /MODELS/codellama.gguf";

        try (
                BufferedReader bufferedReader = new BufferedReader(new FileReader("/app/prompt.txt"));
                FileWriter fileWriter = new FileWriter("/app/input.txt", false)
        ) {
            String throwError = "";
            String line;
            StringBuilder promptContent = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                promptContent.append(line).append("\n");
            }
            fileWriter.write("[INST]" + promptContent + code + "```\n[/INST]1." );

            String flags = " -f /app/input.txt"
                    + " -n 150 -c " + contextLength + " -b "+ batch_size;
//                    " 2>/app/llama_log.txt --no-display-prompt -b " + batch_size;

            Process proc_llama = runtime.exec(new String[]{"/bin/sh", "-c", cmd + flags});
            proc_llama.waitFor();
            new BufferedReader(new InputStreamReader(proc_llama.getInputStream())).lines()
                    .forEach(procOut::println);

            ArrayList<String> recommendations = parseRecommendations("1." + sw);

//            if (recommendations.isEmpty()) {
            if (true) {
                BufferedReader bufferedReaderLog = new BufferedReader(new FileReader("/app/llama_log.txt"));
                String logLine;
                StringBuilder logContent = new StringBuilder();
                while ((logLine = bufferedReaderLog.readLine()) != null) {
                    logContent.append(logLine).append("\n");
                }
                bufferedReaderLog.close();
                throw new RuntimeException("Logs: " + logContent +
                        "\n\n Input was: " + "[INST]" + promptContent + code + "```\n[/INST]1.\n\n\n" +
                        "Output was " + recommendations);
            }
            throw new RuntimeException("Input was: " + "[INST]" + promptContent + code + "```\n[/INST]1.\n\n\n" +
                    "Output was " + recommendations);

//            return recommendations;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e + "\nOutput: " + errorSw);
        }
    }

    public ArrayList<String> parseRecommendations(String recommendations) {
        if (recommendations.equals("[]")) {
            return new ArrayList<String>();
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


}
