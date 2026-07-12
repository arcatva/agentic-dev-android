package dev.agentic.di

import dev.agentic.AgenticApp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DI contract for the modularization path (A-mod-DI): screens and services obtain the
 * process-level [AppContainer] through the neutral [AppContainerOwner] interface, never by
 * casting to the concrete [AgenticApp]. This is what lets `di/` (future :core:di) stop
 * depending on the app class (breaking the would-be :feature:* -> :app Gradle cycle).
 */
class DiContractTest {
    @Test
    fun `AgenticApp implements AppContainerOwner`() {
        assertTrue(
            "AgenticApp must implement AppContainerOwner — appContainer() and the messaging " +
                "service resolve the container through the interface, not the concrete class",
            AppContainerOwner::class.java.isAssignableFrom(AgenticApp::class.java),
        )
    }
}
