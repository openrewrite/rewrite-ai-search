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
package io.moderne.ai.research;

import io.moderne.ai.EmbeddingModelClient;
import io.moderne.ai.table.Embeddings;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;

@Value
@EqualsAndHashCode(callSuper = false)
public class GetCodeEmbedding extends Recipe {

    @Option(displayName = "Code snippet type",
            description = "Choose whether you want to get an embedding for the classes or methods.",
            example = "methods",
            valid = {"methods", "classes"})
    String codeSnippetType;


    transient Embeddings embeddings = new Embeddings(this);

    @Override
    public String getDisplayName() {
        return "Get embeddings for code snippets in code";
    }

    @Override
    public String getDescription() {
        return "This recipe calls an AI model to get an embedding for either classes or methods" +
               " which can then be used for downstream tasks.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        if ("methods".equals(codeSnippetType)) {
            return new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                    J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                    // Get embedding
                    JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                    float[] embedding = EmbeddingModelClient.getInstance().getEmbedding(md.printTrimmed(getCursor()));
                    embeddings.insertRow(ctx, new Embeddings.Row(javaSourceFile.getSourcePath().toString(), md.getSimpleName(), embedding));
                    return md;
                }
            };
        } else {
            return new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration clazz, ExecutionContext ctx) {
                    J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    // Get embedding
                    JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                    float[] embedding = EmbeddingModelClient.getInstance().getEmbedding(cd.printTrimmed(getCursor()));
                    embeddings.insertRow(ctx, new Embeddings.Row(javaSourceFile.getSourcePath().toString(), cd.getSimpleName(), embedding));
                    return cd;
                }
            };
        }


    }
}
