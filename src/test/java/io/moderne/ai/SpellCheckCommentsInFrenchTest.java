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

import io.moderne.ai.SpellCheckCommentsInFrench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class SpellCheckCommentsInFrenchTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new SpellCheckCommentsInFrench());
    }

    @DocumentExample
    @Test
    void singleLineComment() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()),
          //language=java
          java(
            """
              class Test {
                  void test() {
                      // Description: Fabrique pour construire la r�ponse du service Compte?
                      // * - la valeur du champ "variable" doit ?tre remise ? la valeur par d?faut soit le transit courant
                  }
              }
              """,
            """
              class Test {
                  void test() {
                      // Description: Fabrique pour construire la réponse du service Compte?
                      // * - la valeur du champ "variable" doit être remise à la valeur par défaut soit le transit courant
                  }
              }
              """
          )
        );
    }

    @Test
    void trailingQuestion() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()),
          //language=java
          java(
            """
              class Test {
                  void test() {
                      // c'est la valeur qui cotise?
                  }
              }
              """
          )
        );
    }

    @Test
    void addAccent() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()),
          //language=java
          java(
            """
              class Test {
                  void test() {
                      // c'est une valeur simplifi?
                  }
              }
              """,
            """
             class Test {
                 void test() {
                     // c'est une valeur simplifié
                 }
             }
             """
          )
        );
    }

    @Test
    void uppercase() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()),
          //language=java
          java(
            """
              class Test {
                  void test() {
                      // G?rer la variable
                  }
              }
              """,
            """
             class Test {
                 void test() {
                     // Gérer la variable
                 }
             }
             """
          )
        );
    }
        @Test
        void questionMarkAlone() {
            rewriteRun(
              spec -> spec.parser(JavaParser.fromJavaVersion()),
              //language=java
              java(
                """
                  class Test {
                      void test() {
                          // C'est quoi ça ? C'est quoi?
                          // C'est quoi ça ?
                      }
                  }
                  """)
            );
    }

    @Test
    void javaDoc() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()),
          //language=java
          java(
            """
              class Test {
                  /**
                  * Voici comment faire un test facile simplifi?
                  */
                  void test() {
                  }
              }
              """,
            """
              class Test {
                  /**
                  * Voici comment faire un test facile simplifié
                  */
                  void test() {
                  }
              }
              """
            )
        );
    }

}
