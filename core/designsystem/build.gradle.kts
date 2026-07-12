plugins {
    id("agentic.android.library.compose")
}

android {
    namespace = "dev.agentic.core.designsystem"
}

dependencies {
    // Component signatures expose model (PendingAttachment/UploadState) and domain
    // (StatusVisual) types.
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(project(":core:common"))
    implementation(project(":core:voice"))

    implementation(libs.bundles.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
