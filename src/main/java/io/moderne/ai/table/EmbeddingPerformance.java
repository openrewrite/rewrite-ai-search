package io.moderne.ai.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

import java.time.Duration;

public class EmbeddingPerformance extends DataTable<EmbeddingPerformance.Row> {

    public EmbeddingPerformance(Recipe recipe) {
        super(recipe,
                "Embedding performance",
                "Latency characteristics of uses of embedding models.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source file",
                description = "The source file that the method call occurred in.")
        String sourceFile;

        @Column(displayName = "Number of requests",
                description = "The count of requests made to the model.")
        int count;

        @Column(displayName = "Max latency",
                description = "The maximum embedding latency.")
        Duration max;
    }
}
