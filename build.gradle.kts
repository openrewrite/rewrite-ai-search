plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
}

group = "org.openrewrite.recipe"
description = "Rewrite AI search."

val rewriteBomVersion = rewriteRecipe.rewriteVersion.get()

dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:$rewriteBomVersion"))
    implementation("org.openrewrite:rewrite-core")
    implementation("org.openrewrite:rewrite-java")
    implementation("com.konghq:unirest-java:3.14.2")
    implementation("org.openrewrite:rewrite-maven")
    testRuntimeOnly("org.openrewrite:rewrite-java-17")

    testImplementation("org.openrewrite:rewrite-test")
}
