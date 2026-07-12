plugins {
    id("agentic.android.library.compose")
}

android {
    namespace = "dev.agentic.feature.globalsettings"
}

dependencies {
    implementation(project(":core:di"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":feature:newrequest"))
    implementation(project(":feature:providers"))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
