import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** `agentic.android.library` — base for core and feature modules: AGP library + Kotlin + Compose. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure(LibraryExtension::class.java) {
                configureAndroidCommon(this)
            }
        }
    }
}
