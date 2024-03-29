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

import io.moderne.ai.AgentRecommenderClient;
import io.moderne.ai.RelatedModelClient;
import io.moderne.ai.table.EmbeddingPerformance;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindCodeThatResembles extends Recipe {
    @Option(displayName = "Resembles",
            description = "The text, either a natural language description or a code sample, " +
                          "that you are looking for.",
            example = "HTTP request with Content-Type application/json")
    String resembles;

    @Option(displayName = "Method filters",
            description = "Since AI based matching has a higher latency than rules based matching, " +
                          "filter the methods that are searched for the `resembles` text. "+
                          "You must include at least one method pattern.",
            example = "kong.unirest.* *(..)")
    List<String> methodFilters;

     @Option(displayName = "Threshold",
            description = "Tunes the sensitivity for matching. The lower the threshold, the stricter the matching is. " +
                          "The higher the threshold, the more matches. Must be between 0 and 1, "+
                          "but we recommend between 0.15-0.35.",
            example = "0.25")
    String threshold;

    transient EmbeddingPerformance performance = new EmbeddingPerformance(this);

    @Override
    public String getDisplayName() {
        return "Find method invocations that resemble a pattern";
    }

    @Override
    public String getDescription() {
        return "This recipe uses a hybrid rules-based and AI approach to find a method invocation" +
               " that resembles a search string.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        List<MethodMatcher> methodMatchers = new ArrayList<>(methodFilters.size());
        for (String m : methodFilters) {
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
                    }
                }
                if (!matches) {
                    return super.visitMethodInvocation(method, ctx);
                }
                
                double thresholdParsed = threshold != null ? Double.parseDouble(threshold) : 0.5;
                RelatedModelClient.Relatedness related = RelatedModelClient.getInstance()
                        .getRelatedness(resembles, method.printTrimmed(getCursor()), thresholdParsed);
                for (Duration timing : related.getEmbeddingTimings()) {
                    requireNonNull(getCursor().<AtomicInteger>getNearestMessage("count")).incrementAndGet();
                    requireNonNull(getCursor().<EmbeddingPerformance.Histogram>getNearestMessage("histogram")).add(timing);
                    AtomicLong max = getCursor().getNearestMessage("max");
                    if (requireNonNull(max).get() < timing.toNanos()) {
                        max.set(timing.toNanos());
                    }
                }
                return related.isRelated() && AgentRecommenderClient.getInstance().isRelated(resembles, method.printTrimmed(getCursor())) ?
                        SearchResult.found(method) :
                        super.visitMethodInvocation(method, ctx);
            }
        });
    }
}
