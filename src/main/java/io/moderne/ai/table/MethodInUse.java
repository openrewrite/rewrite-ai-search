/*
 * Copyright 2024 the original author or authors.
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
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class MethodInUse extends DataTable<MethodInUse.Row> {

    public MethodInUse(Recipe recipe) {
        super(recipe, "Methods used",
                "Methods used in any Java source file.");
    }

    @Value
    public static class Row {
        String declaringType;
        String methodName;
        String returnType;
        String parameters;
    }
}
