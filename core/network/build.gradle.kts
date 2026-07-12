plugins {
    id("agentic.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.agentic.core.network"
}

dependencies {
    implementation(project(":core:model"))
    // Wire DTOs implement the domain ports (SessionSnapshot/WorkflowRunState/CommitLike):
    // the dependency arrow points INTO domain, never back.
    api(project(":core:domain"))
    implementation(project(":core:common"))

    implementation(libs.bundles.ktor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Models.kt marks wire types @Immutable for Compose skippability — annotation-only usage;
    // the Compose compiler itself is not applied to this module.
    implementation(libs.compose.runtime)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
