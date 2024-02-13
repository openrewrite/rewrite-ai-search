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
import io.moderne.ai.SpellCheckerClient;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavadocVisitor;
import org.openrewrite.java.cleanup.RenameJavaDocParamNameVisitor;
import org.openrewrite.java.tree.*;

import java.util.List;

@Value
@EqualsAndHashCode(callSuper = false)
public class SpellCheckCommentsInFrench extends Recipe {

    @Override
    public String getDisplayName() {
        return "Fix miscoded comments in French";
    }

    @Override
    public String getDescription() {
        return "Use spellchecker to fix miscoded French comments. Miscoded comments will contain either '?' or '�'.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            protected JavadocVisitor<ExecutionContext> getJavadocVisitor() {
                return new JavadocVisitor<ExecutionContext>(this) {
                    @Override
                    public Javadoc visitDocComment(Javadoc.DocComment javadoc, ExecutionContext ctx) {
                        Javadoc.DocComment dc = (Javadoc.DocComment) super.visitDocComment(javadoc, ctx);
                        List<Javadoc> Doc = dc.getBody();
                        for (Javadoc docLine : Doc) {
                            if (docLine instanceof Javadoc.Text) {
                                String commentText = docLine.toString();
                                if (!commentText.isEmpty() && LanguageDetectorModelClient.getInstance()
                                        .getLanguage(commentText).getLanguage().equals("fr")
                                ) {
                                    String fixedComment = SpellCheckerClient.getInstance().getCommentGradio(commentText);
                                    docLine = ((Javadoc.Text) docLine).withText(fixedComment);
                                }
                            }
                        }

                        return dc;
                    }
                };
            }

            @Override
            public Space visitSpace(Space space, Space.Location loc, ExecutionContext ctx) {
                return space.withComments(ListUtils.map(space.getComments(), c -> {
                    TextComment tc = (TextComment) c;
                    String commentText = tc.getText();
                    if (!commentText.isEmpty() && LanguageDetectorModelClient.getInstance()
                            .getLanguage(commentText).getLanguage().equals("fr")
                    ) {
                        String fixedComment = SpellCheckerClient.getInstance().getCommentGradio(commentText);
                        return tc.withText(fixedComment);
                    }
                    return c;
                }));
            }
        };
    }
}
