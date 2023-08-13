package io.moderne.ai.table;

import lombok.Getter;
import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;
import org.openrewrite.internal.lang.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class EmbeddingPerformance extends DataTable<EmbeddingPerformance.Row> {

    public EmbeddingPerformance(Recipe recipe) {
        super(recipe,
                "Embedding performance",
                "Latency characteristics of uses of embedding models.");
    }

    public static class Histogram {
        private static final int BUCKETS = 100;
        private static final long MAX_NANOS = (int) 1e9;

        @Getter
        @Nullable
        List<Integer> buckets;

        public Histogram() {
        }

        public void add(Duration duration) {
            int bucket = (int) (duration.toNanos() / (MAX_NANOS / BUCKETS));
            if (bucket < BUCKETS) {
                if (buckets == null) {
                    buckets = new ArrayList<>(BUCKETS);
                    for (int i = 0; i < BUCKETS; i++) {
                        buckets.add(0);
                    }
                }
                buckets.set(bucket, buckets.get(bucket) + 1);
            }
        }
    }

    @Value
    public static class Row {
        @Column(displayName = "Source file",
                description = "The source file that the method call occurred in.")
        String sourceFile;

        @Column(displayName = "Number of requests",
                description = "The count of requests made to the model.")
        int count;

        @Column(displayName = "Histogram",
                description = "The latency histogram of the requests made to the model (counts). " +
                              "The histogram is a non-cumulative fixed distribution of 100 buckets " +
                              "of 0.01 second each.")
        @Nullable
        List<Integer> histogram;

        @Column(displayName = "Max latency",
                description = "The maximum embedding latency.")
        Duration max;
    }
}
