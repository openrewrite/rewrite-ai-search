package io.moderne.rewrite.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EmbeddingModelClientTest {

    @Test
    void start() {
        EmbeddingModelClient client = new EmbeddingModelClient("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");
        client.start();
        assertThat(client.getEmbedding("test")).hasSizeGreaterThan(0);
    }
}
