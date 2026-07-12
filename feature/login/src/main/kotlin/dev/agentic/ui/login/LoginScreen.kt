package dev.agentic.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.formatFingerprint
import dev.agentic.di.appContainer
import dev.agentic.ui.AppMotion

/**
 * Login shell. Builds the [LoginViewModel] (with the LAN scanner + last-used host) and routes
 * between the Chooser, Scan, and Manual sub-screens held in [LoginUiState.step]. Navigation out
 * still fires via LaunchedEffect(done) so the host never observes VM state.
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    vm: LoginViewModel? = null,
) {
    val container = appContainer()
    val resolvedVm: LoginViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { LoginViewModel(container.authRepo, container.lanScanner, container.settings.host) }
        },
    )
    val s by resolvedVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(s.done) { if (s.done) onLoggedIn() }

    // Sub-screens hand back to the chooser on system-back.
    BackHandler(enabled = s.step != LoginStep.Chooser) { resolvedVm.back() }

    AnimatedContent(
        targetState = s.step,
        transitionSpec = {
            // Derive direction from targetState inside the spec (not a captured outer flag, which
            // can be a frame stale): leaving the Chooser slides forward (+), returning to it slides back (-).
            val dir = if (targetState != LoginStep.Chooser) 1 else -1
            (slideInHorizontally(tween(AppMotion.DurationNav, easing = AppMotion.Emphasized)) { dir * it })
                .togetherWith(slideOutHorizontally(tween(AppMotion.DurationNav, easing = AppMotion.Emphasized)) { -dir * it })
        },
        label = "login-step",
    ) { step ->
        when (step) {
            LoginStep.Chooser -> LoginChooser(
                state = s,
                onScan = { resolvedVm.goTo(LoginStep.Scan) },
                onManual = { resolvedVm.goTo(LoginStep.Manual) },
            )
            LoginStep.Scan -> LoginScanScreen(
                state = s,
                onSelect = resolvedVm::onSelectServer,
                onPassword = resolvedVm::onPassword,
                onTogglePassword = resolvedVm::togglePasswordVisible,
                onRescan = resolvedVm::rescan,
                onSubmit = resolvedVm::submit,
                onManual = { resolvedVm.goTo(LoginStep.Manual) },
                onBack = resolvedVm::back,
            )
            LoginStep.Manual -> LoginManualScreen(
                state = s,
                onHost = resolvedVm::onHost,
                onPassword = resolvedVm::onPassword,
                onTogglePassword = resolvedVm::togglePasswordVisible,
                onSubmit = resolvedVm::submit,
                onBack = resolvedVm::back,
            )
        }
    }

    // Trust-on-first-use prompt: shown over any sub-screen when the server presents a self-signed
    // cert we haven't pinned yet. Verify the fingerprint against the server's boot log, then trust.
    s.certPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = resolvedVm::dismissCertPrompt,
            title = { Text("Trust this server?") },
            text = {
                Text(
                    "${prompt.host} uses a self-signed certificate. Check that this SHA-256 " +
                        "fingerprint matches the one in the server's startup log, then trust it:\n\n" +
                        formatFingerprint(prompt.fingerprint),
                )
            },
            confirmButton = { TextButton(onClick = resolvedVm::trustCertAndRetry) { Text("Trust & continue") } },
            dismissButton = { TextButton(onClick = resolvedVm::dismissCertPrompt) { Text("Cancel") } },
        )
    }
}
