import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** `agentic.android.library.compose` — UI library modules: agentic.android.library + Compose. */
class AndroidComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("agentic.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure(LibraryExtension::class.java) {
                buildFeatures.compose = true
            }
        }
    }
}
