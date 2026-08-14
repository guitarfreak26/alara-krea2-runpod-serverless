package com.alara.hermes.protocol

import com.alara.hermes.protocol.wire.DashboardAuthClient
import com.alara.hermes.protocol.wire.GatewaySocket
import com.alara.hermes.protocol.wire.HermesCredential
import com.alara.hermes.protocol.wire.HermesRestClient
import com.alara.hermes.protocol.wire.HermesRpcException
import com.alara.hermes.protocol.wire.ProtocolMessage
import com.alara.hermes.protocol.wire.SessionCreateResult
import com.alara.hermes.protocol.wire.SessionResumeResult
import com.alara.hermes.protocol.wire.SocketState
import com.alara.hermes.protocol.wire.StoredSession
import com.alara.hermes.protocol.wire.ModelOptionsResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

data class GatewayEndpoint(
    val baseUrl: HttpUrl,
    val credential: HermesCredential,
)

/**
 * Live implementation of [HermesGateway] against the hermes-agent gateway:
 * JSON-RPC 2.0 over `/api/ws` for conversation control + events, and the REST
 * surface under `/api/` for stored sessions, profiles and history.
 *
 * Cross-client reality this design assumes (see docs/UPSTREAM.md):
 * - the backend is authoritative; we rehydrate rather than replay
 * - history rows carry no stable ids -> ordinal identity + wholesale rehydrate
 * - `prompt.submit` binds the live event stream to the submitting client, so a
 *   turn driven from Desktop streams there, not here: we show `running` and
 *   poll authoritative history until the turn settles
 * - profile-filtered listing only works over REST
 */
