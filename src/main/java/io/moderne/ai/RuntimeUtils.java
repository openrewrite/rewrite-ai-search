package io.moderne.ai;

import lombok.RequiredArgsConstructor;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
