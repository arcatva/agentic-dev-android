import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** `agentic.android.library` — base for NON-UI core modules: AGP library + Kotlin (no Compose). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            extensions.configure(LibraryExtension::class.java) {
                configureAndroidCommon(this)
            }
        }
    }
}
