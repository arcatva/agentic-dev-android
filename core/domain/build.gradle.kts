plugins {
    id("agentic.android.library")
}

android {
    namespace = "dev.agentic.core.domain"
}

dependencies {
    implementation(project(":core:common"))
    // Domain transforms over the wire model (Session, WorkflowRun, ...).
    api(project(":core:network"))
    // Status/StopReason helpers (hasError/isBenignCap) live in :core:data's dev.agentic.domain.
    implementation(project(":core:data"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
