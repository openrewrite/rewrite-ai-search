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

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;


public class TopKMethodMatcher extends DataTable<TopKMethodMatcher.Row> {

    public TopKMethodMatcher(Recipe recipe) {
        super(recipe,
                "Top-K Method Matcher",
                "Result from the scanning recipe for top-k method patterns that match the query.");
    }

    @Value
    public static class Row {

        @Column(displayName = "Method Pattern",
                description = "Method invocation pattern.")
        String methodPattern;

        @Column(displayName = "Method Signature",
                description = "Method invocation signature.")
        String methodSignature;

        @Column(displayName = "Distance",
                description = "The distance between the query and the method invocation.")
        double distance;

        @Column(displayName = "Query",
                description = "The natural language search query.")
        String query;

    }
}
