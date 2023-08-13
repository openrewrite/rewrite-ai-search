package io.moderne.ai.table;

import org.junit.jupiter.api.Test;

import java.time.Duration;

public class EmbeddingPerformanceTest {

    @Test
    void histogram() {
        EmbeddingPerformance.Histogram histogram = new EmbeddingPerformance.Histogram();
        histogram.add(Duration.ofMillis(100));
        histogram.add(Duration.ofMillis(200));
        histogram.add(Duration.ofSeconds(2));
    }
}
