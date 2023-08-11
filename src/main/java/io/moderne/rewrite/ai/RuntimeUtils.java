package io.moderne.rewrite.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RuntimeUtils {
    private RuntimeUtils() {
    }

    public static String exec(String cmd, boolean block) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", cmd});
            if(block) {
                if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                    throw new IOException("Execution timed out for command: " + cmd);
                }
                if (proc.exitValue() != 0) {
                    try (BufferedReader reader = proc.errorReader()) {
                        throw new IOException(reader.lines().collect(Collectors.joining("\n")));
                    }
                }
                try (BufferedReader reader = proc.inputReader()) {
                    return reader.lines().collect(Collectors.joining("\n")).trim();
                }
            }
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
