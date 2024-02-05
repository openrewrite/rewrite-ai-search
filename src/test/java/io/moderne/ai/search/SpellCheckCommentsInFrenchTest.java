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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class SpellCheckCommentsInFrenchTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new SpellCheckCommentsInFrench());
    }

    @DocumentExample
    @Test
    void unirest() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().classpath("unirest-java")),
          //language=java
          java(
            """
              import kong.unirest.*;
              class Test {
                  void test() {
                        Unirest.post("https://httpbin.org/post")
                                .header("Content-Type", "application/json")
                                .body("1") // Description: Fabrique pour construire la r�ponse du service SrvObtenirListeCompte?
                                .asString(); // * - la valeur du champ "Transit" doit ?tre remise ? la valeur par d?faut soit le transit courant
                  }
              }
              """,
            """
              import kong.unirest.*;
              class Test {
                  void test() {
                        Unirest.post("https://httpbin.org/post")
                                .header("Content-Type", "application/json")
                                .body("1") // Description: Fabrique pour construire la réponse du service SrvObtenirListeCompte?
                                .asString(); // * - la valeur du champ "Transit" doit être remise à la valeur par défaut soit le transit courant
                  }
              }
              """
          )
        );
    }

}

