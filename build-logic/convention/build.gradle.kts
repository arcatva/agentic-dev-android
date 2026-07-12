plugins {
    `kotlin-dsl`
}

group = "dev.agentic.buildlogic"

dependencies {
    // Compile against the same AGP/KGP the app uses (versions from the shared catalog).
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "agentic.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "agentic.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidComposeLibrary") {
            id = "agentic.android.library.compose"
            implementationClass = "AndroidComposeLibraryConventionPlugin"
        }
    }
}
