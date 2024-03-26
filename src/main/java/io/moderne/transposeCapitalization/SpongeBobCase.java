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
package io.moderne.transposeCapitalization;


import io.moderne.ai.table.LanguageDistribution;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavadocVisitor;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Javadoc;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.SearchResult;

import java.util.Random;

import static org.openrewrite.Tree.randomId;

@Value
@EqualsAndHashCode(callSuper = false)
public class SpongeBobCase extends Recipe {

    @Override
    public String getDisplayName() {
        return "SpongeBob-case comments";
    }

    @Override
    public String getDescription() {
        return "Change all your comments to be SpongeBob-case.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {


        return new JavaIsoVisitor<ExecutionContext>() {


            // Function to convert text to SpongeBob case with slight randomness
                private String toSpongeBobCase(String input) {
                StringBuilder sb = new StringBuilder(input.length());
                Random random = new Random(01042024);
                boolean toUpperCase = random.nextBoolean(); // Initial choice, randomly upper or lower case

                for (char c : input.toCharArray()) {
                    if (Character.isLetter(c)) {
                        // Apply randomness in changing case, not strictly alternating
                        if (random.nextInt(100) < 85) { // ~85% chance to switch the case, adjust probability as needed
                            toUpperCase = !toUpperCase;
                        }
                        c = toUpperCase ? Character.toUpperCase(c) : Character.toLowerCase(c);
                    }
                    sb.append(c);
                }

                return sb.toString();
            }
            @Override
            public Space visitSpace(Space space, Space.Location loc, ExecutionContext ctx) {
                return space.withComments(ListUtils.map(space.getComments(), comment -> {
                    if (comment instanceof TextComment) {
                        TextComment tc = (TextComment) comment;
                        String commentText = tc.getText();
                        String SpongeBobComment = toSpongeBobCase(commentText);
                        return tc.withText(SpongeBobComment);
                    }
                    return comment;
                }));
            }

        };
    }

}