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
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.tree.Xml;

@Value
@EqualsAndHashCode(callSuper = false)
public class SpellCheckCommentsInFrenchPomXml extends Recipe {

    @Override
    public String getDisplayName() {
        return "Fix mis-encoded comments in French";
    }

    @Override
    public String getDescription() {
        return "Use spellchecker to fix mis-encoded French comments in pom.xml files. Mis-encoded comments will contain either '?' or '�'.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Comment visitComment(Xml.Comment comment, ExecutionContext ctx) {
                String commentText = comment.getText();
                if (!commentText.isEmpty() && LanguageDetectorModelClient.getInstance()
                        .getLanguage(commentText).getLanguage().equals("fr")
                ) {
                    String fixedComment = SpellCheckerClient.getInstance().getCommentGradio(commentText);
                    if (!fixedComment.equals(commentText)) {
                        return comment.withText(fixedComment);
                    }
                }
                return comment;
            }
        };
    }
}



