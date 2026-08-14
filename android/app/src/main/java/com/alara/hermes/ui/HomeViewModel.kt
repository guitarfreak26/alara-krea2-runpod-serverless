package com.alara.hermes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alara.hermes.AppContainer
import com.alara.hermes.protocol.ConnectionState
import com.alara.hermes.protocol.HermesGateway
import com.alara.hermes.protocol.HermesProfile
import com.alara.hermes.protocol.ModelOption
import com.alara.hermes.protocol.SessionConfig
import com.alara.hermes.protocol.SessionHandle
import com.alara.hermes.protocol.SessionSummary
import com.alara.hermes.protocol.TimelineState
import com.alara.hermes.protocol.redact
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionKey: String? = null,
    val title: String = "",
    val timeline: TimelineState? = null,
    val config: SessionConfig = SessionConfig(),
    val sending: Boolean = false,
    val error: String? = null,
)

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val features: com.alara.hermes.protocol.GatewayFeatures = com.alara.hermes.protocol.GatewayFeatures.DASHBOARD,
    val profiles: List<HermesProfile> = emptyList(),
    val activeProfile: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = true,
    val searchQuery: String = "",
    val chat: ChatUiState = ChatUiState(),
    val models: List<ModelOption> = emptyList(),
    val notice: String? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    val appearance = container.settings.appearance
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Bearer context for loading private media from the gateway host only. */
    val mediaAuth = container.settings.serverSettings
        .map { server ->
            val host = com.alara.hermes.protocol.wire.HermesRestClient.parseBaseUrl(server.url)?.host
            if (host != null && server.token.isNotBlank()) {
                com.alara.hermes.ui.chat.MediaAuth(host, "Bearer ${server.token.trim()}")
            } else {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var gateway: HermesGateway? = null
    private var secretToken: String = ""
    private var handle: SessionHandle? = null
    private var handleJobs = mutableListOf<Job>()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val server = container.settings.currentServer()
        if (!server.isConfigured) return
        secretToken = server.token
        val gw = container.gatewayFor(server.url, server.token, server.mode) ?: return
        gateway = gw
        _state.update { it.copy(features = gw.features) }
        viewModelScope.launch { gw.connection.collect { c -> _state.update { it.copy(connection = c) } } }
        viewModelScope.launch { gw.sessionsChanged.collect { refreshSessions(silent = true) } }
        gw.connect()
        loadProfiles(server.activeProfile)
        refreshSessions()
        startListRefreshLoop()
    }

    /** Session list stays fresh even when another client is driving turns. */
    private fun startListRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                refreshSessions(silent = true)
            }
        }
    }

    private suspend fun loadProfiles(preferred: String?) {
        val gw = gateway ?: return
        val profiles = runCatching { gw.listProfiles() }.getOrDefault(emptyList())
        val active = preferred?.takeIf { p -> profiles.any { it.id == p } }
            ?: profiles.firstOrNull { it.isDefault }?.id
            ?: profiles.firstOrNull()?.id
        _state.update { it.copy(profiles = profiles, activeProfile = active) }
    }

    fun switchProfile(profileId: String) {
        if (profileId == _state.value.activeProfile) return
        // Profile isolation: switching closes the open chat; sessions reload scoped.
        closeChat()
        _state.update { it.copy(activeProfile = profileId, sessions = emptyList(), sessionsLoading = true) }
        viewModelScope.launch {
            container.settings.setActiveProfile(profileId)
            refreshSessions()
        }
    }

    fun refreshSessions(silent: Boolean = false) {
        val gw = gateway ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(sessionsLoading = true) }
            val profile = _state.value.activeProfile
            val query = _state.value.searchQuery
            val result = runCatching {
                if (query.isBlank()) gw.listSessions(profile) else gw.searchSessions(query, profile)
            }
            result.onSuccess { sessions ->
                val sorted = sessions.sortedWith(
                    compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updatedAtMs },
                )
                _state.update { it.copy(sessions = sorted, sessionsLoading = false) }
            }.onFailure {
                _state.update { it.copy(sessionsLoading = false) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        refreshSessions(silent = true)
    }

    // ---- chat --------------------------------------------------------------

    fun openSession(sessionKey: String?) {
        val gw = gateway ?: return
        val profile = _state.value.activeProfile
        closeChat()
        _state.update {
            it.copy(
                chat = ChatUiState(
                    sessionKey = sessionKey,
                    title = it.sessions.firstOrNull { s -> s.key == sessionKey }?.title ?: "New conversation",
                ),
            )
        }
        viewModelScope.launch {
            val opened = runCatching { gw.openSession(sessionKey, profile) }
            opened.onSuccess { h ->
                handle = h
                _state.update { it.copy(chat = it.chat.copy(sessionKey = h.sessionKey)) }
                handleJobs += viewModelScope.launch {
                    h.timeline.collect { t -> _state.update { it.copy(chat = it.chat.copy(timeline = t)) } }
                }
                handleJobs += viewModelScope.launch {
                    h.config.collect { c -> _state.update { it.copy(chat = it.chat.copy(config = c)) } }
                }
                handleJobs += viewModelScope.launch {
                    val models = runCatching { gw.listModels(h.sessionKey) }.getOrDefault(emptyList())
                    _state.update { it.copy(models = models) }
                }
            }.onFailure { t ->
                _state.update { it.copy(chat = it.chat.copy(error = clean(t.message))) }
            }
        }
    }

    fun closeChat() {
        handleJobs.forEach { it.cancel() }
        handleJobs.clear()
        handle?.close()
        handle = null
        _state.update { it.copy(chat = ChatUiState(), models = emptyList()) }
    }

    fun send(text: String, attachments: List<com.alara.hermes.protocol.OutgoingAttachment> = emptyList()) {
        val h = handle ?: return
        if (text.isBlank() && attachments.isEmpty()) return
        _state.update { it.copy(chat = it.chat.copy(sending = true, error = null)) }
        viewModelScope.launch {
            runCatching { h.send(text, attachments) }
                .onFailure { t ->
                    _state.update {
                        it.copy(chat = it.chat.copy(error = clean("Send failed: ${t.message}. Not re-sent automatically.")))
                    }
                }
            _state.update { it.copy(chat = it.chat.copy(sending = false)) }
            container.drafts.save(h.sessionKey, "")
            refreshSessions(silent = true)
        }
    }

    fun interrupt() {
        val h = handle ?: return
        viewModelScope.launch { runCatching { h.interrupt() } }
    }

    fun respondApproval(entryId: com.alara.hermes.protocol.EntryId, choice: String) {
        val h = handle ?: return
        viewModelScope.launch {
            runCatching { h.respondApproval(entryId, choice) }
                .onFailure { t ->
                    _state.update { it.copy(chat = it.chat.copy(error = clean("Approval failed: ${t.message}"))) }
                }
        }
    }

    fun setModel(option: ModelOption) {
        val h = handle ?: return
        viewModelScope.launch {
            runCatching { h.setModel(option.id, option.provider) }
                .onFailure { t -> _state.update { it.copy(notice = clean("Model change failed: ${t.message}")) } }
        }
    }

    fun setReasoning(level: String) {
        val h = handle ?: return
        viewModelScope.launch {
            runCatching { h.setReasoning(level) }
                .onFailure { t -> _state.update { it.copy(notice = clean("Reasoning change failed: ${t.message}")) } }
        }
    }

    fun setFastMode(enabled: Boolean) {
        val h = handle ?: return
        viewModelScope.launch {
            runCatching { h.setFastMode(enabled) }
                .onFailure { t -> _state.update { it.copy(notice = clean("Fast mode change failed: ${t.message}")) } }
        }
    }

    fun renameSession(sessionKey: String, title: String) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.renameSession(sessionKey, title) }
            refreshSessions(silent = true)
            if (_state.value.chat.sessionKey == sessionKey) {
                _state.update { it.copy(chat = it.chat.copy(title = title)) }
            }
        }
    }

    fun deleteSession(sessionKey: String) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.deleteSession(sessionKey) }
            if (_state.value.chat.sessionKey == sessionKey) closeChat()
            refreshSessions(silent = true)
        }
    }

    /** Exception text can echo URLs/headers; never surface credentials. */
    private fun clean(message: String?): String = redact(message ?: "unknown error", secretToken)

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** Called on app foreground: reconcile with the authoritative backend. */
    fun onForeground() {
        viewModelScope.launch {
            gateway?.connect()
            handle?.let { runCatching { it.refresh() } }
            refreshSessions(silent = true)
        }
    }

    suspend fun draftFor(sessionKey: String): String =
        container.drafts.draft(sessionKey).first()

    fun saveDraft(sessionKey: String, text: String) {
        viewModelScope.launch { container.drafts.save(sessionKey, text) }
    }

    override fun onCleared() {
        closeChat()
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(container) as T
        }
    }
}
