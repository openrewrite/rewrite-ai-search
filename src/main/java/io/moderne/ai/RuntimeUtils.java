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

import lombok.RequiredArgsConstructor;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class RuntimeUtils {
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private RuntimeUtils() {
    }

    public static String exec(String cmd, boolean block) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});
            if (block) {
                StringBuilder sb = new StringBuilder();
                StreamGobbler streamGobbler = new StreamGobbler(proc.getInputStream(), proc.getErrorStream(),
                        sb::append);
                Future<?> future = executorService.submit(streamGobbler);

                if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                    throw new IOException("Execution timed out for command: " + cmd);
                }

                future.get(10, TimeUnit.SECONDS);
                if (proc.exitValue() != 0) {
                    throw new IOException(sb.toString());
                }
                return sb.toString();
            }
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    @RequiredArgsConstructor
    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final InputStream errorStream;
        private final Consumer<String> consumer;

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
                    .forEach(consumer);
            new BufferedReader(new InputStreamReader(errorStream)).lines()
                    .forEach(consumer);
        }
    }
}
