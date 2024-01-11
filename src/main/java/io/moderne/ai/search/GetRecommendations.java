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
package io.moderne.ai.search;

import io.moderne.ai.AgentRecommenderClient;
import io.moderne.ai.EmbeddingModelClient;
import io.moderne.ai.table.Embeddings;
import io.moderne.ai.table.Recommendations;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.security.SecureRandom;
import java.util.ArrayList;


@Value
@EqualsAndHashCode(callSuper = false)
public class GetRecommendations extends Recipe {

    transient Recommendations recommendations_table = new Recommendations(this);

    @Override
    public String getDisplayName() {
        return "Get recommendations";
    }

    @Override
    public String getDescription() {
        return "This recipe calls an AI model to get recommendations for modernizing" +
               " the code base by looking at a sample of method declarations.";
    }
    private static final SecureRandom secureRandom = new SecureRandom();
    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                int randomNumber = secureRandom.nextInt(100);
                if (randomNumber < 90) { // sample 1% of methods
                    // Get recommendations
                    ArrayList<String> recommendations = AgentRecommenderClient.getInstance().getRecommendations(md.printTrimmed(getCursor()));
                    recommendations_table.insertRow(ctx, new Recommendations.Row(md.getSimpleName(), recommendations));
                }
                return md;
            }
        };


        }


}
