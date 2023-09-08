plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
}

group = "org.openrewrite.recipe"
description = "Rewrite AI search."

val rewriteBomVersion = rewriteRecipe.rewriteVersion.get()

dependencies {
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:${rewriteBomVersion}"))
    implementation("org.openrewrite:rewrite-core:latest.release")
    implementation("org.openrewrite:rewrite-java:latest.release")
    implementation("com.konghq:unirest-java:3.14.2")
    implementation("org.testng:testng:6.11")
    testRuntimeOnly("org.openrewrite:rewrite-java-17")

}
