package com.alara.hermes

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
import com.alara.hermes.protocol.GatewayEndpoint
import com.alara.hermes.protocol.HermesLiveGateway
import com.alara.hermes.protocol.wire.HermesCredential
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HermesApp).container
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
                val base = HermesRestClient.parseBaseUrl(url)
                if (base == null) {
                    error = "That doesn't look like a valid host or URL."
                    testing = false
                    return@launch
                }
                val probeScope = CoroutineScope(SupervisorJob())
                // Detect which Hermes surface this is. API server first
                // (GET /v1/capabilities with the Bearer token), then the
                // dashboard gateway (authenticated session list).
                val apiProbe = ApiServerGateway(base, token, probeScope)
                val apiResult = apiProbe.testConnection()
                if (apiResult.isSuccess) {
                    apiProbe.disconnect()
                    probeScope.cancel()
                    container.settings.saveConnection(url, token, GatewayMode.API_SERVER)
                    testing = false
                    return@launch
                }
                apiProbe.disconnect()
                val dashProbe = HermesLiveGateway(
                    endpoint = GatewayEndpoint(base, HermesCredential.Token(token)),
                    scope = probeScope,
                )
                val dashResult = dashProbe.testConnection()
                dashProbe.disconnect()
                probeScope.cancel()
                dashResult.onSuccess {
                    container.settings.saveConnection(url, token, GatewayMode.DASHBOARD)
                }.onFailure {
                    error = "Could not reach Hermes.\n" +
                        "API server probe: ${apiResult.exceptionOrNull()?.message}\n" +
                        "Dashboard probe: ${dashResult.exceptionOrNull()?.message}"
                }
                testing = false
            }
        },
    )
}

@Composable
private fun MainFlow(container: AppContainer) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))

    // Reconcile with the backend every time the app returns to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HomeScreen(viewModel)
}
