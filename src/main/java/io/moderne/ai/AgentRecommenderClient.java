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
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentRecommenderClient {
    @Nullable
    private static AgentRecommenderClient instance;
    private static HashMap<String, String> methodsToSample;

    public static synchronized AgentRecommenderClient getInstance() {
        if (instance == null) {
            //Check if llama.cpp is already built
            File f = new File("/app/llama.cpp/main");
            if(f.exists() && !f.isDirectory()) {
                instance = new AgentRecommenderClient();
                return instance;
            }
            StringWriter sw = new StringWriter();
            PrintWriter procOut = new PrintWriter(sw);
            try {

                Runtime runtime = Runtime.getRuntime();
                Process procMake = runtime.exec(new String[]{"/bin/sh", "-c", "make -C /app/llama.cpp"});
                procMake.waitFor();

                new BufferedReader(new InputStreamReader(procMake.getInputStream())).lines()
                        .forEach(procOut::println);
                new BufferedReader(new InputStreamReader(procMake.getErrorStream())).lines()
                        .forEach(procOut::println);
                if (procMake.exitValue() != 0) {
                    throw new RuntimeException("Failed to make llama.cpp\n" + sw);
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e + "\nOutput: " + sw);
            }
            instance = new AgentRecommenderClient();
            return instance;
        }
        return instance;
    }

    public static void populateMethodsToSample(String pathToCenters) {
        HashMap<String, String> tempMethodsToSample = new HashMap<>();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(pathToCenters))){
            String line;
            String source;
            String methodCall;
            while ((line = bufferedReader.readLine()) != null) {
                source = line.split(" ")[0];
                methodCall = line.split(" ")[1];
                tempMethodsToSample.put(source, methodCall);
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't read which methods to sample. " + e);
        }

        methodsToSample = tempMethodsToSample;

    }

    public static HashMap<String, String> getMethodsToSample() {
        return methodsToSample;
    }

    public ArrayList<String> getRecommendations(String code, int batch_size) {
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        StringWriter errorSw = new StringWriter();

        try (
                BufferedReader bufferedReader = new BufferedReader(new FileReader("/app/prompt.txt"));
                FileWriter fileWriter = new FileWriter("/app/input.txt", false)
        ) {
            // Write a temporary file for input which includes prompt and relevant code snippet
            String line;
            StringBuilder promptContent = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                promptContent.append(line).append("\n");
            }
            fileWriter.write("[INST]" + promptContent + code + "```\n[/INST]1." );

            // Arguments to send to model
            String contextLength = String.valueOf((int) ((code.length() / 3.5)) + 400);
            String cmd = "/app/llama.cpp/main -m /MODELS/codellama.gguf";
            String flags = " -f /app/input.txt --temp 0.50"
                    + " -n 150 -c " + contextLength +
                    " 2>/app/llama_log.txt --no-display-prompt -b " + batch_size;

            // Call llama.cpp
            Runtime runtime = Runtime.getRuntime();
            Process procLlama = runtime.exec(new String[]{"/bin/sh", "-c", cmd + flags});
            procLlama.waitFor();
            new BufferedReader(new InputStreamReader(procLlama.getInputStream())).lines()
                    .forEach(procOut::println);

            ArrayList<String> recommendations = parseRecommendations("1." + sw);

            if (recommendations.isEmpty()) {
                BufferedReader bufferedReaderLog = new BufferedReader(new FileReader("/app/llama_log.txt"));
                String logLine;
                StringBuilder logContent = new StringBuilder();
                while ((logLine = bufferedReaderLog.readLine()) != null) {
                    logContent.append(logLine).append("\n");
                }
                bufferedReaderLog.close();
                throw new RuntimeException("Logs: " + logContent);
            }

            return recommendations;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e + "\nOutput: " + errorSw);
        }
    }

    public ArrayList<String> parseRecommendations(String recommendations) {
        if ("[]".equals(recommendations)) {
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

    public boolean isRelated(String query, String code) {
        //Make call to model
        StringWriter sw = new StringWriter();
        PrintWriter procOut = new PrintWriter(sw);
        StringWriter errorSw = new StringWriter();

        try (
                FileWriter fileWriter = new FileWriter("/app/inputRelated.txt", false)
        ) {
            // Write a temporary file for input which includes prompt and relevant code snippet
            String line;
            String promptContent = "Does this query match the code snippet?\n";
            promptContent += "Query: " + query + "\n";
            promptContent += "Code: " + code + "\n";
            promptContent += "Answer as 'ANS: Yes' or 'ANS: No'.\n";
            fileWriter.write("[INST]" + promptContent + "[/INST]ANS:" );

            // Arguments to send to model
            String contextLength = String.valueOf((int) ((code.length() + query.length()) / 3.5) + 100); //buffer of 100
            String cmd = "/app/llama.cpp/main -m /MODELS/codellama.gguf";
            String flags = " -f /app/inputRelated.txt --temp 0.01"
                    + " -n 2 -c " + contextLength +
                    " 2>/app/llama_log.txt --no-display-prompt";

            // Call llama.cpp
            Runtime runtime = Runtime.getRuntime();
            Process procLlama = runtime.exec(new String[]{"/bin/sh", "-c", cmd + flags});
            procLlama.waitFor();
            new BufferedReader(new InputStreamReader(procLlama.getInputStream())).lines()
                    .forEach(procOut::println);

            return parseRelated(sw);

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e + "\nOutput: " + errorSw);
        }

    }

    private boolean parseRelated(StringWriter sw) {
        // check if Yes or yes in output, return true if it does
        return sw.toString().contains("Yes") || sw.toString().contains("yes");

    }


}
