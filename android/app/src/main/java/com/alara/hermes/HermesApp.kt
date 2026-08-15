package com.alara.hermes

import android.app.Application
import com.alara.hermes.data.DraftsRepository
import com.alara.hermes.data.GatewayMode
import com.alara.hermes.data.PendingRunsRepository
import com.alara.hermes.data.SettingsRepository
import com.alara.hermes.notify.NotificationCenter
import com.alara.hermes.protocol.ApiServerGateway
import com.alara.hermes.protocol.GatewayEndpoint
import com.alara.hermes.protocol.HermesGateway
import com.alara.hermes.protocol.HermesLiveGateway
import com.alara.hermes.protocol.wire.HermesCredential
import com.alara.hermes.protocol.wire.HermesRestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/** Manual DI: one container for the whole app. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val drafts = DraftsRepository(app)
    val pendingRuns = PendingRunsRepository(app)
    val archivedRegistry = com.alara.hermes.data.ArchivedRegistry(app)
    val notifications = NotificationCenter(app, settings, pendingRuns, appScope)

    /** Session key a notification tap asked us to open. */
    val pendingOpenSession = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    @Volatile private var gateway: HermesGateway? = null
    @Volatile private var gatewayKey: String? = null

    fun buildGateway(url: String, token: String, mode: GatewayMode): HermesGateway? {
        val base = HermesRestClient.parseBaseUrl(url) ?: return null
        return when (mode) {
            GatewayMode.API_SERVER -> ApiServerGateway(
                base,
                token,
                appScope,
                profilesProvider = { settings.profileNames.first() },
            )
            GatewayMode.DASHBOARD -> HermesLiveGateway(
                endpoint = GatewayEndpoint(base, HermesCredential.Token(token)),
                scope = appScope,
            )
        }
    }

    /** Build (or reuse) the gateway for the given connection settings. */
    fun gatewayFor(url: String, token: String, mode: GatewayMode): HermesGateway? {
        val key = "$url|${token.hashCode()}|$mode"
        gateway?.let { existing ->
            if (gatewayKey == key) return existing
            existing.disconnect()
        }
        val created = buildGateway(url, token, mode) ?: return null
        gateway = created
        gatewayKey = key
        notifications.attach(created)
        return created
    }

    fun dropGateway() {
        notifications.detach()
        gateway?.disconnect()
        gateway = null
        gatewayKey = null
    }
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
