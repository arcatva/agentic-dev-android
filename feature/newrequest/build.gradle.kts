plugins {
    id("agentic.android.library.compose")
}

android {
    namespace = "dev.agentic.feature.newrequest"
}

dependencies {
    implementation(project(":core:di"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))

    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
