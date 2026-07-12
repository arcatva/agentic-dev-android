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
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    extensions.configure(KotlinAndroidProjectExtension::class.java) {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}
