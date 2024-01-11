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

import java.util.ArrayList;


public class Recommendations extends DataTable<Recommendations.Row> {

    public Recommendations(Recipe recipe) {
        super(recipe,
                "Recommendations",
                "Collects the recommendations based on sampled methods.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Name",
                description = "Name of the class or method")
        String name;

        @Column(displayName = "Recommendation",
                description = "The recommendations based on the method")
        ArrayList<String> Recommendations;
    }
}
