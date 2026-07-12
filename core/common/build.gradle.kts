plugins {
    id("agentic.android.library")
}

android {
    namespace = "dev.agentic.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
