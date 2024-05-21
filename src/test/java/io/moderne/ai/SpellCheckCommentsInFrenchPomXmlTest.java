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
package io.moderne.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.maven.Assertions.pomXml;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class SpellCheckCommentsInFrenchPomXmlTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new SpellCheckCommentsInFrenchPomXml());
    }

    @DocumentExample
    @Test
    void pom() {
        rewriteRun(
          spec -> spec.recipe(
            new SpellCheckCommentsInFrenchPomXml()
          ),
          pomXml(
            """
             <project>
               <modelVersion>4.0.0</modelVersion>
               <groupId>com.mycompany.app</groupId>
               <artifactId>my-app</artifactId>
               <version>1</version>
               <dependencies>
                 <dependency>
                   <!-- c'est une d?pendance incorpor? -->
                   <groupId>com.google.guava</groupId>
                   <artifactId>guava</artifactId>
                   <version>29.0-jre</version>
                 </dependency>
               </dependencies>
             </project>
             """,
             """
             <project>
               <modelVersion>4.0.0</modelVersion>
               <groupId>com.mycompany.app</groupId>
               <artifactId>my-app</artifactId>
               <version>1</version>
               <dependencies>
                 <dependency>
                   <!-- c'est une dépendance incorporé -->
                   <groupId>com.google.guava</groupId>
                   <artifactId>guava</artifactId>
                   <version>29.0-jre</version>
                 </dependency>
               </dependencies>
             </project>
             """
          )
        );
    }
}
