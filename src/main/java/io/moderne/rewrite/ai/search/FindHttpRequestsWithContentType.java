package io.moderne.rewrite.ai.search;

import io.moderne.rewrite.ai.EmbeddingModelClient;
import io.moderne.rewrite.ai.table.EmbeddingPerformance;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class FindHttpRequestsWithContentType extends Recipe {
    @Option(displayName = "Content-type header",
            description = "The value of the `Content-Type` header to search for.",
            example = "application/json")
    private final String contentType;

    @Option(displayName = "Hugging Face token",
            description = "The token to use for the HuggingFace API. Create a " +
                          "[read token](https://huggingface.co/settings/tokens).",
            example = "hf_*****")
    private final String huggingFaceToken;

    private transient EmbeddingModelClient modelClient;
    private final transient EmbeddingPerformance performance = new EmbeddingPerformance(this);

    @Override
    public String getDisplayName() {
        return "Find HTTP requests with a particular `Content-Type` header";
    }

    @Override
    public String getDescription() {
        return "This recipe uses a hybrid rules-based and AI approach to find " +
               "HTTP requests with a particular `Content-Type` header.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher kong = new MethodMatcher("kong.unirest.* *(..)", false);
        MethodMatcher okhttp = new MethodMatcher("okhttp*..* *(..)", true);
        MethodMatcher springWebClient = new MethodMatcher("org.springframework.web.reactive.function.client.WebClient *(..)", true);
        MethodMatcher apacheHttpClient5 = new MethodMatcher("org.apache.hc..* *(..)", true);
        MethodMatcher apacheHttpClient4 = new MethodMatcher("org.apache.http.client..* *(..)", true);

        return new JavaIsoVisitor<>() {
            @Override
            public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    getCursor().putMessage("count", new AtomicInteger());
                    getCursor().putMessage("max", new AtomicLong());
                    J visit = super.visit(tree, ctx);
                    if (getCursor().getMessage("count", new AtomicInteger()).get() > 0) {
                        Duration max = Duration.ofNanos(getCursor().getMessage("max", new AtomicLong()).get());
                        performance.insertRow(ctx, new EmbeddingPerformance.Row((
                                (SourceFile) tree).getSourcePath().toString(),
                                getCursor().getMessage("count", new AtomicInteger()).get(),
                                max));
                    }
                    return visit;
                } else {
                    return super.visit(tree, ctx);
                }
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if(!(kong.matches(method) || okhttp.matches(method) || springWebClient.matches(method) ||
                   apacheHttpClient5.matches(method) || apacheHttpClient4.matches(method))) {
                    return super.visitMethodInvocation(method, ctx);
                }

                if (modelClient == null) {
                    modelClient = new EmbeddingModelClient(huggingFaceToken);
                    modelClient.start();
                }

                EmbeddingModelClient.Relatedness related = modelClient
                        .getRelatedness("HTTP request with Content-Type %s".formatted(contentType), method.printTrimmed(getCursor()));
                for (Duration timing : related.embeddingTimings()) {
                    getCursor().getNearestMessage("count", new AtomicInteger(0)).incrementAndGet();
                    AtomicLong max = getCursor().getNearestMessage("max", new AtomicLong(0));
                    if (max.get() < timing.toNanos()) {
                        max.set(timing.toNanos());
                    }
                }
                if (related.isRelated()) {
                    return SearchResult.found(method);
                }
                return super.visitMethodInvocation(method, ctx);
            }
        };
    }
}
