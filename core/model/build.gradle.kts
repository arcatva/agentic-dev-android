plugins {
    id("agentic.android.library")
}

android {
    namespace = "dev.agentic.core.model"
}

dependencies {
    // Runtime JSON tree APIs (JsonObject/JsonElement parsing) — no @Serializable derives here,
    // so the serialization compiler plugin is not needed.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
