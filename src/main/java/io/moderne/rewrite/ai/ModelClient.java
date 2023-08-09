package io.moderne.rewrite.ai;

import io.github.resilience4j.retry.Retry;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpRetryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static io.moderne.rewrite.ai.RuntimeUtils.exec;
import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
public class ModelClient {
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");

    private final String huggingFaceToken;

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public void start() {
        Path pyLauncher = MODELS_DIR.resolve("get_em.py");
        try {
            if (!Files.exists(pyLauncher)) {
                Files.copy(requireNonNull(ModelClient.class.getResourceAsStream("/get_em.py")), pyLauncher);
            }
            exec("python3 -m pip install --no-python-version-warning --disable-pip-version-check gradio transformers");
            exec("python3 %s/get_em.py".formatted(MODELS_DIR));
            checkForUp();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void checkForUp() {
        Retry retry = Retry.ofDefaults("model-up");
        try {
            Retry.decorateCheckedRunnable(retry, this::checkForUpRequest).run();
        } catch (HttpRetryException e) {
            throw new UncheckedIOException(e);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void checkForUpRequest() throws HttpRetryException {
        HttpResponse<String> response = Unirest.get("http://localhost:7826")
//                .header(HeaderNames.CONTENT_TYPE, "application/json")
//                .header(HeaderNames.AUTHORIZATION, "Bearer " + tenant.apiToken())
                .asString();

        if (!response.isSuccess()) {
            throw new HttpRetryException("Check again for gradio to be up", response.getStatus());
        }
    }
}
