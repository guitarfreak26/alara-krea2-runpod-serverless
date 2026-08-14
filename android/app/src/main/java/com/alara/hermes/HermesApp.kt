package com.alara.hermes

import android.app.Application
import com.alara.hermes.data.DraftsRepository
import com.alara.hermes.data.SettingsRepository
import com.alara.hermes.protocol.GatewayEndpoint
import com.alara.hermes.protocol.HermesGateway
import com.alara.hermes.protocol.HermesLiveGateway
import com.alara.hermes.protocol.wire.HermesCredential
import com.alara.hermes.protocol.wire.HermesRestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI: one container for the whole app. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val drafts = DraftsRepository(app)

    @Volatile private var gateway: HermesGateway? = null
    @Volatile private var gatewayKey: String? = null

    /** Build (or reuse) the gateway for the given connection settings. */
    fun gatewayFor(url: String, token: String): HermesGateway? {
        val base = HermesRestClient.parseBaseUrl(url) ?: return null
        val key = "$url|${token.hashCode()}"
        gateway?.let { existing ->
            if (gatewayKey == key) return existing
            existing.disconnect()
        }
        val created = HermesLiveGateway(
            endpoint = GatewayEndpoint(base, HermesCredential.Token(token)),
            scope = appScope,
        )
        gateway = created
        gatewayKey = key
        return created
    }

    fun dropGateway() {
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
