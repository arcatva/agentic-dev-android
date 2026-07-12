import java.io.FileInputStream
import java.util.Properties

plugins {
    id("agentic.android.application")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
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
    defaultConfig {
        applicationId = "dev.agentic"
        versionCode = 14
        versionName = "0.4.2"
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
    // Compose —— 版本经由 catalog 统一管理。material3 1.4.0-alpha18 公开 Expressive API 且
    // 依赖 compose 1.8.x（仍以 compileSdk 35 为目标）；compose 1.9.5 verified on AGP 8.7.3 (checkDebugAarMetadata + lintVitalRelease pass)
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    // lifecycle 2.8.7：runtime-compose + viewmodel-compose + process（ProcessLifecycleOwner，
    // 用于热返回时强制每个活跃会话重连）。lint 禁用见下方 android{} lint 块。
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.navigation.compose)

    // Material3 adaptive panes（单树 list/detail/extra + 原生 pane 展开拖拽手柄）。
    implementation(libs.bundles.compose.adaptive)

    // networking: Ktor(REST + WebSocket) + kotlinx-serialization
    implementation(libs.bundles.ktor)
    implementation(libs.kotlinx.serialization.json)

    // Firebase Cloud Messaging —— 会话完成推送。
    // TODO: 构建前把 google-services.json 放进 app/ 并启用上方 google-services 插件。
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // kotlinx-coroutines-play-services 提供 Firebase Task<T> 的 .await()
    implementation(libs.kotlinx.coroutines.play.services)

    // 单元测试（JVM）——纯领域变换、仓库（fake Api）、ViewModel。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 用脚本化响应驱动 ResumableDownloader（中途断流、206 续传）。
    testImplementation(libs.ktor.client.mock)
}

// Static analysis (A2). Existing findings live in detekt-baseline.xml — CI fails only on NEW
// findings. Shrink the baseline over time; don't add to it.
detekt {
    parallel = true
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}
