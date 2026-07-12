package dev.agentic.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * Architecture conformance gate. These rules ARE the architecture — a violation fails CI, so the
 * module diagram cannot rot into a suggestion. Rules mirror the declared Gradle edges plus the
 * strict-clean constraints being migrated toward (each new rule lands together with the change
 * that makes it true).
 */
class ArchitectureTest {
    private val scope = Konsist.scopeFromProject()

    /** Feature isolation: only the four declared feature->feature edges may exist. */
    @Test
    fun `features only import declared feature edges`() {
        val allowed = mapOf(
            "workflow" to setOf("session"),
            "home" to setOf("session", "workflow"),
            "diagnostics" to setOf("home"),
            "globalsettings" to setOf("newrequest", "providers"),
        )
        val features = listOf(
            "adopt", "diagnostics", "globalsettings", "home", "login",
            "newrequest", "providers", "session", "tree", "workflow",
        )
        scope
            .files
            .filter { file -> file.path.contains("/feature/") && file.path.contains("/src/main/") }
            .assertFalse { file ->
                val self = features.firstOrNull { file.path.contains("/feature/$it/") } ?: return@assertFalse false
                file.imports.any { imp ->
                    features.any { other ->
                        other != self &&
                            imp.name.startsWith("dev.agentic.ui.$other.") &&
                            other !in (allowed[self] ?: emptySet())
                    }
                }
            }
    }

    /** Navigation is app-owned: no feature may reach back into dev.agentic.ui.nav. */
    @Test
    fun `no feature imports navigation`() {
        scope
            .files
            .filter { it.path.contains("/feature/") && it.path.contains("/src/main/") }
            .assertFalse { file -> file.imports.any { it.name.startsWith("dev.agentic.ui.nav") } }
    }

    /** DI stays behind the interface: nobody outside :app casts to the concrete Application. */
    @Test
    fun `container resolved via AppContainerOwner only`() {
        scope
            .files
            .filter { !it.path.contains("/app/") && it.path.contains("/src/main/") }
            .assertFalse { file -> file.text.contains("as AgenticApp") }
    }

    /** Core layering: core modules never import feature code. */
    @Test
    fun `core never imports features`() {
        val features = listOf(
            "adopt", "diagnostics", "globalsettings", "home", "login",
            "newrequest", "providers", "session", "tree", "workflow",
        )
        scope
            .files
            .filter { it.path.contains("/core/") && it.path.contains("/src/main/") }
            .assertFalse { file ->
                file.imports.any { imp -> features.any { imp.name.startsWith("dev.agentic.ui.$it.") } }
            }
    }
}