class HermesLiveGateway(
    private val endpoint: GatewayEndpoint,
    private val scope: CoroutineScope,
    private val json: Json = defaultJson,
    okHttpClient: OkHttpClient = GatewaySocket.defaultOkHttp(),
) : HermesGateway {

    private val rest = HermesRestClient(okHttpClient, json, endpoint.baseUrl, endpoint.credential)

    private val socket = GatewaySocket(okHttpClient, scope, json) {
        val builder = endpoint.baseUrl.newBuilder().addPathSegments("api/ws")
        when (val cred = endpoint.credential) {
            is HermesCredential.Token -> builder.addQueryParameter("token", cred.token)
            is HermesCredential.DashboardSession ->
                builder.addQueryParameter("ticket", rest.mintWsTicket())
        }
        val url = builder.build()
        // ws:// vs http:// is handled by OkHttp accepting http(s) URLs for websockets.
        url
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection

    override val features: GatewayFeatures = GatewayFeatures.DASHBOARD

    private val _sessionsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val sessionsChanged: SharedFlow<Unit> = _sessionsChanged

    private val _turnEvents = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 16)
    override val turnEvents: SharedFlow<TurnEvent> = _turnEvents

    /** durable key -> live handle */
    private val handles = ConcurrentHashMap<String, LiveSessionHandle>()

    /** runtime session id -> durable key */
    private val runtimeIndex = ConcurrentHashMap<String, String>()

    private var started = false

    init {
        scope.launch {
            socket.state.collect { state ->
                _connection.value = when (state) {
                    SocketState.Idle -> ConnectionState.Disconnected
                    SocketState.Connecting -> ConnectionState.Connecting
                    SocketState.Open -> ConnectionState.Connected()
                    is SocketState.Closed -> ConnectionState.Connecting
                    is SocketState.Retrying -> ConnectionState.Connecting
                }
            }
        }
        scope.launch {
            socket.connected.collect {
                // Fresh socket: runtime ids from the old socket are dead.
                val open = handles.values.toList()
                runtimeIndex.clear()
                open.forEach { handle ->
                    runCatching { handle.rebind() }
                }
                _sessionsChanged.tryEmit(Unit)
            }
        }
        scope.launch {
            socket.events.collect { event ->
                val runtime = event.resolvedSessionId
                // Events address the runtime id; `session.title` addresses the stored id.
                val handle = runtime?.let { id ->
                    runtimeIndex[id]?.let { handles[it] } ?: handles[id]
                }
                handle?.onEvent(event)
                when (event.type) {
                    // `sessions.changed` is the gateway's own change broadcast.
                    "sessions.changed", "session.title", "message.complete", "session.info", "error" ->
                        _sessionsChanged.tryEmit(Unit)
                }
            }
        }
    }

    override suspend fun connect() {
        started = true
        socket.start()
    }

    override fun disconnect() {
        started = false
        handles.values.forEach { it.close() }
        handles.clear()
        runtimeIndex.clear()
        socket.stop()
    }

    override suspend fun testConnection(): Result<String> = runCatching {
        // Authenticated probe: validates both reachability and the credential
        // (public endpoints like /health or /api/status would accept a bad key).
        rest.listSessions(profile = null, limit = 1)
        "connected"
    }

    override suspend fun listProfiles(): List<HermesProfile> =
        rest.listProfiles().mapNotNull { info ->
            val name = info.name ?: return@mapNotNull null
            HermesProfile(
                id = name,
                displayName = info.displayName ?: name,
                isDefault = info.isDefault == true,
            )
        }

    override suspend fun listSessions(profileId: String?): List<SessionSummary> =
        rest.listSessions(profileId, archived = "include").mapNotNull { it.toSummary(profileId) }

    override suspend fun searchSessions(query: String, profileId: String?): List<SessionSummary> =
        rest.searchSessions(query, profileId).mapNotNull { it.toSummary(profileId) }

    private fun StoredSession.toSummary(fallbackProfile: String?): SessionSummary? {
        val key = durableId ?: return null
        return SessionSummary(
            key = key,
            profileId = profile ?: fallbackProfile ?: "default",
            title = title?.takeIf { it.isNotBlank() } ?: "New conversation",
            preview = (preview ?: lastMessage).orEmpty().replace('\n', ' ').take(140),
            updatedAtMs = ((lastActive ?: startedAt) ?: 0.0).let { (it * 1000).toLong() },
            running = running == true || isActive == true,
            pinned = pinned == true,
            archived = archived == true,
            source = source,
            model = model,
        )
    }

    override suspend fun listModels(sessionKey: String?): List<ModelOption> {
        val runtime = sessionKey?.let { handles[it]?.runtimeId } ?: return emptyList()
        val result = socket.request(
            "model.options",
            buildJsonObject {
                put("session_id", runtime)
                put("explicit_only", true)
            },
        )
        val parsed = runCatching {
            json.decodeFromJsonElement(ModelOptionsResult.serializer(), result)
        }.getOrNull() ?: return emptyList()
        val fromProviders = parsed.providers.flatMap { provider ->
            provider.models.mapNotNull { entry ->
                val id = entry.resolvedId ?: return@mapNotNull null
                ModelOption(
                    id = id,
                    provider = entry.provider ?: provider.slug ?: provider.name,
                    displayName = entry.name ?: id,
                    isCurrent = entry.isCurrent == true || provider.isCurrent == true,
                )
            }
        }
        val flat = parsed.models.mapNotNull { entry ->
            val id = entry.resolvedId ?: return@mapNotNull null
            ModelOption(id = id, provider = entry.provider, displayName = entry.name ?: id)
        }
        return (fromProviders + flat).distinctBy { "${it.provider}/${it.id}" }
    }

    override suspend fun openSession(sessionKey: String?, profileId: String?): SessionHandle {
        socket.start()
        return if (sessionKey == null) {
            createSession(profileId)
        } else {
            handles[sessionKey]?.also { scope.launch { runCatching { it.refresh() } } }
                ?: resumeSession(sessionKey, profileId)
        }
    }

    private suspend fun createSession(profileId: String?): SessionHandle {
        val result = socket.request(
            "session.create",
            buildJsonObject {
                put("cols", 96)
                put("source", "android")
                profileId?.let { put("profile", it) }
            },
        )
        val created = json.decodeFromJsonElement(SessionCreateResult.serializer(), result)
        val runtime = created.sessionId ?: throw HermesRpcException("session.create returned no id")
        val durable = created.storedSessionId ?: runtime
        val handle = LiveSessionHandle(durable, profileId, runtime)
        handle.applyRuntimeInfo(created.info)
        handles[durable] = handle
        runtimeIndex[runtime] = durable
        _sessionsChanged.tryEmit(Unit)
        return handle
    }

    private suspend fun resumeSession(sessionKey: String, profileId: String?): SessionHandle {
        val handle = LiveSessionHandle(sessionKey, profileId, runtimeId = null)
        handles[sessionKey] = handle
        handle.rebind()
        return handle
    }

    override suspend fun renameSession(sessionKey: String, title: String) {
        // REST PATCH addresses the stored id and works for non-live sessions too.
        val profile = handles[sessionKey]?.profileId
        rest.patchSession(sessionKey, profile, title = title)
        _sessionsChanged.tryEmit(Unit)
    }

    override suspend fun setPinned(sessionKey: String, pinned: Boolean) {
        rest.patchSession(sessionKey, handles[sessionKey]?.profileId, pinned = pinned)
        _sessionsChanged.tryEmit(Unit)
    }

    override suspend fun setArchived(sessionKey: String, archived: Boolean) {
        rest.patchSession(sessionKey, handles[sessionKey]?.profileId, archived = archived)
        _sessionsChanged.tryEmit(Unit)
    }

    override suspend fun deleteSession(sessionKey: String) {
        handles.remove(sessionKey)?.close()
        socket.request("session.delete", buildJsonObject { put("session_id", sessionKey) })
        _sessionsChanged.tryEmit(Unit)
    }

    // ------------------------------------------------------------------

    inner class LiveSessionHandle(
        override val sessionKey: String,
        override val profileId: String?,
        @Volatile var runtimeId: String?,
    ) : SessionHandle {

        private val _timeline = MutableStateFlow(TimelineState(sessionKey))
        override val timeline: StateFlow<TimelineState> = _timeline

        private val _config = MutableStateFlow(SessionConfig())
        override val config: StateFlow<SessionConfig> = _config

        private val sendMutex = Mutex()

        /** True while a turn submitted from THIS client is running. */
        @Volatile private var ownsTurn = false

        private var pollJob: Job? = startRemoteTurnPolling()

        fun onEvent(event: com.alara.hermes.protocol.wire.WireEvent) {
            if (event.type == "session.info") {
                val payload = event.payload as? JsonObject ?: return
                _config.update { current ->
                    current.copy(
                        model = payload.strOrNull("model") ?: current.model,
                        provider = payload.strOrNull("provider") ?: current.provider,
                        thinkingLevel = payload.strOrNull("reasoning_effort") ?: current.thinkingLevel,
                        fastMode = payload.boolOrNull("fast") ?: current.fastMode,
                    )
                }
                return
            }
            _timeline.update { TimelineReducer.reduce(it, event, System.currentTimeMillis()) }
            when (event.type) {
                "message.complete" -> {
                    val payload = event.payload as? JsonObject
                    if (payload?.strOrNull("status") == "error") {
                        _turnEvents.tryEmit(
                            TurnEvent.Failed(sessionKey, payload.strOrNull("error") ?: "turn failed"),
                        )
                    } else {
                        _turnEvents.tryEmit(
                            TurnEvent.Completed(sessionKey, payload?.strOrNull("text").orEmpty().take(160)),
                        )
                    }
                }
                "error" -> _turnEvents.tryEmit(
                    TurnEvent.Failed(
                        sessionKey,
                        (event.payload as? JsonObject)?.strOrNull("error") ?: "turn failed",
                    ),
                )
                "approval.request", "clarify.request" -> {
                    val payload = event.payload as? JsonObject
                    _turnEvents.tryEmit(
                        TurnEvent.ApprovalRequested(
                            sessionKey,
                            payload?.strOrNull("command") ?: payload?.strOrNull("question")
                                ?: "The agent needs your input",
                        ),
                    )
                }
            }
            if (TimelineReducer.isTerminalEvent(event.type)) {
                ownsTurn = false
            }
        }

        /** (Re-)resume on the current socket and rehydrate authoritative history. */
        suspend fun rebind() {
            val result = socket.request(
                "session.resume",
                buildJsonObject {
                    put("session_id", sessionKey)
                    put("cols", 96)
                    put("source", "android")
                    profileId?.let { put("profile", it) }
                },
            )
            val resume = json.decodeFromJsonElement(SessionResumeResult.serializer(), result)
            val runtime = resume.sessionId
            if (runtime != null) {
                runtimeId = runtime
                runtimeIndex[runtime] = sessionKey
            }
            applyRuntimeInfo(resume.info)
            val restMessages = runCatching { rest.sessionMessages(sessionKey, profileId) }
                .getOrDefault(emptyList())
            // REST is authoritative when it knows strictly more; the resume
            // payload wins otherwise (it reflects the live projection).
            val messages = if (restMessages.size > resume.messages.size) restMessages else resume.messages
            applyAuthoritativeHistory(messages, resume)
        }

        private fun applyAuthoritativeHistory(messages: List<ProtocolMessage>, resume: SessionResumeResult?) {
            _timeline.update { state ->
                var next = TimelineReducer.rehydrate(state, messages, System.currentTimeMillis())
                // Resume status is idle|starting|working|waiting; anything but idle
                // means the agent still owes this session output or a decision.
                val running = resume?.running == true ||
                    resume?.status in setOf("working", "starting", "waiting") ||
                    resume?.inflight?.streaming == true
                val inflightUser = resume?.inflight?.user
                if (!inflightUser.isNullOrBlank() &&
                    next.entries.none { it is ChatEntry.Message && it.role == Role.USER && it.text == inflightUser }
                ) {
                    next = next.copy(
                        entries = next.entries + ChatEntry.Message(
                            id = EntryId("inflight-user:$sessionKey:${next.entries.size}"),
                            timestampMs = System.currentTimeMillis(),
                            role = Role.USER,
                            text = inflightUser,
                        ),
                    )
                }
                val inflightAssistant = resume?.inflight?.assistant
                if (!inflightAssistant.isNullOrBlank()) {
                    val generation = next.generation + 1
                    val id = EntryId("assistant:$sessionKey:$generation")
                    next = next.copy(
                        generation = generation,
                        streamingAssistantId = id,
                        entries = next.entries + ChatEntry.Message(
                            id = id,
                            timestampMs = System.currentTimeMillis(),
                            role = Role.ASSISTANT,
                            text = inflightAssistant,
                            streaming = true,
                        ),
                    )
                }
                next.copy(running = running)
            }
        }

        fun applyRuntimeInfo(info: com.alara.hermes.protocol.wire.SessionRuntimeInfo?) {
            info ?: return
            _config.update {
                SessionConfig(
                    model = info.model,
                    provider = info.provider,
                    thinkingLevel = info.reasoningEffort,
                    fastMode = info.fast,
                )
            }
        }

        override suspend fun send(text: String, attachments: List<OutgoingAttachment>) {
            sendMutex.withLock {
                val runtime = runtimeId ?: run { rebind(); runtimeId }
                    ?: throw HermesRpcException("session is not bound")
                // Stage attachments first; non-image files contribute ref_text
                // lines the prompt must carry (they are not structured fields
                // of prompt.submit on this surface).
                val refLines = mutableListOf<String>()
                attachments.forEach { attachment ->
                    if (attachment.isImage) {
                        socket.request("image.attach_bytes", buildJsonObject {
                            put("session_id", runtime)
                            put("content_base64", attachment.dataBase64)
                            put("filename", attachment.name)
                        }, timeoutMs = 120_000)
                    } else {
                        val result = socket.request("file.attach", buildJsonObject {
                            put("session_id", runtime)
                            put("name", attachment.name)
                            put("data_url", attachment.dataUrl)
                        }, timeoutMs = 120_000)
                        (result as? JsonObject)?.strOrNull("ref_text")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { refLines += it }
                    }
                }
                val fullText = if (refLines.isEmpty()) text else {
                    (refLines + text).joinToString("\n")
                }
                // Optimistic local echo; replaced by authoritative history on next rehydrate.
                _timeline.update { state ->
                    state.copy(
                        running = true,
                        entries = state.entries + ChatEntry.Message(
                            id = EntryId("local-user:$sessionKey:${state.entries.size}:${text.hashCode()}"),
                            timestampMs = System.currentTimeMillis(),
                            role = Role.USER,
                            text = text,
                        ),
                    )
                }
                ownsTurn = true
                _turnEvents.tryEmit(TurnEvent.Started(sessionKey, runId = null))
                try {
                    // Desktop parity: the ack can be legitimately slow; completion
                    // arrives as message.complete regardless.
                    socket.request(
                        "prompt.submit",
                        buildJsonObject {
                            put("session_id", runtime)
                            put("text", fullText)
                        },
                        timeoutMs = 1_800_000,
                    )
                } catch (t: Throwable) {
                    // Ambiguous or failed ack: never auto-resubmit. Re-sync state;
                    // the user decides whether to send again.
                    ownsTurn = false
                    runCatching { refresh() }
                    throw t
                }
            }
        }

        override suspend fun interrupt() {
            val runtime = runtimeId ?: return
            socket.request("session.interrupt", buildJsonObject { put("session_id", runtime) })
        }

        override suspend fun respondApproval(entryId: EntryId, choice: String) {
            val entry = _timeline.value.entries
                .firstOrNull { it.id == entryId } as? ChatEntry.Approval ?: return
            if (entry.requestId.isEmpty()) {
                // Command approvals are session-keyed on the wire.
                val runtime = runtimeId ?: throw HermesRpcException("session is not bound")
                socket.request("approval.respond", buildJsonObject {
                    put("session_id", runtime)
                    put("choice", choice)
                })
            } else {
                socket.request("clarify.respond", buildJsonObject {
                    put("request_id", entry.requestId)
                    put("answer", choice)
                })
            }
            _timeline.update { TimelineReducer.resolveApproval(it, entryId) }
        }

        private suspend fun configSet(key: String, value: String, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) {
            val runtime = runtimeId ?: throw HermesRpcException("session is not bound")
            socket.request("config.set", buildJsonObject {
                put("session_id", runtime)
                put("key", key)
                put("value", value)
                extra?.invoke(this)
            })
        }

        override suspend fun setModel(model: String, provider: String?) {
            // CLI-style value; `--session` scopes the override to this session
            // (dropping it would silently rewrite the profile default).
            val value = if (provider.isNullOrBlank() || provider == "custom") {
                "$model --session"
            } else {
                "$model --provider $provider --session"
            }
            configSet("model", value)
            _config.update { it.copy(model = model, provider = provider) }
        }

        override suspend fun setReasoning(level: String) {
            // "" = provider default on the wire; "none" = explicitly disabled.
            val value = if (level == "default") "" else level
            configSet("reasoning", value)
            _config.update { it.copy(thinkingLevel = level.takeIf { l -> l != "default" }) }
        }

        override suspend fun setFastMode(enabled: Boolean) {
            configSet("fast", if (enabled) "fast" else "normal")
            _config.update { it.copy(fastMode = enabled) }
        }

        override suspend fun refresh() {
            rebind()
        }

        override fun close() {
            pollJob?.cancel()
            pollJob = null
            handles.remove(sessionKey, this)
            runtimeId?.let { runtimeIndex.remove(it, sessionKey) }
        }

        /**
         * While another client owns the running turn, its stream does not
         * reach this socket. Poll authoritative history at a low rate so the
         * conversation converges without manual refresh.
         */
        private fun startRemoteTurnPolling(): Job = scope.launch {
            while (isActive) {
                delay(6_000)
                val state = _timeline.value
                if (state.running && !ownsTurn && socket.isOpen) {
                    runCatching { rebind() }
                }
            }
        }
    }

    companion object {
        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
            isLenient = false
        }
    }
}

private fun JsonObject.strOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.booleanOrNull
