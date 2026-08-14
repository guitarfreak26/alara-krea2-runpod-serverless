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

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection

    /** What this deployment advertised via /v1/capabilities. */
    data class ServerCapabilities(
        val sessionResources: Boolean,
        val sessionUpdate: Boolean,
        val runSubmission: Boolean = false,
        val model: String? = null,
    )

    @Volatile private var capabilities: ServerCapabilities? = null

    override val features: GatewayFeatures
        get() = GatewayFeatures.API_SERVER.copy(
            rename = capabilities?.sessionUpdate == true,
            // Structured approvals exist only on the /v1/runs stream.
            approvals = capabilities?.runSubmission == true,
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
        client.newCall(request(target).get().build()).execute().use { response ->
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
            model = (caps?.get("model") as? JsonPrimitive)?.contentOrNull,
        )
        capabilities = parsed
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

    override suspend fun listModels(sessionKey: String?): List<ModelOption> = runCatching {
        dataRows(getJson(url("v1/models"))).mapNotNull { row ->
            val id = (row as? JsonObject)?.get("id")
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
            ModelOption(id = id, displayName = id)
        }
    }.getOrDefault(emptyList())

    override suspend fun openSession(sessionKey: String?, profileId: String?): SessionHandle {
        // New sessions use a client-generated durable id carried on every
        // completion request, so REST history and chat share one identity.
        val key = sessionKey ?: "mob-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val handle = handles.getOrPut(key) { ApiSessionHandle(key) }
        if (sessionKey != null) {
            scope.launch { runCatching { handle.refresh() } }
        }
        return handle
    }

    override suspend fun renameSession(sessionKey: String, title: String) {
        if (capabilities?.sessionUpdate != true) {
            throw HermesRpcException("this server does not advertise session rename")
        }
        val body = buildJsonObject { put("title", title) }
        withContext(Dispatchers.IO) {
            client.newCall(
                request(url("api/sessions", sessionKey))
                    .patch(body.toString().toRequestBody(jsonMedia)).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HermesHttpException(response.code, "rename failed: HTTP ${response.code}")
                }
            }
        }
        _sessionsChanged.tryEmit(Unit)
    }

    override suspend fun deleteSession(sessionKey: String) {
        handles.remove(sessionKey)?.close()
        if (capabilities?.sessionResources == true) {
            withContext(Dispatchers.IO) {
                client.newCall(request(url("api/sessions", sessionKey)).delete().build())
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

    inner class ApiSessionHandle(override val sessionKey: String) : SessionHandle {
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
            // Without session resources the streamed timeline is all we have.
            if (capabilities?.sessionResources != true) return
            val rows = dataRows(getJson(url("api/sessions", sessionKey, "messages")))
            val messages = rows.mapNotNull { row ->
                runCatching {
                    json.decodeFromJsonElement(ProtocolMessage.serializer(), row)
                }.getOrNull()
            }
            _timeline.update { state ->
                TimelineReducer.rehydrate(state, messages, System.currentTimeMillis())
            }
        }

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
            // /v1/runs gives structured events (tools, approvals) but takes
            // text-only input; image-bearing turns ride chat completions.
            if (capabilities?.runSubmission == true && attachments.isEmpty()) {
                sendViaRun(text)
            } else {
                sendViaCompletions(text, attachments)
            }
        }

        // ---- structured run turn (/v1/runs) --------------------------------

        private suspend fun sendViaRun(text: String) {
            val body = buildJsonObject {
                put("input", text)
                put("session_id", sessionKey)
                _config.value.model?.let { put("model", it) }
            }
            val started = withContext(Dispatchers.IO) {
                client.newCall(
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
                        client.newCall(
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
                client.newCall(
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

        override suspend fun setReasoning(level: String) {
            throw HermesRpcException("reasoning control is not supported by the API server surface")
        }

        override suspend fun setFastMode(enabled: Boolean) {
            throw HermesRpcException("fast mode is not supported by the API server surface")
        }

        override fun close() {
            streamJob?.cancel()
            streamJob = null
            handles.remove(sessionKey, this)
        }
    }
}
