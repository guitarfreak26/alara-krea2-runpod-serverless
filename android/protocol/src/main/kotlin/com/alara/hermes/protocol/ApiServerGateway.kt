package com.alara.hermes.protocol

import com.alara.hermes.protocol.wire.HermesHttpException
import com.alara.hermes.protocol.wire.HermesRpcException
import com.alara.hermes.protocol.wire.ProtocolMessage
import com.alara.hermes.protocol.wire.StoredSession
import com.alara.hermes.protocol.wire.WireEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [HermesGateway] implementation for the Hermes **API server** surface
 * (default port 8642): OpenAI-compatible REST + SSE with Bearer auth.
 *
 * Contract used here (traced from hermes-agent gateway/platforms/api_server.py):
 * - auth:      `Authorization: Bearer <API_SERVER_KEY>` on EVERY request; the
 *              server strips + timing-safe-compares the token and answers 401
 *              `gateway_auth_failed` on mismatch. Nothing else is used for
 *              auth: no X-API-Key, no cookies, no dashboard-password flow.
 * - validate:  GET /v1/capabilities (auth-required) — also advertises the
 *              deployment's feature/endpoint surface, which gates everything
 *              below at runtime.
 * - chat:      POST /v1/chat/completions  {model, messages, stream:true}
 *              + header X-Hermes-Session-Id binding the Hermes session;
 *              streaming arrives as SSE deltas plus `hermes.tool.progress`
 *              tool events; dropping the connection interrupts the turn.
 * - sessions:  GET /api/sessions, GET /api/sessions/{id}/messages,
 *              PATCH/DELETE /api/sessions/{id} — called ONLY when
 *              capabilities advertises `session_resources`; never used for
 *              validation.
 * - models:    GET /v1/models -> {data:[{id}]}
 *
 * The raw token never appears in logs or user-facing errors (see [redactSecrets]).
 */
/** The session row is unknown on this surface — fall back to a legacy turn path. */
private class SessionRowMissing : Exception()

