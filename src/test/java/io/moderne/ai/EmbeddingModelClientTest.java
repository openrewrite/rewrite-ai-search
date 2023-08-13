package io.moderne.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class EmbeddingModelClientTest {

    @Test
    void start() {
        EmbeddingModelClient client = new EmbeddingModelClient("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");
        client.start();
        assertThat(client.getEmbedding("test")).hasSizeGreaterThan(0);
    }
}
