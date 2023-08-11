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

    @Test
    void okhttp() {
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion().classpath("okhttp")),
          //language=java
          java(
            """
              import okhttp3.*;
              class Test {
                   OkHttpClient client = new OkHttpClient();
                   
                   String post(String url, String json) {
                     Request request = /*~~>*/new Request.Builder()
                         .url(url)
                         .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8")))
                         .build();
                     try (Response response = client.newCall(request).execute()) {
                       return response.body().string();
                     }
                   }
              }
              """,
            """
              import okhttp.*;
              class Test {
                   OkHttpClient client = new OkHttpClient();
                   
                   String post(String url, String json) {
                     Request request = new Request.Builder()
                         .url(url)
                         .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8")))
                         .build();
                     try (Response response = client.newCall(request).execute()) {
                       return response.body().string();
                     }
                   }
              }
              """
          )
        );
    }
}
