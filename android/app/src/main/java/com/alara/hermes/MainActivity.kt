package com.alara.hermes

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alara.hermes.data.GatewayMode
import com.alara.hermes.protocol.ApiServerGateway
import com.alara.hermes.protocol.redact
import com.alara.hermes.protocol.wire.HermesRestClient
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.home.HomeScreen
import com.alara.hermes.ui.onboarding.OnboardingScreen
import com.alara.hermes.ui.theme.HermesTheme
import com.alara.hermes.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeOpenSessionIntent(intent)
    }

    private fun consumeOpenSessionIntent(intent: Intent?) {
        intent?.getStringExtra(com.alara.hermes.notify.NotificationCenter.EXTRA_OPEN_SESSION)
            ?.let { key ->
                (application as HermesApp).container.pendingOpenSession.value = key
                intent.removeExtra(com.alara.hermes.notify.NotificationCenter.EXTRA_OPEN_SESSION)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HermesApp).container
        consumeOpenSessionIntent(intent)
        setContent {
            val onboarded by container.settings.onboarded.collectAsState(initial = null)
            val appearance by container.settings.appearance.collectAsState(initial = null)
            HermesTheme(mode = appearance?.themeMode ?: ThemeMode.AMOLED) {
                when (onboarded) {
                    null -> Unit // settle DataStore before choosing a route
                    false -> OnboardingFlow(container)
                    true -> MainFlow(container)
                }
            }
        }
    }
}

@Composable
private fun OnboardingFlow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    OnboardingScreen(
        testing = testing,
        error = error,
        onConnect = { url, token ->
            testing = true
            error = null
            scope.launch {
                // Root server URL, trimmed token; validation is exactly one
                // authenticated call: GET /v1/capabilities with
                // `Authorization: Bearer`. No /api/status, no /api/sessions,
                // no cookies, no dashboard-password flow.
                val base = HermesRestClient.parseBaseUrl(url)
                if (base == null) {
                    error = "That doesn't look like a valid host or URL."
                    testing = false
                    return@launch
                }
                val probeScope = CoroutineScope(SupervisorJob())
                val probe = ApiServerGateway(base, token, probeScope)
                val result = probe.testConnection()
                probe.disconnect()
                probeScope.cancel()
                result.onSuccess {
                    container.settings.saveConnection(url, token, GatewayMode.API_SERVER)
                }.onFailure { t ->
                    error = redact(
                        "Could not validate against GET /v1/capabilities: ${t.message}",
                        token,
                    )
                }
                testing = false
            }
        },
    )
}

@Composable
private fun MainFlow(container: AppContainer) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    var overlay by remember { mutableStateOf<String?>(null) }

    // Android 13+ requires runtime opt-in before any notification shows.
    if (Build.VERSION.SDK_INT >= 33) {
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Reconcile with the backend every time the app returns to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (overlay) {
        "settings" -> {
            val connection by viewModel.state.collectAsState()
            com.alara.hermes.ui.settings.SettingsScreen(
                container = container,
                connection = connection.connection,
                onBack = {
                    overlay = null
                    // Profile names may have been edited; refresh the switcher.
                    viewModel.reloadProfiles()
                },
            )
        }
        "skills" -> com.alara.hermes.ui.manage.SkillsScreen(viewModel, onBack = { overlay = null })
        "automations" -> com.alara.hermes.ui.manage.AutomationsScreen(viewModel, onBack = { overlay = null })
        "usage" -> com.alara.hermes.ui.manage.UsageScreen(viewModel, onBack = { overlay = null })
        else -> HomeScreen(
            viewModel,
            onOpenOverlay = { overlay = it },
            openSessionRequests = container.pendingOpenSession,
        )
    }
}
