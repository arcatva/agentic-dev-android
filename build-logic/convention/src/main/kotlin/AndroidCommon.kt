import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Android config shared by every module (application and libraries): SDK levels, Java/Kotlin 17.
 * Compose is NOT enabled here — pure data/domain modules must not pay the Compose compiler; UI
 * modules opt in via agentic.android.application / agentic.android.library.compose.
 */
internal fun Project.configureAndroidCommon(ext: CommonExtension<*, *, *, *, *, *>) {
    with(ext) {
        compileSdk = 35
        defaultConfig.minSdk = 26
        // lifecycle 2.8.7+'s NullSafeMutableLiveData lint check is binary-incompatible with the
        // lint bundled in AGP 8.7.x (IncompatibleClassChangeError) and crashes the whole lint
        // run. Disabled for EVERY module here — the app-level disable alone stopped covering
        // library modules once the split began. Remove when AGP/lifecycle lint align.
        lint.disable += "NullSafeMutableLiveData"
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    extensions.configure(KotlinAndroidProjectExtension::class.java) {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}
