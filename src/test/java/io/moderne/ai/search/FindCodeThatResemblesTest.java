package io.moderne.ai.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static org.openrewrite.java.Assertions.java;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class FindCodeThatResemblesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindCodeThatResembles(
          "HTTP request with Content-Type application/json",
          List.of("kong.unirest.* *(..)", "okhttp*..* *(..)", "org.springframework.web.reactive.function.client.WebClient *(..)",
            "org.apache.hc..* *(..)", "org.apache.http.client..* *(..)"),
          "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"
        ));
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

    @Test
    void unirest2() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().classpath("unirest-java")),
          //language=java
          java(
            """
              import kong.unirest.*;
              class Test {
                  void test() {
                    HttpRequestWithBody request = Unirest.post("https://httpbin.org/post")
                            .header("Content-Type", "application/json");
                    request
                            .body("1")
                            .asString();
                  }
              }
              """,
            """
              import kong.unirest.*;
              class Test {
                  void test() {
                    HttpRequestWithBody request = /*~~>*/Unirest.post("https://httpbin.org/post")
                            .header("Content-Type", "application/json");
                    request
                            .body("1")
                            .asString();
                  }
              }
              """
          )
        );
    }
}
