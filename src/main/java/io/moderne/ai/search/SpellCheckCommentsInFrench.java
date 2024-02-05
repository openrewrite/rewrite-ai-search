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


import io.moderne.ai.SpellCheckerClient;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;

@Value
@EqualsAndHashCode(callSuper = false)
public class SpellCheckCommentsInFrench extends Recipe {

    @Override
    public String getDisplayName() {
        return "Fix mis-coded comments in French";
    }

    @Override
    public String getDescription() {
        return "Use spellchecker to fix mis-coded French comments. Mis-coded comments will contain either '?' or '�'.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public Space visitSpace(Space space, Space.Location loc, ExecutionContext ctx) {
                return space.withComments(ListUtils.map(space.getComments(), c -> {
                    if (!c.isMultiline()) {
                        TextComment tc = (TextComment) c;
                        String commentText = tc.getText();
                        if (!commentText.isEmpty()) {
                            String fixedComment = SpellCheckerClient.getInstance().getCommentGradio(commentText);
                            return tc.withText(fixedComment);
                        }
                    }
                    return c;
                }));
            }
        };
    }
}