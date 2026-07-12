package dev.agentic.di

/**
 * Neutral owner of the process-level [AppContainer]. The Application class implements this;
 * screens (`appContainer()`) and services resolve the container through THIS interface instead
 * of casting to the concrete app class — so `di/` has no dependency on `AgenticApp`, and a
 * future `:core:di` module doesn't create a `:feature:* -> :app` Gradle cycle (A-mod-DI).
 */
interface AppContainerOwner {
    val container: AppContainer
}
