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
package io.moderne.ai.research;

import io.moderne.ai.AgentGenerativeModelClient;
import io.moderne.ai.ClusteringClient;
import io.moderne.ai.EmbeddingModelClient;
import io.moderne.ai.table.Recommendations;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;


@Value
@EqualsAndHashCode(callSuper = false)
public class GetRecommendations extends ScanningRecipe<GetRecommendations.Accumulator> {

    @Option(displayName = "random sampling",
            description = "Do random sampling or use clusters based on embeddings to sample.")
    @Nullable
    Boolean randomSampling;

    transient Recommendations recommendationsTable = new Recommendations(this);
    private static final Random random = new Random(13);

    @Override
    public String getDisplayName() {
        return "Get recommendations";
    }

    @Override
    public String getDescription() {
        return "This recipe calls an AI model to get recommendations for modernizing" +
               " the code base by looking at a sample of method declarations.";
    }

    @Value
    public class Method {
        String method;
        String name;
        String file;
    }

    public class Accumulator {
        List<Method> methods = new ArrayList<>();
        List<float[]> embeddings = new ArrayList<>();

        int[] centers;

        public int[] getCenters(int numberOfCenters) {
            if (this.centers == null) {
                this.centers = ClusteringClient.getInstance().getCenters(this.embeddings, numberOfCenters);
            }
            return this.centers;
        }

        public Method[] getMethodsToSample(int numberOfCenters) {
            int[] centersIndex = getCenters(numberOfCenters);
            Method[] methodsToSample = new Method[centersIndex.length];
            for (int i = 0; i < getCenters(numberOfCenters).length; i++) {
                methodsToSample[i] = this.methods.get(centersIndex[i]);
            }
            return methodsToSample;

        }

        public void addMethodToSample(String method, String methodName, String file) {
            this.methods.add(new Method(method, methodName, file));
            this.embeddings.add(EmbeddingModelClient.getInstance().getEmbedding(method));
        }

    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                String methodName = md.getSimpleName();
                JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                String source = javaSourceFile.getSourcePath().toString();
                acc.addMethodToSample(md.printTrimmed(getCursor()), methodName, source);
                return md;
            }
        };
    }


    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                boolean isMethodToSample = false;
                JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                String source = javaSourceFile.getSourcePath().toString();
                if (randomSampling) {
                    isMethodToSample = random.nextInt(200) <= 1;
                } else {
                    for (Method methodToSample : acc.getMethodsToSample(10)) {
                        if (methodToSample.file.equals(source) &&
                                methodToSample.name.equals(md.getSimpleName()) &&
                                methodToSample.method.equals(md.printTrimmed(getCursor()))) {
                            isMethodToSample = true;
                            break;
                        }
                    }

                }
                if (isMethodToSample) { // samples based on the results from running GetCodeEmbedding and clustering
                    long time = System.nanoTime();
                    // Get recommendations
                    List<String> recommendations;
                    recommendations = AgentGenerativeModelClient.getInstance().getRecommendations(md.printTrimmed(getCursor()));

                    List<String> recommendationsQuoted = recommendations.stream()
                            .map(element -> "\"" + element + "\"")
                            .collect(Collectors.toList());
                    String recommendationsAsString = "[" + String.join(", ", recommendationsQuoted) + "]";

                    int tokenSize = (int) ((md.printTrimmed(getCursor())).length() / 3.5 + recommendations.toString().length() / 3.5);
                    double elapsedTime = (System.nanoTime() - time) / 1e9;

                    recommendationsTable.insertRow(ctx, new Recommendations.Row(md.getSimpleName(),
                            elapsedTime,
                            tokenSize,
                            recommendationsAsString));
                }
                return md;
            }
        };
    }
}
