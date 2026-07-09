import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // TODO: uncomment once google-services.json is placed in app/ (get it from Firebase Console,
    //       project package dev.agentic).  Also add the matching root-project plugin line — see
    //       the TODO block in AndroidManifest.xml for the full instructions.
    // id("com.google.gms.google-services")
}

// Release signing: CI injects env vars (KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS /
// KEY_PASSWORD — see .github/workflows/release.yml); locally they come from the gitignored
// keystore.properties. Neither present -> release stays unsigned (APK named *-unsigned).
// Blank env vars count as unset so CI without the signing secrets degrades cleanly.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile)) }
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)
// Enable signing only when ALL four inputs are present — a partial set (e.g. a renamed/rotated
// secret) must fall back to the unsigned build, not fail assembleRelease with null credentials.
val signingComplete = listOf(
    "KEYSTORE_FILE" to "storeFile",
    "KEYSTORE_PASSWORD" to "storePassword",
    "KEY_ALIAS" to "keyAlias",
    "KEY_PASSWORD" to "keyPassword",
).all { (env, prop) -> !signingValue(env, prop).isNullOrBlank() }
val signingStoreFile = if (signingComplete) rootProject.file(signingValue("KEYSTORE_FILE", "storeFile")!!) else null

android {
    namespace = "dev.agentic"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.agentic"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.4.0"
    }

    signingConfigs {
        if (signingStoreFile != null) create("release") {
            storeFile = signingStoreFile
            storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingValue("KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        // androidx.lifecycle 2.8.7 ships a `NullSafeMutableLiveData` lint check
        // (NonNullableMutableLiveDataDetector) that is binary-incompatible with the lint bundled in
        // AGP 8.7.2: its UAST handler hits IncompatibleClassChangeError ("Found class
        // org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall, but interface was
        // expected") and crashes the entire `lintVitalRelease` run, blocking release APK builds.
        // Disable ONLY that one detector — every other lint check still runs and release builds stay
        // lint-gated. Remove this once AGP/lifecycle lint versions line up again.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    // Explicit pins (no BOM). material3 1.4.0-alpha18 exposes the Expressive APIs publicly
    // (they were marked `internal` at the 1.4.0 beta/stable cut) AND depends on compose 1.8.x,
    // which still targets compileSdk 35 — so AGP 8.7.2 stays put. The 1.5.0-alpha line needs
    // compose 1.12 → compileSdk 37 → a newer toolchain.
    // Bumped 1.8.2 → 1.9.0 to get material3-adaptive 1.2.0 (native pane-expansion drag handle for
    // the resizable 3-pane home). Verified: 1.9.0 still builds on AGP 8.7.2 / compileSdk 35 /
    // Gradle 8.10.2 (checkDebugAarMetadata passes — no compileSdk 36 / AGP bump needed). material3
    // 1.4.0-alpha18 (Expressive APIs public) stays; it resolves its compose deps up to 1.9.0.
    val compose = "1.9.0"
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.ui:ui-tooling-preview:$compose")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner: a single app-foreground signal used to force a stream reconnect for every
    // live session on warm return (backstop to the per-screen ON_RESUME refresh).
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    // Material3 adaptive panes (single-tree list/detail/extra + native pane-expansion drag handle).
    val adaptive = "1.2.0"
    implementation("androidx.compose.material3.adaptive:adaptive:$adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:$adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:$adaptive")

    // networking: Ktor (REST + WebSocket) + kotlinx-serialization
    val ktor = "2.3.12"
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Firebase Cloud Messaging — push notifications for session finish events.
    // TODO: add google-services.json to app/ and uncomment the google-services plugin above
    //       before building. The two lines below make the code compile regardless.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    // kotlinx-coroutines-play-services provides .await() for Firebase Task<T> (pulled transitively
    // by firebase-messaging-ktx, but pinned here for clarity).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Unit testing (JVM) — pure domain transforms, repositories (fake Api), ViewModels.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