class ApiServerGateway(
    private val baseUrl: HttpUrl,
    token: String,
    private val scope: CoroutineScope,
    private val json: Json = HermesLiveGateway.defaultJson,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : HermesGateway {

    // Pasted keys often carry stray whitespace/newlines; the server compares
    // the stripped value, so trim before it ever leaves the device.
    private val token: String = token.trim()

    private val jsonMedia = "application/json".toMediaType()

    /**
     * Plain REST calls get bounded timeouts; only SSE streams may run
     * unbounded. Sharing the streaming client's readTimeout(0) for JSON calls
     * would hang forever against a stalled server.
     */
    private val restClient: OkHttpClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection

    /** What this deployment advertised via /v1/capabilities. */
    data class ServerCapabilities(
        val sessionResources: Boolean,
        val sessionUpdate: Boolean,
        val runSubmission: Boolean = false,
        /** POST /api/sessions/{id}/chat/stream — server-side history, no transcript resend. */
        val sessionChatStreaming: Boolean = false,
        val skillsApi: Boolean? = null,
        val jobsAvailable: Boolean? = null,
        val model: String? = null,
    )

    @Volatile private var capabilities: ServerCapabilities? = null

    /** Set when PATCH rejects pinned/archived: the server build predates flag sync. */
    @Volatile private var sessionFlagsRejected = false

    override val features: GatewayFeatures
        get() = GatewayFeatures.API_SERVER.copy(
            rename = capabilities?.sessionUpdate == true,
            sessionFlags = capabilities?.sessionUpdate == true && !sessionFlagsRejected,
            // Structured approvals exist only on the /v1/runs stream.
            approvals = capabilities?.runSubmission == true,
            // Thinking/fast ride each request as model_options overrides.
            sessionConfig = true,
            skills = capabilities?.skillsApi != false,
            automations = capabilities?.jobsAvailable != false,
        )

    private val _sessionsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val sessionsChanged: SharedFlow<Unit> = _sessionsChanged

    private val _turnEvents = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 16)
    override val turnEvents: SharedFlow<TurnEvent> = _turnEvents

    private val handles = ConcurrentHashMap<String, ApiSessionHandle>()

    private fun url(vararg segments: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        segments.forEach { builder.addPathSegments(it) }
        return builder.build()
    }

    private fun request(target: HttpUrl): Request.Builder =
        Request.Builder().url(target).header("Authorization", "Bearer $token")

    /** Strip the token (and any credential query params) from outbound text. */
    fun redactSecrets(text: String): String = redact(text, token)

    private suspend fun getJson(target: HttpUrl): JsonElement = withContext(Dispatchers.IO) {
        restClient.newCall(request(target).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HermesHttpException(response.code, "HTTP ${response.code} ${target.encodedPath}")
            }
            json.parseToJsonElement(body)
        }
    }

    override suspend fun connect() {
        if (_connection.value !is ConnectionState.Connected) {
            testConnection()
                .onSuccess { _connection.value = ConnectionState.Connected(it) }
                .onFailure { _connection.value = ConnectionState.Failed(it.message ?: "unreachable") }
        }
    }

    override fun disconnect() {
        handles.values.forEach { it.close() }
        handles.clear()
        _connection.value = ConnectionState.Disconnected
    }

    override suspend fun testConnection(): Result<String> = runCatching {
        val caps = getJson(url("v1/capabilities")) as? JsonObject
        val features = caps?.get("features") as? JsonObject
        val endpoints = caps?.get("endpoints") as? JsonObject
        val parsed = ServerCapabilities(
            sessionResources = (features?.get("session_resources") as? JsonPrimitive)
                ?.contentOrNull?.toBooleanStrictOrNull()
                ?: (endpoints?.get("sessions") != null),
            sessionUpdate = endpoints?.get("session_update") != null,
            runSubmission = (features?.get("run_submission") as? JsonPrimitive)
                ?.contentOrNull?.toBooleanStrictOrNull()
                ?: (endpoints?.get("runs") != null),
            sessionChatStreaming = (features?.get("session_chat_streaming") as? JsonPrimitive)
                ?.contentOrNull?.toBooleanStrictOrNull() == true,
            skillsApi = (features?.get("skills_api") as? JsonPrimitive)
                ?.contentOrNull?.toBooleanStrictOrNull(),
            // jobs_admin=false means CRUD is locked down; listing may still work,
            // so treat only an explicit false as absent when the probe fails later.
            jobsAvailable = null,
            model = (caps?.get("model") as? JsonPrimitive)?.contentOrNull,
        )
        capabilities = parsed
        // A fresh capability probe re-tests flag support: after the VPS updates
        // hermes-agent, pin/archive re-enable on the next reconnect.
        sessionFlagsRejected = false
        val label = parsed.model?.let { "connected ($it)" } ?: "connected"
        _connection.value = ConnectionState.Connected(label)
        label
    }

    override suspend fun listProfiles(): List<HermesProfile> = emptyList()

    private fun dataRows(element: JsonElement): JsonArray = when (element) {
        is JsonArray -> element
        is JsonObject -> (element["data"] ?: element["sessions"] ?: element["messages"]) as? JsonArray
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }

    /** Sessions the app itself opened; the fallback list when the server does not expose session resources. */
    private fun locallyKnownSessions(): List<SessionSummary> = handles.values.map { handle ->
        SessionSummary(
            key = handle.sessionKey,
            profileId = "default",
            title = "Conversation ${handle.sessionKey.takeLast(6)}",
            preview = (handle.timeline.value.entries.lastOrNull() as? ChatEntry.Message)
                ?.text.orEmpty().replace('\n', ' ').take(140),
            updatedAtMs = handle.timeline.value.entries.lastOrNull()?.timestampMs ?: 0L,
            running = handle.timeline.value.running,
        )
    }.sortedByDescending { it.updatedAtMs }

    override suspend fun listSessions(profileId: String?): List<SessionSummary> {
        if (capabilities == null) connect()
        // Session resources are optional on this surface; only call the
        // endpoint when the deployment advertises it via /v1/capabilities.
        if (capabilities?.sessionResources != true) return locallyKnownSessions()
        val rows = dataRows(getJson(url("api/sessions")))
        return rows.mapNotNull { row ->
            val session = runCatching {
                json.decodeFromJsonElement(StoredSession.serializer(), row)
            }.getOrNull() ?: return@mapNotNull null
            val key = session.durableId ?: return@mapNotNull null
            val ended = (row as? JsonObject)?.get("ended_at")
            SessionSummary(
                key = key,
                profileId = "default",
                title = session.title?.takeIf { it.isNotBlank() } ?: "New conversation",
                preview = (session.preview ?: session.lastMessage).orEmpty()
                    .replace('\n', ' ').take(140),
                updatedAtMs = ((session.lastActive ?: session.startedAt) ?: 0.0)
                    .let { (it * 1000).toLong() },
                running = handles[key]?.timeline?.value?.running == true ||
                    (session.isActive == true && (ended == null || ended is kotlinx.serialization.json.JsonNull)),
                pinned = session.pinned == true,
                archived = session.archived == true,
                source = session.source,
                model = session.model,
            )
        }.sortedByDescending { it.updatedAtMs }
    }

    override suspend fun searchSessions(query: String, profileId: String?): List<SessionSummary> {
        val needle = query.trim().lowercase()
        return listSessions(profileId).filter {
            needle.isEmpty() || it.title.lowercase().contains(needle) ||
                it.preview.lowercase().contains(needle)
        }
    }

    override suspend fun listModels(sessionKey: String?): List<ModelOption> {
        // Rich inventory first: mirrors the dashboard/TUI picker and carries
        // the profile's ACTUAL configured model — /v1/models only advertises
        // the virtual "hermes-agent" alias.
        val inventory = runCatching {
            val payload = getJson(url("api/model/options")) as? JsonObject
                ?: return@runCatching emptyList()
            val currentModel = (payload["model"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val currentProvider = (payload["provider"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val providers = payload["providers"] as? JsonArray ?: return@runCatching emptyList()
            providers.flatMap { row ->
                val obj = row as? JsonObject ?: return@flatMap emptyList<ModelOption>()
                val slug = (obj["slug"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
                val label = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: slug
                val rowCurrent = (obj["is_current"] as? JsonPrimitive)?.contentOrNull == "true"
                (obj["models"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val id = when (entry) {
                        is JsonPrimitive -> entry.contentOrNull
                        is JsonObject -> ((entry["id"] ?: entry["name"]) as? JsonPrimitive)?.contentOrNull
                        else -> null
                    } ?: return@mapNotNull null
                    ModelOption(
                        id = id,
                        provider = slug,
                        displayName = id,
                        isCurrent = id == currentModel &&
                            (currentProvider.isEmpty() || slug.equals(currentProvider, true) || rowCurrent),
                    )
                }
            }.sortedByDescending { it.isCurrent }
        }.getOrDefault(emptyList())
        if (inventory.isNotEmpty()) return inventory
        return runCatching {
            dataRows(getJson(url("v1/models"))).mapNotNull { row ->
                val id = (row as? JsonObject)?.get("id")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
                ModelOption(id = id, displayName = id)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openSession(sessionKey: String?, profileId: String?): SessionHandle {
        if (capabilities == null) connect()
        val key = if (sessionKey != null) {
            sessionKey
        } else if (capabilities?.sessionChatStreaming == true && capabilities?.sessionResources == true) {
            // The server mints the canonical session id; every later request,
            // relaunch and reconnect reuses exactly this id.
            createServerSession()
        } else {
            // Legacy surfaces: client-generated durable id carried on every
            // completion request so REST history and chat share one identity.
            "mob-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        }
        return handles.getOrPut(key) { ApiSessionHandle(key, isNew = sessionKey == null) }
        // Existing sessions: the caller awaits refresh() so a failed history
        // load surfaces as an error instead of silently starting fresh.
    }

    /** POST /api/sessions — create an empty canonical session row. */
    private suspend fun createServerSession(): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("source", "android") }
        restClient.newCall(
            request(url("api/sessions"))
                .post(body.toString().toRequestBody(jsonMedia)).build(),
        ).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HermesHttpException(response.code, "session create failed: HTTP ${response.code}")
            }
            val obj = json.parseToJsonElement(payload) as? JsonObject
            val nested = obj?.get("session") as? JsonObject
            listOf(obj?.get("id"), obj?.get("session_id"), nested?.get("id"), nested?.get("session_id"))
                .firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw HermesRpcException("session create returned no id")
        }
    }

    override suspend fun renameSession(sessionKey: String, title: String) {
        patchSessionFlag(sessionKey, buildJsonObject { put("title", title) }, "rename")
    }

    override suspend fun setPinned(sessionKey: String, pinned: Boolean) {
        patchSessionFlag(sessionKey, buildJsonObject { put("pinned", pinned) }, "pin")
    }

    override suspend fun setArchived(sessionKey: String, archived: Boolean) {
        patchSessionFlag(sessionKey, buildJsonObject { put("archived", archived) }, "archive")
    }

    /** PATCH /api/sessions/{id} — durable flags shared with the desktop sidebar. */
    private suspend fun patchSessionFlag(sessionKey: String, body: JsonObject, action: String) {
        if (capabilities?.sessionUpdate != true) {
            throw HermesRpcException("this server does not advertise session updates ($action)")
        }
        withContext(Dispatchers.IO) {
            restClient.newCall(
                request(url("api/sessions", sessionKey))
                    .patch(body.toString().toRequestBody(jsonMedia)).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty()
                    // Older hermes-agent builds allow only {title, end_reason}
                    // here and 400 on pinned/archived. Degrade once, loudly.
                    if (response.code == 400 &&
                        (body.containsKey("pinned") || body.containsKey("archived")) &&
                        (detail.contains("unsupported_session_field") ||
                            detail.contains("Unsupported session fields"))
                    ) {
                        sessionFlagsRejected = true
                        throw HermesRpcException(
                            "This Hermes build doesn't sync pin/archive yet — " +
                                "update hermes-agent on the VPS to enable it",
                        )
                    }
                    throw HermesHttpException(response.code, "$action failed: HTTP ${response.code}")
                }
            }
        }
        _sessionsChanged.tryEmit(Unit)
    }

    override suspend fun deleteSession(sessionKey: String) {
        handles.remove(sessionKey)?.close()
        if (capabilities?.sessionResources == true) {
            withContext(Dispatchers.IO) {
                restClient.newCall(request(url("api/sessions", sessionKey)).delete().build())
                    .execute().use { response ->
                        // 404 = already gone; that's success for a delete.
                        if (!response.isSuccessful && response.code != 404) {
                            throw HermesHttpException(response.code, "delete failed: HTTP ${response.code}")
                        }
                    }
            }
        }
        _sessionsChanged.tryEmit(Unit)
    }

    override suspend fun listSkills(): List<SkillInfo> {
        val payload = getJson(url("v1/skills"))
        return dataRows(payload).mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SkillInfo(
                name = name,
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                category = (obj["category"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                disabled = (obj["disabled"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
    }

    private fun parseJob(row: JsonElement): AutomationInfo? {
        val obj = row as? JsonObject ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val id = str("id") ?: return null
        val schedule = str("schedule_display")
            ?: str("schedule")
            ?: ((obj["schedule"] as? JsonObject)?.get("display") as? JsonPrimitive)?.contentOrNull
            ?: ""
        val lastRun = obj["last_run_at"] ?: obj["last_execution_at"]
        return AutomationInfo(
            id = id,
            name = str("name")?.takeIf { it.isNotBlank() } ?: id,
            schedule = schedule,
            prompt = str("prompt").orEmpty(),
            enabled = str("enabled") != "false",
            paused = str("state") == "paused" ||
                (obj["paused_at"] != null && obj["paused_at"] !is kotlinx.serialization.json.JsonNull),
            lastRunAtMs = ((lastRun as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull())
                ?.let { (it * 1000).toLong() },
            lastStatus = str("last_status") ?: str("last_result"),
        )
    }

    override suspend fun listAutomations(): List<AutomationInfo> {
        val payload = getJson(url("api/jobs"))
        val rows = (payload as? JsonObject)?.get("jobs") as? JsonArray ?: dataRows(payload)
        return rows.mapNotNull(::parseJob)
    }

    private suspend fun postJobAction(id: String, action: String) {
        withContext(Dispatchers.IO) {
            restClient.newCall(
                request(url("api/jobs", id, action))
                    .post("{}".toRequestBody(jsonMedia)).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HermesHttpException(response.code, "$action failed: HTTP ${response.code}")
                }
            }
        }
    }

    override suspend fun setAutomationPaused(id: String, paused: Boolean) {
        postJobAction(id, if (paused) "pause" else "resume")
    }

    override suspend fun runAutomation(id: String) {
        postJobAction(id, "run")
    }

    override suspend fun deleteAutomation(id: String) {
        withContext(Dispatchers.IO) {
            restClient.newCall(request(url("api/jobs", id)).delete().build())
                .execute().use { response ->
                    if (!response.isSuccessful && response.code != 404) {
                        throw HermesHttpException(response.code, "delete failed: HTTP ${response.code}")
                    }
                }
        }
    }

    /**
     * Provider allowance bars. Contract (proposed to upstream; the app
     * degrades cleanly until the endpoint exists): GET /v1/usage ->
     * {"providers":[{"provider","plan","unavailable_reason",
     *   "windows":[{"label","used_percent","reset_at","detail"}],
     *   "details":["..."]}]}
     * — the serialized form of agent/account_usage.py AccountUsageSnapshot.
     */
    override suspend fun accountUsage(): List<AccountUsage> {
        val payload = getJson(url("v1/usage"))
        val rows = when (payload) {
            is JsonArray -> payload
            is JsonObject -> (payload["providers"] ?: payload["snapshots"] ?: payload["data"]) as? JsonArray
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            val provider = str("provider") ?: str("title") ?: return@mapNotNull null
            val windows = (obj["windows"] as? JsonArray).orEmpty().mapNotNull { win ->
                val w = win as? JsonObject ?: return@mapNotNull null
                fun wstr(key: String) = (w[key] as? JsonPrimitive)?.contentOrNull
                UsageWindow(
                    label = wstr("label") ?: "window",
                    usedPercent = wstr("used_percent")?.toDoubleOrNull()
                        ?: wstr("percent_used")?.toDoubleOrNull(),
                    resetAtMs = wstr("reset_at")?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                    detail = wstr("detail"),
                )
            }
            AccountUsage(
                provider = provider,
                plan = str("plan"),
                windows = windows,
                details = (obj["details"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                unavailableReason = str("unavailable_reason"),
            )
        }
    }

    override suspend fun usageSummary(profileId: String?): UsageSummary {
        val payload = getJson(url("api/sessions").newBuilder().addQueryParameter("limit", "200").build())
        val rows = dataRows(payload).mapNotNull { row ->
            runCatching { json.decodeFromJsonElement(StoredSession.serializer(), row) }.getOrNull()
        }
        val dayAgo = System.currentTimeMillis() / 1000.0 - 24 * 3600
        fun totals(sessions: List<StoredSession>) = TokenTotals(
            inputTokens = sessions.sumOf { it.inputTokens ?: 0L },
            outputTokens = sessions.sumOf { it.outputTokens ?: 0L },
            estimatedCostUsd = sessions.mapNotNull { it.actualCostUsd ?: it.estimatedCostUsd }
                .takeIf { it.isNotEmpty() }?.sum(),
            sessionCount = sessions.size,
        )
        return UsageSummary(
            today = totals(rows.filter { (it.lastActive ?: it.startedAt ?: 0.0) >= dayAgo }),
            allListed = totals(rows),
        )
    }

    /** Pollable status of a run, for reconciling after process death. */
    data class RunOutcome(val status: String, val output: String?, val error: String?)

    /** GET /v1/runs/{id} — null when the server no longer knows the run. */
    suspend fun reconcileRun(runId: String): RunOutcome? = runCatching {
        val status = getJson(url("v1/runs", runId)) as? JsonObject ?: return null
        RunOutcome(
            status = (status["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown",
            output = (status["output"] as? JsonPrimitive)?.contentOrNull,
            error = (status["error"] as? JsonPrimitive)?.contentOrNull,
        )
    }.getOrNull()

    inner class ApiSessionHandle(
        override val sessionKey: String,
        private val isNew: Boolean = false,
    ) : SessionHandle {

        /** True once the authoritative transcript has loaded at least once. */
        @Volatile private var historyLoaded = isNew
        override val profileId: String = "default"

        private val _timeline = MutableStateFlow(TimelineState(sessionKey))
        override val timeline: StateFlow<TimelineState> = _timeline

        private val _config = MutableStateFlow(SessionConfig())
        override val config: StateFlow<SessionConfig> = _config

        private var streamJob: Job? = null
        @Volatile private var activeCall: okhttp3.Call? = null
        @Volatile private var interrupted = false
        @Volatile private var activeRunId: String? = null
        private val seenTools = ConcurrentHashMap.newKeySet<String>()

        override suspend fun refresh() {
            if (capabilities == null) connect()
            // Without session resources the streamed timeline is all we have;
            // the server still restores context from its own store.
            if (capabilities?.sessionResources != true) {
                historyLoaded = true
                return
            }
            val rows = dataRows(getJson(url("api/sessions", sessionKey, "messages")))
            val messages = rows.mapNotNull { row ->
                runCatching {
                    json.decodeFromJsonElement(ProtocolMessage.serializer(), row)
                }.getOrNull()
            }
            _timeline.update { state ->
                TimelineReducer.rehydrate(state, messages, System.currentTimeMillis())
            }
            historyLoaded = true
        }

        /**
         * The transcript the agent must see this turn, as OpenAI-style rows.
         * Built from the authoritative timeline BEFORE the optimistic local
         * echo is appended. /v1/runs executes with exactly the history the
         * request carries — session_id alone scopes memory/persistence, NOT
         * transcript loading — so forgetting this field is agent amnesia.
         */
        private fun conversationHistory(): List<Pair<String, String>> =
            _timeline.value.entries
                .filterIsInstance<ChatEntry.Message>()
                .filter { it.role != Role.SYSTEM && it.text.isNotBlank() }
                .map { (if (it.role == Role.USER) "user" else "assistant") to it.text }

        override suspend fun send(text: String, attachments: List<OutgoingAttachment>) {
            if (streamJob?.isActive == true) {
                throw HermesRpcException("a turn is already streaming; stop it first")
            }
            if (capabilities == null) connect()
            attachments.firstOrNull { !it.isImage }?.let {
                throw HermesRpcException(
                    "only image attachments are supported on this surface (got ${it.mimeType})",
                )
            }
            if (!historyLoaded) {
                // A resumed conversation whose transcript never loaded must not
                // run a turn: the agent would answer with no context. Fail loud;
                // the UI offers retry — never silently start fresh.
                refresh()
            }
            val history = conversationHistory()
            _timeline.update { state ->
                state.copy(
                    running = true,
                    entries = state.entries + ChatEntry.Message(
                        id = EntryId("local-user:$sessionKey:${state.entries.size}:${text.hashCode()}"),
                        timestampMs = System.currentTimeMillis(),
                        role = Role.USER,
                        text = text,
                        attachments = attachments.map {
                            Attachment(name = it.name, mimeType = it.mimeType, url = it.dataUrl)
                        },
                    ),
                )
            }
            // Primary: session-bound streaming — the SERVER loads and persists
            // the transcript (source of truth); nothing is resent. Runs (with
            // explicit history) and completions remain fallbacks for older
            // deployments; a 404 on the session row falls back within the turn.
            if (capabilities?.sessionChatStreaming == true) {
                sendViaSessionChat(text, attachments, fallbackHistory = history)
            } else if (capabilities?.runSubmission == true && attachments.isEmpty()) {
                sendViaRun(text, history)
            } else {
                sendViaCompletions(text, attachments)
            }
        }

        // ---- session-bound streaming turn (/api/sessions/{id}/chat/stream) --

        private fun sendViaSessionChat(
            text: String,
            attachments: List<OutgoingAttachment>,
            fallbackHistory: List<Pair<String, String>>,
        ) {
            val body = buildJsonObject {
                if (attachments.isEmpty()) {
                    put("message", text)
                } else {
                    putJsonArray("message") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                        attachments.forEach { attachment ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", attachment.dataUrl) })
                            })
                        }
                    }
                }
                _config.value.model?.let { put("model", it) }
                _config.value.provider?.let { put("provider", it) }
                modelOptions()?.let { put("model_options", it) }
            }
            val call = client.newCall(
                request(url("api/sessions", sessionKey, "chat", "stream"))
                    .header("Accept", "text/event-stream")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build(),
            )
            interrupted = false
            activeCall = call
            streamJob = scope.launch(Dispatchers.IO) {
                var runId: String? = null
                try {
                    call.execute().use { response ->
                        if (response.code == 404) {
                            // Session row unknown to this surface (pre-upgrade
                            // mob- id): fall back to a runs turn with history.
                            throw SessionRowMissing()
                        }
                        if (!response.isSuccessful) {
                            throw HermesHttpException(response.code, "session chat failed: HTTP ${response.code}")
                        }
                        val source = response.body?.source()
                            ?: throw HermesRpcException("empty session chat stream")
                        reduce(WireEvent(type = "message.start", sessionId = sessionKey))
                        val buffer = StringBuilder()
                        var eventName: String? = null
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (buffer.isNotEmpty()) {
                                        runId = dispatchSessionChatEvent(eventName, buffer.toString(), runId)
                                    }
                                    buffer.setLength(0)
                                    eventName = null
                                }
                                line.startsWith(":") -> Unit // keepalive
                                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> buffer.append(line.removePrefix("data:").trim())
                            }
                        }
                        if (buffer.isNotEmpty()) {
                            runId = dispatchSessionChatEvent(eventName, buffer.toString(), runId)
                        }
                    }
                    if (_timeline.value.running) {
                        val id = runId
                        if (id != null) settleFromRunStatus(id) else finishTurn(error = null)
                    }
                } catch (t: SessionRowMissing) {
                    activeCall = null
                    if (capabilities?.runSubmission == true && attachments.isEmpty()) {
                        sendViaRun(text, fallbackHistory)
                    } else {
                        sendViaCompletions(text, attachments)
                    }
                    return@launch
                } catch (t: Throwable) {
                    if (interrupted) {
                        finishTurn(error = null)
                    } else if (_timeline.value.running) {
                        val id = runId
                        if (id != null) {
                            runCatching { settleFromRunStatus(id) }
                                .onFailure { finishTurn(error = t.message ?: "session chat stream failed") }
                        } else {
                            finishTurn(error = t.message ?: "session chat stream failed")
                        }
                    }
                } finally {
                    if (activeCall === call) activeCall = null
                    activeRunId = null
                }
            }
        }

        /** Map one session-chat SSE frame; returns the (possibly updated) run id. */
        private fun dispatchSessionChatEvent(eventName: String?, data: String, currentRunId: String?): String? {
            val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject
                ?: return currentRunId
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            when (eventName) {
                "run.started" -> {
                    val runId = str("run_id")
                    if (runId != null) {
                        activeRunId = runId
                        _turnEvents.tryEmit(TurnEvent.Started(sessionKey, runId))
                        return runId
                    }
                }
                "assistant.delta" -> str("delta")?.let { delta ->
                    reduce(WireEvent("message.delta", sessionKey, payload = buildJsonObject { put("text", delta) }))
                }
                "tool.progress" -> {
                    val tool = str("tool_name")
                    if (tool == "_thinking") {
                        reduce(WireEvent("thinking.delta", sessionKey, payload = buildJsonObject {
                            put("text", str("delta").orEmpty())
                        }))
                    } else if (tool != null) {
                        reduce(WireEvent("tool.progress", sessionKey, payload = buildJsonObject {
                            put("name", tool)
                            str("delta")?.takeIf { it.isNotBlank() }?.let { put("summary", it) }
                        }))
                    }
                }
                "tool.started" -> reduce(
                    WireEvent("tool.start", sessionKey, payload = buildJsonObject {
                        put("name", str("tool_name") ?: "tool")
                        str("preview")?.let { put("context", it) }
                    }),
                )
                "tool.completed", "tool.failed" -> reduce(
                    WireEvent("tool.complete", sessionKey, payload = buildJsonObject {
                        put("name", str("tool_name") ?: "tool")
                        if (eventName == "tool.failed") put("failed", true)
                    }),
                )
                "run.completed" -> {
                    reduce(WireEvent("message.complete", sessionKey, payload = buildJsonObject {
                        put("status", "complete")
                    }))
                    val preview = (_timeline.value.entries.lastOrNull {
                        it is ChatEntry.Message && it.role == Role.ASSISTANT
                    } as? ChatEntry.Message)?.text.orEmpty().take(160)
                    _turnEvents.tryEmit(TurnEvent.Completed(sessionKey, preview))
                    afterTurn()
                }
                "error" -> {
                    val message = str("message") ?: "session chat failed"
                    reduce(WireEvent("error", sessionKey, payload = buildJsonObject { put("error", message) }))
                    _turnEvents.tryEmit(TurnEvent.Failed(sessionKey, message))
                    afterTurn()
                }
            }
            return currentRunId
        }

        // ---- structured run turn (/v1/runs) --------------------------------

        private suspend fun sendViaRun(text: String, history: List<Pair<String, String>>) {
            val body = buildJsonObject {
                put("input", text)
                put("session_id", sessionKey)
                if (history.isNotEmpty()) {
                    putJsonArray("conversation_history") {
                        history.forEach { (role, content) ->
                            add(buildJsonObject {
                                put("role", role)
                                put("content", content)
                            })
                        }
                    }
                }
                _config.value.model?.let { put("model", it) }
                _config.value.provider?.let { put("provider", it) }
                modelOptions()?.let { put("model_options", it) }
            }
            val started = withContext(Dispatchers.IO) {
                restClient.newCall(
                    request(url("v1/runs"))
                        .post(body.toString().toRequestBody(jsonMedia)).build(),
                ).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw HermesHttpException(response.code, "run submit failed: HTTP ${response.code}")
                    }
                    json.parseToJsonElement(payload) as? JsonObject
                        ?: throw HermesRpcException("malformed run response")
                }
            }
            val runId = (started["run_id"] as? JsonPrimitive)?.contentOrNull
                ?: throw HermesRpcException("run response carried no run_id")
            activeRunId = runId
            interrupted = false
            _turnEvents.tryEmit(TurnEvent.Started(sessionKey, runId))
            reduce(WireEvent(type = "message.start", sessionId = sessionKey))

            val call = client.newCall(
                request(url("v1/runs", runId, "events"))
                    .header("Accept", "text/event-stream")
                    .get().build(),
            )
            activeCall = call
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw HermesHttpException(response.code, "run events failed: HTTP ${response.code}")
                        }
                        val source = response.body?.source()
                            ?: throw HermesRpcException("empty run stream")
                        val buffer = StringBuilder()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (buffer.isNotEmpty()) dispatchRunEvent(buffer.toString())
                                    buffer.setLength(0)
                                }
                                line.startsWith(":") -> Unit // keepalive
                                line.startsWith("data:") -> buffer.append(line.removePrefix("data:").trim())
                            }
                        }
                        if (buffer.isNotEmpty()) dispatchRunEvent(buffer.toString())
                    }
                    // Stream may close without a terminal frame (e.g. proxy drop):
                    // settle from the pollable run status rather than guessing.
                    if (_timeline.value.running) settleFromRunStatus(runId)
                } catch (t: Throwable) {
                    if (interrupted) {
                        finishTurn(error = null)
                    } else if (_timeline.value.running) {
                        runCatching { settleFromRunStatus(runId) }
                            .onFailure { finishTurn(error = t.message ?: "run stream failed") }
                    }
                } finally {
                    activeCall = null
                    activeRunId = null
                }
            }
        }

        /** Map one /v1/runs SSE frame onto the shared timeline reducer. */
        private fun dispatchRunEvent(data: String) {
            val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject ?: return
            val kind = (obj["event"] as? JsonPrimitive)?.contentOrNull ?: return
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            when (kind) {
                "message.delta" -> str("delta")?.let { delta ->
                    reduce(WireEvent("message.delta", sessionKey, payload = buildJsonObject { put("text", delta) }))
                }
                "reasoning.available" -> reduce(
                    WireEvent("reasoning.available", sessionKey, payload = buildJsonObject {
                        put("text", str("text").orEmpty())
                    }),
                )
                "tool.started" -> reduce(
                    WireEvent("tool.start", sessionKey, payload = buildJsonObject {
                        put("name", str("tool") ?: "tool")
                        str("preview")?.let { put("context", it) }
                    }),
                )
                "tool.completed" -> reduce(
                    WireEvent("tool.complete", sessionKey, payload = buildJsonObject {
                        put("name", str("tool") ?: "tool")
                        obj["duration"]?.let { put("duration_s", it) }
                        if ((obj["error"] as? JsonPrimitive)?.contentOrNull == "true") put("failed", true)
                    }),
                )
                "subagent.start" -> reduce(
                    WireEvent("tool.start", sessionKey, payload = buildJsonObject {
                        put("tool_id", str("subagent_id") ?: "subagent")
                        put("name", "subagent")
                        put("context", listOfNotNull(str("goal"), str("model")).joinToString("  ·  "))
                    }),
                )
                "subagent.complete" -> reduce(
                    WireEvent("tool.complete", sessionKey, payload = buildJsonObject {
                        put("tool_id", str("subagent_id") ?: "subagent")
                        put("name", "subagent")
                        str("summary")?.let { put("summary", it) }
                        if (str("status") == "failed") put("failed", true)
                    }),
                )
                "approval.request" -> {
                    val prompt = str("command") ?: str("description") ?: "Approve action?"
                    reduce(
                        WireEvent("approval.request", sessionKey, payload = buildJsonObject {
                            put("command", prompt)
                            obj["choices"]?.let { put("choices", it) }
                        }),
                    )
                    _turnEvents.tryEmit(TurnEvent.ApprovalRequested(sessionKey, prompt))
                }
                "approval.responded" -> _timeline.update { state ->
                    state.copy(entries = state.entries.map { entry ->
                        if (entry is ChatEntry.Approval && !entry.resolved) entry.copy(resolved = true) else entry
                    })
                }
                "run.completed" -> {
                    reduce(WireEvent("message.complete", sessionKey, payload = buildJsonObject {
                        put("text", str("output").orEmpty())
                        put("status", "complete")
                    }))
                    _turnEvents.tryEmit(TurnEvent.Completed(sessionKey, str("output").orEmpty().take(160)))
                    afterTurn()
                }
                "run.failed" -> {
                    reduce(WireEvent("error", sessionKey, payload = buildJsonObject {
                        put("error", str("error") ?: "run failed")
                    }))
                    _turnEvents.tryEmit(TurnEvent.Failed(sessionKey, str("error") ?: "run failed"))
                    afterTurn()
                }
                "run.cancelled" -> {
                    reduce(WireEvent("turn.end", sessionKey))
                    afterTurn()
                }
            }
        }

        private suspend fun settleFromRunStatus(runId: String) {
            val status = getJson(url("v1/runs", runId)) as? JsonObject
            val state = (status?.get("status") as? JsonPrimitive)?.contentOrNull
            val output = (status?.get("output") as? JsonPrimitive)?.contentOrNull
            when (state) {
                "completed" -> {
                    reduce(
                        WireEvent("message.complete", sessionKey, payload = buildJsonObject {
                            put("text", output.orEmpty())
                            put("status", "complete")
                        }),
                    )
                    _turnEvents.tryEmit(TurnEvent.Completed(sessionKey, output.orEmpty().take(160)))
                }
                "failed" -> {
                    val error = (status?.get("error") as? JsonPrimitive)?.contentOrNull ?: "run failed"
                    reduce(
                        WireEvent("error", sessionKey, payload = buildJsonObject { put("error", error) }),
                    )
                    _turnEvents.tryEmit(TurnEvent.Failed(sessionKey, error))
                }
                else -> reduce(WireEvent("turn.end", sessionKey))
            }
            afterTurn()
        }

        // ---- OpenAI-compatible completions turn ----------------------------

        private fun sendViaCompletions(text: String, attachments: List<OutgoingAttachment>) {
            val body = buildJsonObject {
                put("model", _config.value.model ?: "hermes-agent")
                _config.value.provider?.let { put("provider", it) }
                modelOptions()?.let { put("model_options", it) }
                put("stream", true)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            if (attachments.isEmpty()) {
                                put("content", text)
                            } else {
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    })
                                    attachments.forEach { attachment ->
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", attachment.dataUrl)
                                            })
                                        })
                                    }
                                }
                            }
                        },
                    )
                }
            }
            val call = client.newCall(
                request(url("v1/chat/completions"))
                    .header("Accept", "text/event-stream")
                    .header("X-Hermes-Session-Id", sessionKey)
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build(),
            )
            interrupted = false
            activeCall = call
            _turnEvents.tryEmit(TurnEvent.Started(sessionKey, runId = null))
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw HermesHttpException(
                                response.code,
                                "chat failed: HTTP ${response.code}",
                            )
                        }
                        val source = response.body?.source()
                            ?: throw HermesRpcException("empty stream")
                        reduce(WireEvent(type = "message.start", sessionId = sessionKey))
                        val buffer = StringBuilder()
                        var eventName: String? = null
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    dispatchSse(eventName, buffer.toString())
                                    buffer.setLength(0)
                                    eventName = null
                                }
                                line.startsWith(":") -> Unit // SSE comment
                                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> buffer.append(line.removePrefix("data:").trim())
                            }
                        }
                        if (buffer.isNotEmpty()) dispatchSse(eventName, buffer.toString())
                    }
                    finishTurn(error = null)
                } catch (t: Throwable) {
                    if (interrupted) {
                        // Stop pressed: the dropped connection IS the interrupt
                        // signal on this surface; settle locally without error.
                        finishTurn(error = null)
                    } else {
                        finishTurn(error = t.message ?: "stream failed")
                    }
                } finally {
                    activeCall = null
                }
            }
        }

        private fun dispatchSse(eventName: String?, data: String) {
            if (data.isEmpty() || data == "[DONE]") return
            val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return
            if (eventName == "hermes.tool.progress") {
                handleToolProgress(element)
                return
            }
            val obj = element as? JsonObject ?: return
            val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject
            val content = delta?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (!content.isNullOrEmpty()) {
                reduce(
                    WireEvent(
                        type = "message.delta",
                        sessionId = sessionKey,
                        payload = buildJsonObject { put("text", content) },
                    ),
                )
            }
        }

        private fun handleToolProgress(element: JsonElement) {
            val obj = element as? JsonObject ?: return
            val toolId = listOf("tool_id", "tool_call_id", "id").firstNotNullOfOrNull { key ->
                (obj[key] as? JsonPrimitive)?.contentOrNull
            } ?: "tool-${seenTools.size}"
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            val finished = status in setOf("completed", "complete", "finished", "done", "failed", "error")
            val type = when {
                seenTools.add(toolId) -> "tool.start"
                finished -> "tool.complete"
                else -> "tool.progress"
            }
            val payload = buildJsonObject {
                put("tool_id", toolId)
                (obj["name"] as? JsonPrimitive)?.contentOrNull?.let { put("name", it) }
                (obj["summary"] ?: obj["context"] ?: obj["text"])
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?.let { put("summary", it); put("context", it) }
                if (status == "failed" || status == "error") put("failed", true)
            }
            reduce(WireEvent(type = type, sessionId = sessionKey, payload = payload))
            // A terminal status on the same frame that introduced the tool
            // still needs the completion transition.
            if (finished && type == "tool.start") {
                reduce(WireEvent(type = "tool.complete", sessionId = sessionKey, payload = payload))
            }
        }

        private fun reduce(event: WireEvent) {
            _timeline.update { TimelineReducer.reduce(it, event, System.currentTimeMillis()) }
        }

        private fun finishTurn(error: String?) {
            if (error != null) {
                reduce(
                    WireEvent(
                        type = "error",
                        sessionId = sessionKey,
                        payload = buildJsonObject { put("error", error) },
                    ),
                )
                _turnEvents.tryEmit(TurnEvent.Failed(sessionKey, error))
            } else {
                reduce(WireEvent(type = "turn.end", sessionId = sessionKey))
                // A user-initiated stop is not a completion worth announcing.
                if (!interrupted) {
                    val preview = (_timeline.value.entries.lastOrNull {
                        it is ChatEntry.Message && it.role == Role.ASSISTANT
                    } as? ChatEntry.Message)?.text.orEmpty().take(160)
                    _turnEvents.tryEmit(TurnEvent.Completed(sessionKey, preview))
                }
            }
            afterTurn()
        }

        /** Post-terminal bookkeeping shared by both turn engines. */
        private fun afterTurn() {
            seenTools.clear()
            _sessionsChanged.tryEmit(Unit)
            // Converge with the authoritative transcript once the server settles.
            scope.launch { runCatching { refresh() } }
        }

        override suspend fun interrupt() {
            val runId = activeRunId
            if (runId != null) {
                // Structured stop; the run stream then delivers run.cancelled.
                runCatching {
                    withContext(Dispatchers.IO) {
                        restClient.newCall(
                            request(url("v1/runs", runId, "stop"))
                                .post("{}".toRequestBody(jsonMedia)).build(),
                        ).execute().close()
                    }
                }.onFailure {
                    interrupted = true
                    activeCall?.cancel()
                }
                return
            }
            // Completions path: dropping the SSE connection IS the interrupt.
            interrupted = true
            activeCall?.cancel()
            streamJob = null
        }

        override suspend fun respondApproval(entryId: EntryId, choice: String) {
            val runId = activeRunId
                ?: throw HermesRpcException("no active run is waiting for approval")
            val body = buildJsonObject { put("choice", choice) }
            withContext(Dispatchers.IO) {
                restClient.newCall(
                    request(url("v1/runs", runId, "approval"))
                        .post(body.toString().toRequestBody(jsonMedia)).build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HermesHttpException(response.code, "approval failed: HTTP ${response.code}")
                    }
                }
            }
            _timeline.update { TimelineReducer.resolveApproval(it, entryId) }
        }

        override suspend fun setModel(model: String, provider: String?) {
            // Model rides on each completion request on this surface.
            _config.update { it.copy(model = model, provider = provider) }
        }

        /** Per-request overrides: {reasoning_effort, fast} per api_server contract. */
        private fun modelOptions(): JsonObject? {
            val config = _config.value
            if (config.thinkingLevel == null && config.fastMode == null) return null
            return buildJsonObject {
                config.thinkingLevel?.let { put("reasoning_effort", it) }
                config.fastMode?.let { put("fast", it) }
            }
        }

        override suspend fun setReasoning(level: String) {
            // Sticky for this conversation; rides every request as model_options.
            // "default" clears the override so the profile's setting applies.
            _config.update { it.copy(thinkingLevel = level.takeIf { l -> l != "default" }) }
        }

        override suspend fun setFastMode(enabled: Boolean) {
            _config.update { it.copy(fastMode = enabled) }
        }

        override fun close() {
            streamJob?.cancel()
            streamJob = null
            handles.remove(sessionKey, this)
        }
    }
}
