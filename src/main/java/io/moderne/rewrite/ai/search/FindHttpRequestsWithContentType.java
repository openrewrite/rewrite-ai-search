package io.moderne.rewrite.ai.search;

import io.moderne.rewrite.ai.EmbeddingModelClient;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class FindHttpRequestsWithContentType extends Recipe {
    @Option(displayName = "Content-type header",
            description = "The value of the `Content-Type` header to search for.",
            example = "application/json")
    private final String contentType;

    // huggingface token option
    @Option(displayName = "Hugging Face token",
            description = "The token to use for the HuggingFace API. Create a " +
                          "[read token](https://huggingface.co/settings/tokens).",
            example = "hf_*****")
    private final String huggingFaceToken;

    private transient EmbeddingModelClient modelClient;

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
        return new JavaIsoVisitor<>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (modelClient == null) {
                    modelClient = new EmbeddingModelClient(huggingFaceToken);
                    modelClient.start();
                }
                if (modelClient.isRelated("HTTP request with Content-Type %s".formatted(contentType),
                        method.printTrimmed(getCursor()))) {
                    return SearchResult.found(method);
                }
                return super.visitMethodInvocation(method, ctx);
            }
        };
    }
}
