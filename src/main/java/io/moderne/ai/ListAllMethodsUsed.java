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
package io.moderne.ai;

import io.moderne.ai.table.MethodInUse;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.Optional;
import java.util.stream.Collectors;

public class ListAllMethodsUsed extends Recipe {
    private final transient MethodInUse methodInUse = new MethodInUse(this);

    @Override
    public String getDisplayName() {
        return "List all methods used";
    }

    @Override
    public String getDescription() {
        return "List all methods used in any Java source file.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @SuppressWarnings("OptionalOfNullableMisuse")
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                cu.getTypesInUse().getUsedMethods().forEach(type -> {
                    methodInUse.insertRow(ctx, new MethodInUse.Row(
                            Optional.ofNullable(type.getDeclaringType()).map(Object::toString).orElse(""),
                            type.getName(),
                            Optional.of(type.getReturnType()).map(Object::toString).orElse(""),
                            type.getParameterNames().stream().collect(Collectors.joining(", ")),
                            type.getParameterTypes().stream().map(Object::toString).collect(Collectors.joining(", "))
                    ));
                });
                return cu;
            }
        };
    }
}
