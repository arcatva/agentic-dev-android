plugins {
    id("agentic.android.library")
}

android {
    namespace = "dev.agentic.core.voice"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
