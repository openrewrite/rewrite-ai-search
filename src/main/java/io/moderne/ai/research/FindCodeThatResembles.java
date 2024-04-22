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
import io.moderne.ai.table.EmbeddingPerformance;
import io.moderne.ai.table.MethodInUse;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.yaml.CopyValue;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

@Value
@EqualsAndHashCode(callSuper = true)
public class FindCodeThatResembles extends ScanningRecipe<FindCodeThatResembles.Accumulator> {
    @Option(displayName = "Resembles",
            description = "The text, either a natural language description or a code sample, " +
                          "that you are looking for.",
            example = "HTTP request with Content-Type application/json")
    String resembles;

    @Option(displayName = "top k methods",
            description = "Since AI based matching has a higher latency than rules based matching, " +
                          "we do a first pass to find the top k methods using embeddings. "+
                          "To narrow the scope, you can specify the top k methods as method filters.",
            example = "1000")
    int k;


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
    public class MethodSignatureWithDistance{
        String methodSignature;
        String methodPattern;
        double distance;


        public MethodSignatureWithDistance(String methodSignature, String methodPattern, float distance) {
            this.methodSignature = methodSignature;
            this.methodPattern = methodPattern;
            this.distance = distance;
        }

        public double getDistance() {
            return this.distance;
        }

        public String getMethodPattern() {
            return this.methodPattern;
        }
    }
    public class Accumulator{
        PriorityQueue<MethodSignatureWithDistance> methodSignaturesQueue = new PriorityQueue<>(Comparator.comparingDouble(MethodSignatureWithDistance::getDistance));
        EmbeddingModelClient embeddingModelClient = EmbeddingModelClient.getInstance();

        public void add(String methodSignature, String methodPattern, String resembles) {
            for (MethodSignatureWithDistance entry : methodSignaturesQueue){
                if (entry.methodPattern.equals(methodPattern)){
                    return;
                }
            }
            MethodSignatureWithDistance methodSignatureWithDistance = new MethodSignatureWithDistance(methodSignature,
                            methodPattern,
                            (float) embeddingModelClient.getDistance(resembles, methodSignature));
            methodSignaturesQueue.add(methodSignatureWithDistance);
        }

        public List<String> getMethodSignaturesTopK(int k) {
            List<String> topMethodPatterns = new ArrayList<>();
            int count = 0;
            for (MethodSignatureWithDistance entry : methodSignaturesQueue) {
                if (count < k ) {
                    topMethodPatterns.add(entry.getMethodPattern());
                    count++;
                } else {
                    break;
                }
            }
            return topMethodPatterns;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            private String extractTypeName(String fullyQualifiedTypeName){
                return fullyQualifiedTypeName.substring(fullyQualifiedTypeName.lastIndexOf('.') + 1);
            }

            @SuppressWarnings("OptionalOfNullableMisuse")
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                cu.getTypesInUse().getUsedMethods().forEach(type -> {
                    String methodSignature= "" ;
                    methodSignature +=
                            extractTypeName(Optional.of(type.getReturnType()).map(Object::toString).orElse("")) + " " +
                            type.getName() + " (" +
                            IntStream.range(0, type.getParameterNames().size())
                                    .mapToObj(i -> extractTypeName(type.getParameterTypes().get(i).toString())
                                                   + " " + type.getParameterNames().get(i))
                                    .collect(Collectors.joining(", ")) + ")";
                    String methodPattern = Optional.ofNullable(type.getDeclaringType()).map(Object::toString)
                            .orElse("").replaceAll("<[^>]*>", "")
                            + " " + type.getName() + "(..)" ;
                    acc.add(methodSignature, methodPattern, resembles);
                });
                return cu;
            }
        };

    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        List<MethodMatcher> methodMatchers = new ArrayList<>(k);

        for (String m : acc.getMethodSignaturesTopK(k)) {
            methodMatchers.add(new MethodMatcher(m, true));
        }

        List<TreeVisitor<?, ExecutionContext>> preconditions = new ArrayList<>(methodMatchers.size());
        for (MethodMatcher m : methodMatchers) {
            preconditions.add(new UsesMethod<>(m));
        }

        //noinspection unchecked
        return Preconditions.check(Preconditions.or(preconditions.toArray(new TreeVisitor[0])), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    getCursor().putMessage("count", new AtomicInteger());
                    getCursor().putMessage("max", new AtomicLong());
                    getCursor().putMessage("histogram", new EmbeddingPerformance.Histogram());
                    J visit = super.visit(tree, ctx);
                    if (getCursor().getMessage("count", new AtomicInteger()).get() > 0) {
                        Duration max = Duration.ofNanos(requireNonNull(getCursor().<AtomicLong>getMessage("max")).get());
                        performance.insertRow(ctx, new EmbeddingPerformance.Row((
                                (SourceFile) tree).getSourcePath().toString(),
                                requireNonNull(getCursor().<AtomicInteger>getMessage("count")).get(),
                                requireNonNull(getCursor().<EmbeddingPerformance.Histogram>getMessage("histogram")).getBuckets(),
                                max));
                    }
                    return visit;
                } else {
                    return super.visit(tree, ctx);
                }
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
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
                if (resultEmbeddingModels == 0) {
                    result = AgentGenerativeModelClient.getInstance().isRelated(resembles, method.printTrimmed(getCursor()),  0.5932);
                } else{
                    result = resultEmbeddingModels == 1;
                }
                return  result  ?
                        SearchResult.found(method) :
                        super.visitMethodInvocation(method, ctx);
            }
        });


    }
}
