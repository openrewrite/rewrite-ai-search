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


import io.moderne.ai.LanguageDetectorModelClient;
import io.moderne.ai.table.LanguageDistribution;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.SearchResult;

import static org.openrewrite.Tree.randomId;

@Value
@EqualsAndHashCode(callSuper = false)
public class FindCommentsLanguage extends Recipe {

    @Override
    public String getDisplayName() {
        return "Find comments' language distribution";
    }

    @Override
    public String getDescription() {
        return "Finds all comments and uses AI to predict which language the comment is in.";
    }

    transient LanguageDistribution distribution = new LanguageDistribution(this);

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public Space visitSpace(Space space, Space.Location loc, ExecutionContext ctx) {
                return space.withComments(ListUtils.map(space.getComments(), comment -> {
                    if (comment instanceof TextComment) {
                        JavaSourceFile javaSourceFile = getCursor().firstEnclosing(JavaSourceFile.class);
                        distribution.insertRow(ctx, new LanguageDistribution.Row(
                                javaSourceFile.getSourcePath().toString(),
                                ((TextComment) comment).getText().toString(),
                                LanguageDetectorModelClient.getInstance().getLanguage(((TextComment) comment).getText().toString()).getLanguage()
                                )
                        );


                        return comment.withMarkers(comment.getMarkers().
                                computeByType(new SearchResult(randomId(), null), (s1, s2) -> s1 == null ? s2 : s1));

                    }
                    return comment;
                }));
            }

        };
    }
}