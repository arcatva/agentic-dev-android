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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
