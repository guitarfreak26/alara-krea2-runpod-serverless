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
    /** True when an existing conversation's history failed to load; sends are blocked. */
    val loadFailed: Boolean = false,
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
    /** True while the list shows archived conversations instead of active ones. */
    val showArchived: Boolean = false,
    val chatSettings: com.alara.hermes.data.ChatSettings =
        com.alara.hermes.data.ChatSettings(activeFirst = false, showToolActivity = true),
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
        viewModelScope.launch {
            container.settings.chatSettings.collect { prefs ->
                _state.update { it.copy(chatSettings = prefs, sessions = sortSessions(it.sessions, prefs)) }
            }
        }
    }

    private fun sortSessions(
        sessions: List<SessionSummary>,
        prefs: com.alara.hermes.data.ChatSettings = _state.value.chatSettings,
    ): List<SessionSummary> = sessions.sortedWith(
        compareByDescending<SessionSummary> { prefs.activeFirst && it.running }
            .thenByDescending { it.pinned }
            .thenByDescending { it.updatedAtMs },
    )

    /**
     * The list view honors the archived toggle. When the server supports
     * archived listing, `archived=only` is the durable archived list — it
     * includes sessions archived from desktop. Only older builds fall back to
     * merging the local registry of sessions archived from this device.
     */
    fun visibleSessions(state: HomeUiState = _state.value): List<SessionSummary> {
        if (!state.showArchived) return state.sessions.filter { !it.archived }
        if (state.features.archivedListing) return state.sessions.filter { it.archived }
        val serverArchived = state.sessions.filter { it.archived }
        val serverKeys = state.sessions.map { it.key }.toSet()
        val registryOnly = archivedEntries.value
            .filter { it.sessionKey !in serverKeys }
            .map { entry ->
                SessionSummary(
                    key = entry.sessionKey,
                    profileId = state.activeProfile ?: "default",
                    title = entry.title,
                    preview = "Archived from this device",
                    updatedAtMs = entry.archivedAtMs,
                    archived = true,
                )
            }
        return (serverArchived + registryOnly).sortedByDescending { it.updatedAtMs }
    }

    private val archivedEntries = container.archivedRegistry.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleArchivedView() {
        _state.update { it.copy(showArchived = !it.showArchived) }
        // The archived view is its own server query (`archived=only`), so the
        // list must be re-fetched on every toggle when the server supports it.
        if (_state.value.features.archivedListing) refreshSessions()
    }

    fun setPinned(sessionKey: String, pinned: Boolean) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.setPinned(sessionKey, pinned) }
                .onFailure { t ->
                    _state.update { it.copy(notice = clean(t.message), features = gw.features) }
                }
            refreshSessions(silent = true)
        }
    }

    fun setArchived(sessionKey: String, archived: Boolean) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.setArchived(sessionKey, archived) }
                .onSuccess {
                    val title = _state.value.sessions.firstOrNull { it.key == sessionKey }?.title
                        ?: archivedEntries.value.firstOrNull { it.sessionKey == sessionKey }?.title
                        ?: "Conversation"
                    if (archived) {
                        container.archivedRegistry.add(sessionKey, title)
                    } else {
                        container.archivedRegistry.remove(sessionKey)
                    }
                }
                .onFailure { t ->
                    _state.update { it.copy(notice = clean(t.message), features = gw.features) }
                }
            refreshSessions(silent = true)
        }
    }

    private suspend fun bootstrap() {
        val server = container.settings.currentServer()
        if (!server.isConfigured) return
        secretToken = server.token
        val gw = container.gatewayFor(server.url, server.token, server.mode) ?: return
        gateway = gw
        _state.update { it.copy(features = gw.features) }
        viewModelScope.launch {
            gw.connection.collect { c ->
                // features is computed from server capabilities, which land with
                // the first successful connect — re-read it on every transition
                // so pin/archive/rename gates reflect the real server.
                _state.update { it.copy(connection = c, features = gw.features) }
            }
        }
        viewModelScope.launch { gw.sessionsChanged.collect { refreshSessions(silent = true) } }
        gw.connect()
        loadProfiles(server.activeProfile)
        refreshSessions()
        startListRefreshLoop()
        // Server-side session is the source of truth: after relaunch/process
        // death, resume the same canonical session and reload its messages
        // from Hermes rather than starting anything new.
        container.settings.lastOpenSession.first()?.let { last ->
            if (_state.value.chat.sessionKey == null) openSession(last)
        }
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
                when {
                    query.isNotBlank() -> gw.searchSessions(query, profile)
                    _state.value.showArchived && gw.features.archivedListing ->
                        gw.listArchivedSessions(profile)
                    else -> gw.listSessions(profile)
                }
            }
            result.onSuccess { sessions ->
                _state.update { it.copy(sessions = sortSessions(sessions), sessionsLoading = false) }
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
                container.settings.setLastOpenSession(h.sessionKey)
                if (sessionKey != null) {
                    // Existing conversation: history must load before any send.
                    // A failure blocks the composer with a retry instead of
                    // silently running the agent without context.
                    runCatching { h.refresh() }.onFailure { t ->
                        _state.update {
                            it.copy(
                                chat = it.chat.copy(
                                    loadFailed = true,
                                    error = clean("Couldn't load this conversation from the server: ${t.message}"),
                                ),
                            )
                        }
                    }
                }
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

    /** Retry loading a conversation whose history fetch failed. */
    fun retryLoad() {
        val h = handle ?: return
        viewModelScope.launch {
            runCatching { h.refresh() }
                .onSuccess {
                    _state.update { it.copy(chat = it.chat.copy(loadFailed = false, error = null)) }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(chat = it.chat.copy(error = clean("Still can't load: ${t.message}")))
                    }
                }
        }
    }

    fun send(text: String, attachments: List<com.alara.hermes.protocol.OutgoingAttachment> = emptyList()) {
        val h = handle ?: return
        if (_state.value.chat.loadFailed) return
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
            container.settings.setLastOpenSession(null)
            if (_state.value.chat.sessionKey == sessionKey) closeChat()
            refreshSessions(silent = true)
        }
    }

    /** Exception text can echo URLs/headers; never surface credentials. */
    private fun clean(message: String?): String = redact(message ?: "unknown error", secretToken)

    suspend fun skills(): Result<List<com.alara.hermes.protocol.SkillInfo>> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.listSkills() }
    }

    suspend fun automations(): Result<List<com.alara.hermes.protocol.AutomationInfo>> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.listAutomations() }
    }

    suspend fun setAutomationPaused(id: String, paused: Boolean): Result<Unit> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.setAutomationPaused(id, paused) }
    }

    suspend fun runAutomation(id: String): Result<Unit> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.runAutomation(id) }
    }

    suspend fun deleteAutomation(id: String): Result<Unit> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.deleteAutomation(id) }
    }

    suspend fun accountUsage(): Result<List<com.alara.hermes.protocol.AccountUsage>> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.accountUsage() }
    }

    suspend fun usageSummary(): Result<com.alara.hermes.protocol.UsageSummary> {
        val gw = gateway ?: return Result.failure(IllegalStateException("not connected"))
        return runCatching { gw.usageSummary(_state.value.activeProfile) }
    }

    fun cleanError(message: String?): String = clean(message)

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
