plugins {
    id("agentic.android.library.compose")
}

android {
    namespace = "dev.agentic.core.di"
}

dependencies {
    // AppContainer wires the whole data layer; its public fields expose repo/store/api types.
    api(project(":core:data"))
    implementation(project(":core:common"))

    // appContainer() is a @Composable reading LocalContext.
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
}
