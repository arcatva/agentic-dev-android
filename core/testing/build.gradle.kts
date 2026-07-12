plugins {
    id("agentic.android.library")
}

android {
    namespace = "dev.agentic.core.testing"
}

dependencies {
    // Shared fakes implement the network/data interfaces; consumed via testImplementation by
    // :app, :core:network and :core:data (test->main edges, no configuration cycle).
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
