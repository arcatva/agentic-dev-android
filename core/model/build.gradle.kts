plugins {
    id("agentic.jvm.library")
}

dependencies {
    // Runtime JSON tree APIs (transcripts are JSON documents — a domain reality, not a
    // transport concern). Pure-JVM artifact.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
