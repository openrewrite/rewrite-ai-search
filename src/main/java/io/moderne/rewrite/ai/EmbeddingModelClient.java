package io.moderne.rewrite.ai;

import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import kong.unirest.HeaderNames;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import org.openrewrite.internal.MetricsHelper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpRetryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;

import static io.moderne.rewrite.ai.RuntimeUtils.exec;
import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
public class EmbeddingModelClient {
    private static final Path MODELS_DIR = Paths.get(System.getProperty("user.home") + "/.moderne/models");
    private static final double RELATED_THRESHOLD = 0.0755;

    private final String huggingFaceToken;

    private final LinkedHashMap<String, float[]> embeddingCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
            return size() > 1000;
        }
    };

    static {
        if (!Files.exists(MODELS_DIR) && !MODELS_DIR.toFile().mkdirs()) {
            throw new IllegalStateException("Unable to create models directory at " + MODELS_DIR);
        }
    }

    public void start() {
        Path pyLauncher = MODELS_DIR.resolve("get_em.py");
        try {
            if (!Files.exists(pyLauncher)) {
                Files.copy(requireNonNull(EmbeddingModelClient.class.getResourceAsStream("/get_em.py")), pyLauncher);
            }
            exec("python3 -m pip install --no-python-version-warning --disable-pip-version-check gradio transformers");
            exec("nohup HUGGING_FACE_TOKEN=%s python3 %s/get_em.py >/dev/null 2>&1 &"
                    .formatted(huggingFaceToken, MODELS_DIR));
            if (!checkForUp()) {
                throw new IllegalStateException("Unable to start model daemon");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean checkForUp() {
        Retry retry = Retry.ofDefaults("model-up");
        try {
            Retry.decorateCheckedRunnable(retry, this::checkForUpRequest).run();
        } catch (Throwable e) {
            return false;
        }
        return true;
    }

    private void checkForUpRequest() throws HttpRetryException {
        HttpResponse<String> response = Unirest.head("http://127.0.0.1:7860")
                .asString();
        if (!response.isSuccess()) {
            throw new HttpRetryException("Check again for gradio to be up", response.getStatus());
        }
    }

    public boolean isRelated(String t1, String t2) {
        return isRelated(t1, t2, RELATED_THRESHOLD);
    }

    public boolean isRelated(String t1, String t2, double threshold) {
        float[] e1 = embeddingCache.computeIfAbsent(t1, this::getEmbedding);
        float[] e2 = embeddingCache.computeIfAbsent(t2.replace("\n", ""), this::getEmbedding);
        return dist(e1, e2) <= threshold;
    }

    private static double dist(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vectors must have the same dimension");
        }
        float sumOfSquaredDifferences = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sumOfSquaredDifferences += diff * diff;
        }
        return Math.sqrt(sumOfSquaredDifferences);
    }

    public float[] getEmbedding(String text) {
        Timer.Sample sample = Timer.start();
        HttpResponse<GradioResponse> response = Unirest.post("http://127.0.0.1:7860/run/predict")
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .body(new GradioRequest(text))
                .asObject(GradioResponse.class);
        if (!response.isSuccess()) {
            IllegalStateException t = new IllegalStateException("Unable to get embedding. HTTP " + response.getStatus());
            sample.stop(MetricsHelper.errorTags(Timer.builder("rewrite.ai.get.embedding"), t)
                    .register(Metrics.globalRegistry));
            throw t;
        }
        float[] em = response.getBody().getEmbedding();
        sample.stop(MetricsHelper.successTags(Timer.builder("rewrite.ai.get.embedding"))
                .register(Metrics.globalRegistry));
        return em;
    }

    private record GradioRequest(String... data) {
    }

    private record GradioResponse(List<String> data) {
        public float[] getEmbedding() {
            String d = data.get(0);
            String[] emStr = d.substring(1, d.length() - 1).split(",");
            float[] em = new float[emStr.length];
            for (int i = 0; i < emStr.length; i++) {
                em[i] = Float.parseFloat(emStr[i]);
            }
            return em;
        }
    }
}
