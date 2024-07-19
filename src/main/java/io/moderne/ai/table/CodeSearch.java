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


public class CodeSearch extends DataTable<CodeSearch.Row> {

    public CodeSearch(Recipe recipe) {
        super(recipe,
                "Code Search",
                "Searches for method invocations that resemble a natural language query.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source",
                description = "Source")
        String source;

        @Column(displayName = "Method",
                description = "Method invocation")
        String method;

        @Column(displayName = "Query",
                description = "Natural language query")
        String query;

        @Column(displayName = "Result of first models",
                description = "First two embeddings models result," +
                              " where -1 means negative match, 0 means unsure, and 1 means positive match.")
        int resultEmbedding;

        @Column(displayName = "Called second model",
                description = "True if the generative model was used.")
        boolean calledGenerative;

        @Column(displayName = "Result of second model",
                description = "Second generative model's result.")
        boolean resultGenerative;
    }
}
