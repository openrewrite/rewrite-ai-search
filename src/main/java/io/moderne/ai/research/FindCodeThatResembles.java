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

import io.moderne.ai.AgentGenerativeModelClient;
import io.moderne.ai.EmbeddingModelClient;
import io.moderne.ai.RelatedModelClient;
import io.moderne.ai.table.CodeSearch;
import io.moderne.ai.table.EmbeddingPerformance;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindCodeThatResembles extends ScanningRecipe<FindCodeThatResembles.Accumulator> {
    @Option(displayName = "Resembles",
            description = "The text, either a natural language description or a code sample, " +
                          "that you are looking for.",
            example = "HTTP request with Content-Type application/json")
    String resembles;

    @Option(displayName = "top k methods",
            description = "Since AI based matching has a higher latency than rules based matching, " +
                          "we do a first pass to find the top k methods using embeddings. " +
                          "To narrow the scope, you can specify the top k methods as method filters.",
            example = "1000")
    int k;

    transient CodeSearch codeSearchTable = new CodeSearch(this);

    transient EmbeddingPerformance performance = new EmbeddingPerformance(this);

    @Override
    public String getDisplayName() {
        return "Find method invocations that resemble a pattern";
    }

    @Override
    public String getDescription() {
        return "This recipe uses two phase AI approach to find a method invocation" +
               " that resembles a search string.";
    }

    @Value
    private static class MethodSignatureWithDistance {
        String methodSignature;
        String methodPattern;
        double distance;
    }

    @Value
    @RequiredArgsConstructor
    public static class Accumulator {
        int k;
        PriorityQueue<MethodSignatureWithDistance> methodSignaturesQueue = new PriorityQueue<>(Comparator.comparingDouble(MethodSignatureWithDistance::getDistance));
        EmbeddingModelClient embeddingModelClient = EmbeddingModelClient.getInstance();
        @NonFinal
        @Nullable
        List<MethodMatcher> topMethodPatterns;

        public void add(String methodSignature, String methodPattern, String resembles) {
            for (MethodSignatureWithDistance entry : methodSignaturesQueue) {
                if (entry.methodPattern.equals(methodPattern)) {
                    return;
                }
            }
            MethodSignatureWithDistance methodSignatureWithDistance = new MethodSignatureWithDistance(methodSignature,
                    methodPattern,
                    (float) embeddingModelClient.getDistance(resembles, methodSignature));
            methodSignaturesQueue.add(methodSignatureWithDistance);
        }

        public List<MethodMatcher> getMethodMatchersTopK() {
            if (topMethodPatterns != null) {
                return topMethodPatterns;
            }
            topMethodPatterns = new ArrayList<>(k);
            for (int i = 0; i < k && !methodSignaturesQueue.isEmpty(); i++) {
                topMethodPatterns.add(new MethodMatcher(methodSignaturesQueue.poll().getMethodPattern(), true));
            }
            return topMethodPatterns;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator(k);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {

        return new JavaIsoVisitor<ExecutionContext>() {


            private String extractTypeName(String fullyQualifiedTypeName) {
                return fullyQualifiedTypeName.replace("<.*>", "")
                        .substring(fullyQualifiedTypeName.lastIndexOf('.') + 1);
            }

            @SuppressWarnings("OptionalOfNullableMisuse")
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                cu.getTypesInUse().getUsedMethods().forEach(type -> {
                    String methodSignature = extractTypeName(Optional.ofNullable(type.getReturnType())
                                    .map(Object::toString).orElse("")) + " " + type.getName() ;

                    String[] parameters = new String[type.getParameterTypes().size()];
                    for (int i = 0; i < type.getParameterTypes().size(); i++) {
                        String typeName = extractTypeName(type.getParameterTypes().get(i).toString());
                        String paramName = type.getParameterNames().get(i);
                        parameters[i] = typeName + " " + paramName ;
                    }

                    methodSignature +=  "(" + String.join(", ", parameters) + ")";

                    String methodPattern =
                            Optional.ofNullable(type.getDeclaringType()).map(Object::toString)
                                                   .orElse("") + " " + type.getName() + "(..)";

                    acc.add(methodSignature, methodPattern, resembles);
                });

                return super.visitCompilationUnit(cu, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        List<MethodMatcher> methodMatchers = acc.getMethodMatchersTopK();

        List<TreeVisitor<?, ExecutionContext>> preconditions = new ArrayList<>(methodMatchers.size());
        for (MethodMatcher m : methodMatchers) {
            preconditions.add(new UsesMethod<>(m));
        }

        //noinspection unchecked
        return Preconditions.check(Preconditions.or(preconditions.toArray(new TreeVisitor[0])), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                getCursor().putMessage("count", new AtomicInteger());
                getCursor().putMessage("max", new AtomicLong());
                getCursor().putMessage("histogram", new EmbeddingPerformance.Histogram());
                try {
                    return super.visitCompilationUnit(cu, ctx);
                } finally {
                    if (getCursor().getMessage("count", new AtomicInteger()).get() > 0) {
                        Duration max = Duration.ofNanos(requireNonNull(getCursor().<AtomicLong>getMessage("max")).get());
                        performance.insertRow(ctx, new EmbeddingPerformance.Row((
                                (SourceFile) cu).getSourcePath().toString(),
                                requireNonNull(getCursor().<AtomicInteger>getMessage("count")).get(),
                                requireNonNull(getCursor().<EmbeddingPerformance.Histogram>getMessage("histogram")).getBuckets(),
                                max));
                    }
                }
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {

                if (!getLanguage().equals("java")) {
                    return super.visitMethodInvocation(method, ctx);
                }

                boolean matches = false;
                for (MethodMatcher methodMatcher : methodMatchers) {
                    if (methodMatcher.matches(method)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    return super.visitMethodInvocation(method, ctx);
                }

                RelatedModelClient.Relatedness related = RelatedModelClient.getInstance()
                        .getRelatedness(resembles, method.printTrimmed(getCursor()));
                for (Duration timing : related.getEmbeddingTimings()) {
                    requireNonNull(getCursor().<AtomicInteger>getNearestMessage("count")).incrementAndGet();
                    requireNonNull(getCursor().<EmbeddingPerformance.Histogram>getNearestMessage("histogram")).add(timing);
                    AtomicLong max = getCursor().getNearestMessage("max");
                    if (requireNonNull(max).get() < timing.toNanos()) {
                        max.set(timing.toNanos());
                    }
                }
                int resultEmbeddingModels = related.isRelated();
                boolean result;
                boolean calledGenerativeModel = false;
                if (resultEmbeddingModels == 0) {
                    result = AgentGenerativeModelClient.getInstance().isRelated(resembles, method.printTrimmed(getCursor()), 0.5932);
                    calledGenerativeModel = true;
                } else {
                    result = resultEmbeddingModels == 1;
                }

                // Populate data table for debugging model's accuracy
                JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                String source = javaSourceFile.getSourcePath().toString();
                if (result) {
                    codeSearchTable.insertRow(ctx, new CodeSearch.Row(
                            source,
                            method.printTrimmed(getCursor()),
                            resembles,
                            resultEmbeddingModels,
                            calledGenerativeModel ? ( result ? 1 : -1) : 0
                    ));
                }

                return result ?
                        SearchResult.found(method) :
                        super.visitMethodInvocation(method, ctx);
            }
        });
    }
}
