import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** `agentic.android.application` — the :app module's base: AGP application + Kotlin + Compose. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure(ApplicationExtension::class.java) {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = 35
            }
        }
    }
}
