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
package io.moderne.ai.table;

import lombok.Getter;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class GenerativeModelPerformance extends DataTable<GenerativeModelPerformance.Row> {

    public GenerativeModelPerformance(Recipe recipe) {
        super(recipe,
                "Generative model performance",
                "Latency characteristics of uses of generative models.");
    }

    public static class Histogram {
        private static final int BUCKETS = 100;
        private static final long MAX_NANOS = (long) 1e11;

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
                              "of 1 second each.")
        @Nullable
        List<Integer> histogram;

        @Column(displayName = "Max latency",
                description = "The maximum embedding latency.")
        Duration max;
    }
}
