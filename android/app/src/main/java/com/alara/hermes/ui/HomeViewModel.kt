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
    /** Non-null while the open conversation is a server-backed Bot Mode room. */
    val room: com.alara.hermes.protocol.BotRoom? = null,
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
    /** Source groups (cron, matrix, discord, …) the user filtered out of the list. */
    val hiddenSources: Set<String> = emptySet(),
    /** Server-defined Bot Mode rooms; empty when unsupported or none exist. */
    val rooms: List<com.alara.hermes.protocol.BotRoom> = emptyList(),
    /** Bots pinned to the roster top (under rooms); local preference. */
    val pinnedBots: Set<String> = emptySet(),
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
        viewModelScope.launch {
            container.settings.hiddenSources.collect { hidden ->
                _state.update { it.copy(hiddenSources = hidden) }
            }
        }
        viewModelScope.launch {
            container.settings.pinnedBots.collect { pinned ->
                _state.update { it.copy(pinnedBots = pinned) }
            }
        }
    }

    fun togglePinnedBot(profileId: String) {
        viewModelScope.launch { container.settings.togglePinnedBot(profileId) }
    }

    /**
     * Source groups offered as filter chips: everything present in the current
     * list plus anything already hidden (so a fully filtered-out group can be
     * turned back on).
     */
    fun sourceFilterOptions(state: HomeUiState = _state.value): List<String> =
        (state.sessions.mapNotNull { it.source?.trim()?.lowercase()?.takeIf(String::isNotEmpty) } +
            state.hiddenSources).distinct().sorted()

    fun toggleSourceFilter(source: String) {
        viewModelScope.launch { container.settings.toggleHiddenSource(source) }
    }

    private fun sourceVisible(session: SessionSummary, hidden: Set<String>): Boolean {
        val source = session.source?.trim()?.lowercase() ?: return true
        return source !in hidden
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
        val hidden = state.hiddenSources
        if (!state.showArchived) {
            return state.sessions.filter { !it.archived && sourceVisible(it, hidden) }
        }
        if (state.features.archivedListing) {
            return state.sessions.filter { it.archived && sourceVisible(it, hidden) }
        }
        val serverArchived = state.sessions.filter { it.archived && sourceVisible(it, hidden) }
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
            var wasConnected = false
            gw.connection.collect { c ->
                // features is computed from server capabilities, which land with
                // the first successful connect — re-read it on every transition
                // so pin/archive/rename gates reflect the real server.
                _state.update { it.copy(connection = c, features = gw.features) }
                // Rooms/roster gates flip ON with the capability probe: reload
                // them when a connection lands, or a cold start shows an empty
                // Rooms list until the pane happens to reopen.
                val connected = c is ConnectionState.Connected
                if (connected && !wasConnected) {
                    loadRooms()
                    loadProfiles(_state.value.activeProfile)
                }
                wasConnected = connected
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
                // Keep per-bot busy/last-active fresh so the chat header's
                // working indicator tracks server-side work, not just turns
                // this app started.
                runCatching { loadProfiles(_state.value.activeProfile) }
                loadRooms()
            }
        }
    }

    private suspend fun loadProfiles(preferred: String?) {
        val gw = gateway ?: return
        val profiles = runCatching { gw.listProfiles() }.getOrDefault(emptyList())
        val active = preferred?.takeIf { p -> profiles.any { it.id == p } }
            ?: profiles.firstOrNull { it.isDefault }?.id
            ?: profiles.firstOrNull()?.id
        gw.setActiveProfile(active)
        _state.update { it.copy(profiles = profiles, activeProfile = active, features = gw.features) }
    }

    fun switchProfile(profileId: String) {
        if (profileId == _state.value.activeProfile) return
        // Profile isolation: switching closes the open chat; sessions reload scoped.
        closeChat()
        gateway?.setActiveProfile(profileId)
        _state.update { it.copy(activeProfile = profileId, sessions = emptyList(), sessionsLoading = true) }
        viewModelScope.launch {
            container.settings.setActiveProfile(profileId)
            refreshSessions()
        }
    }

    /** Re-read the configured profile names (Settings can change them at runtime). */
    fun reloadProfiles() {
        viewModelScope.launch { loadProfiles(_state.value.activeProfile) }
    }

    fun showNotice(message: String) {
        _state.update { it.copy(notice = message) }
    }

    /** Backend-synced avatar edit; the roster re-reads so desktop agrees. */
    fun editAppearance(
        profileId: String,
        shape: String? = null,
        color: String? = null,
        clearImage: Boolean = false,
        onDone: (Boolean) -> Unit = {},
    ) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.setProfileAppearance(profileId, shape, color, clearImage) }
                .onSuccess { updated ->
                    applyAppearance(profileId, updated)
                    loadProfiles(_state.value.activeProfile)
                    loadRooms()
                    onDone(true)
                }
                .onFailure { t ->
                    _state.update { it.copy(notice = clean(t.message)) }
                    onDone(false)
                }
        }
    }

    /** The write response is authoritative — reflect it before the refetch. */
    private fun applyAppearance(
        profileId: String,
        appearance: com.alara.hermes.protocol.ProfileAppearance,
    ) {
        _state.update { st ->
            st.copy(profiles = st.profiles.map { p ->
                if (p.id == profileId) {
                    p.copy(
                        avatarShape = appearance.shape ?: p.avatarShape,
                        avatarColor = appearance.color ?: p.avatarColor,
                        avatarUrl = appearance.imageUrl,
                        appearanceRevision = appearance.revision ?: p.appearanceRevision,
                    )
                } else {
                    p
                }
            })
        }
    }

    fun uploadAvatar(
        profileId: String,
        imageBase64: String,
        mimeType: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.uploadProfileAvatar(profileId, imageBase64, mimeType) }
                .onSuccess { updated ->
                    applyAppearance(profileId, updated)
                    loadProfiles(_state.value.activeProfile)
                    loadRooms()
                    onDone(true)
                }
                .onFailure { t ->
                    _state.update { it.copy(notice = clean(t.message)) }
                    onDone(false)
                }
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
            }.onFailure { t ->
                // A silent background poll failing is noise, but a user-driven
                // refresh (e.g. a profile switch hitting a mirror that 404s
                // or lacks a profile-scoped key) must say what went wrong.
                _state.update {
                    it.copy(
                        sessionsLoading = false,
                        notice = if (silent) it.notice else clean(t.message),
                    )
                }
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

    /** Builds authenticated gateway URLs for MEDIA: directive paths. */
    fun mediaUrlBuilder(): ((String) -> String?)? =
        gateway?.let { gw -> { path: String -> gw.mediaUrl(path) } }

    /**
     * Bot Mode: open (or start) the profile's one canonical "Bot Chat"
     * conversation. Existing chats are found by title; a missing one opens a
     * fresh session that gets titled "Bot Chat" on its first message.
     */
    fun openBotChat(profileId: String) {
        val gw = gateway ?: return
        viewModelScope.launch {
            if (profileId != _state.value.activeProfile) {
                closeChat()
                gw.setActiveProfile(profileId)
                container.settings.setActiveProfile(profileId)
                _state.update {
                    it.copy(activeProfile = profileId, sessions = emptyList(), sessionsLoading = true)
                }
            }
            val sessions = runCatching { gw.listSessions(profileId) }.getOrDefault(emptyList())
            _state.update { it.copy(sessions = sortSessions(sessions), sessionsLoading = false) }
            val existing = sessions.firstOrNull {
                it.title.equals(BOT_CHAT_TITLE, ignoreCase = true) && !it.archived
            }
            container.pendingOpenSession.value = existing?.key ?: NEW_BOT_CHAT
        }
    }

    /** Start the not-yet-existing canonical Bot Chat for the active profile. */
    fun openNewBotChat() {
        openSession(null)
        _state.update { it.copy(chat = it.chat.copy(title = BOT_CHAT_TITLE)) }
    }

    /** Refresh the server room roster; quiet no-op when unsupported. */
    fun loadRooms() {
        val gw = gateway ?: return
        if (!gw.features.botRooms) {
            _state.update { it.copy(rooms = emptyList()) }
            return
        }
        viewModelScope.launch {
            runCatching { gw.listBotRooms() }
                .onSuccess { rooms -> _state.update { it.copy(rooms = rooms) } }
                .onFailure { t -> _state.update { it.copy(notice = clean(t.message)) } }
        }
    }

    /**
     * Open a server-backed room: one persistent transcript, sends carry
     * structured mentions, the room's manager profile scopes every request.
     */
    fun openRoom(room: com.alara.hermes.protocol.BotRoom) {
        val gw = gateway ?: return
        closeChat()
        _state.update {
            it.copy(
                activeProfile = room.managerProfileId,
                chat = ChatUiState(sessionKey = room.sessionKey, title = room.displayName, room = room),
            )
        }
        viewModelScope.launch {
            container.settings.setActiveProfile(room.managerProfileId)
            val opened = runCatching { gw.openRoom(room) }
            opened.onSuccess { h ->
                handle = h
                // Rooms are not resumed through lastOpenSession (that path
                // reopens plain sessions without the room contract).
                container.settings.setLastOpenSession(null)
                runCatching { h.refresh() }.onFailure { t ->
                    _state.update {
                        it.copy(
                            chat = it.chat.copy(
                                loadFailed = true,
                                error = clean("Couldn't load this room from the server: ${t.message}"),
                            ),
                        )
                    }
                }
                handleJobs += viewModelScope.launch {
                    h.timeline.collect { t -> _state.update { it.copy(chat = it.chat.copy(timeline = t)) } }
                }
                handleJobs += viewModelScope.launch {
                    h.config.collect { c -> _state.update { it.copy(chat = it.chat.copy(config = c)) } }
                }
                container.pendingOpenSession.value = ROOM_OPENED
            }.onFailure { t ->
                _state.update { it.copy(chat = it.chat.copy(error = clean(t.message))) }
            }
        }
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

    fun send(
        text: String,
        attachments: List<com.alara.hermes.protocol.OutgoingAttachment> = emptyList(),
        mentions: List<String> = emptyList(),
    ) {
        val h = handle ?: return
        if (_state.value.chat.loadFailed) return
        if (text.isBlank() && attachments.isEmpty()) return
        // A message typed while a turn is RUNNING steers the live run instead
        // of queuing a second turn (desktop /steer semantics).
        if (_state.value.chat.timeline?.running == true && attachments.isEmpty()) {
            viewModelScope.launch {
                val accepted = runCatching { h.steer(text) }.getOrDefault(false)
                _state.update {
                    it.copy(
                        notice = if (accepted) {
                            "Steered the running turn"
                        } else {
                            "Couldn't steer — wait for the turn to finish and send again"
                        },
                    )
                }
            }
            return
        }
        val currentTitle = _state.value.chat.title
        val needsTitle = (currentTitle.isBlank() || currentTitle == "New conversation" ||
            currentTitle == BOT_CHAT_TITLE) &&
            _state.value.chat.timeline?.entries.orEmpty().none {
                it is com.alara.hermes.protocol.ChatEntry.Message &&
                    it.role == com.alara.hermes.protocol.Role.USER
            }
        // Structured mentions come from the composer's picker state; the
        // scan below only backstops hand-typed mentions, and everything is
        // filtered to CURRENT room members — an unknown name never becomes
        // mention metadata.
        val room = _state.value.chat.room
        val members = room?.members.orEmpty()
        val memberIds = members.map { it.profileId }.toSet()
        val scanned = members.filter { member ->
            text.contains("@${member.mentionLabel}", ignoreCase = true) ||
                text.contains("@${member.displayName}", ignoreCase = true) ||
                text.contains("@${member.profileId}", ignoreCase = true)
        }.map { it.profileId }
        val roomMentions = (mentions.filter { it in memberIds } + scanned).distinct()
        _state.update { it.copy(chat = it.chat.copy(sending = true, error = null)) }
        viewModelScope.launch {
            runCatching {
                if (room != null) h.sendWithMentions(text, roomMentions) else h.send(text, attachments)
            }
                .onSuccess {
                    // The server never titles sessions minted from this surface;
                    // name it from the first message like desktop's auto-title.
                    if (needsTitle && text.isNotBlank() && gateway?.features?.rename == true) {
                        // Bot Chat keeps its canonical name; everything else is
                        // titled from the first message.
                        val derived = if (currentTitle == BOT_CHAT_TITLE) {
                            BOT_CHAT_TITLE
                        } else {
                            deriveTitle(text)
                        }
                        if (derived.isNotBlank()) {
                            runCatching { gateway?.renameSession(h.sessionKey, derived) }
                            _state.update { it.copy(chat = it.chat.copy(title = derived)) }
                        }
                    }
                }
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

    /** First line of the first message, clipped at a word boundary. */
    private fun deriveTitle(text: String): String {
        val line = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.length <= 48) return line
        val cut = line.take(48).substringBeforeLast(' ').ifBlank { line.take(48) }
        return "$cut…"
    }

    fun forkSession(sessionKey: String) {
        val gw = gateway ?: return
        viewModelScope.launch {
            runCatching { gw.forkSession(sessionKey) }
                .onSuccess { forkKey ->
                    _state.update { it.copy(notice = "Forked — opening the branch") }
                    refreshSessions(silent = true)
                    openSession(forkKey)
                }
                .onFailure { t ->
                    _state.update { it.copy(notice = clean("Fork failed: ${t.message}")) }
                }
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
            val gw = gateway
            // Re-probe capabilities on every foreground: the VPS may have
            // deployed new features while the app slept, and a stale cached
            // snapshot must never keep gating them off.
            gw?.let { runCatching { it.testConnection() } }
            _state.update { it.copy(features = gw?.features ?: it.features) }
            loadRooms()
            gw?.let { loadProfiles(_state.value.activeProfile) }
            handle?.let { runCatching { it.refresh() } }
            refreshSessions(silent = true)
        }
    }

    /**
     * Gate for avatar editing that distinguishes UNKNOWN from FALSE: a stale
     * or missing capability snapshot triggers a fresh probe, and the "newer
     * server required" message only shows when a SUCCESSFUL probe still says
     * the feature is absent.
     */
    fun ensureAvatarEditing(onResult: (Boolean) -> Unit) {
        val gw = gateway ?: return onResult(false)
        if (gw.features.avatarEditing) {
            onResult(true)
            return
        }
        viewModelScope.launch {
            val probe = runCatching { gw.testConnection().getOrThrow() }
            _state.update { it.copy(features = gw.features) }
            when {
                gw.features.avatarEditing -> onResult(true)
                probe.isFailure -> {
                    _state.update {
                        it.copy(notice = clean("Couldn't check server capabilities: ${probe.exceptionOrNull()?.message}"))
                    }
                    onResult(false)
                }
                else -> {
                    showNotice("Avatar editing requires a newer ALARA server")
                    onResult(false)
                }
            }
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
        const val BOT_CHAT_TITLE = "Bot Chat"

        /** Sentinel routed through pendingOpenSession for a fresh Bot Chat. */
        const val NEW_BOT_CHAT = "::new-bot-chat::"

        /** Sentinel: a room was already opened; the UI only needs to navigate. */
        const val ROOM_OPENED = "::room-opened::"

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(container) as T
        }
    }
}
