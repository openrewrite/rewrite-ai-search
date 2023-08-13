plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

group = "org.openrewrite.recipe"
description = "Rewrite AI search."

val rewriteBomVersion = rewriteRecipe.rewriteVersion.get()

dependencies {
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:${rewriteBomVersion}"))
    implementation("org.openrewrite:rewrite-core")
    implementation("org.openrewrite:rewrite-java")

    implementation("io.micrometer:micrometer-core:latest.release")
    implementation("io.github.resilience4j:resilience4j-retry:latest.release")
    implementation("com.konghq:unirest-java:3.14.2")

    testRuntimeOnly("org.openrewrite:rewrite-java-17")
}
