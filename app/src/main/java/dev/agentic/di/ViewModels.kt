package dev.agentic.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.agentic.AgenticApp

/**
 * Composable helper: retrieves the process-level [AppContainer] from the [Application] context.
 * Screens use this together with `viewModel(factory = viewModelFactory { initializer { XViewModel(appContainer()...) } })`
 * (Phase 6). Nothing else lives here — ViewModelProvider.Factory is handled inline at call sites.
 */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as AgenticApp).container
