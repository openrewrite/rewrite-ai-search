package io.moderne.rewrite.ai.search;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

public class FindHttpRequestsWithContentTypeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindHttpRequestsWithContentType("application/json",
          "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"));
    }

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
                                .body("1")
                                .asString();
                  }
              }
              """,
            """
              import kong.unirest.*;
              class Test {
                  void test() {
                        /*~~>*/Unirest.post("https://httpbin.org/post")
                                .header("Content-Type", "application/json")
                                .body("1")
                                .asString();
                  }
              }
              """
          )
        );
    }
}
