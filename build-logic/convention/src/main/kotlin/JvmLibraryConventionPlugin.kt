import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * `agentic.jvm.library` — pure-Kotlin modules (:core:model, :core:domain). No Android plugin at
 * all: framework-independence enforced by the classpath, not by convention.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            extensions.configure(JavaPluginExtension::class.java) {
                toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            }
            extensions.configure(DetektExtension::class.java) {
                parallel = true
                buildUponDefaultConfig = true
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                baseline = file("detekt-baseline.xml")
            }
        }
    }
}
