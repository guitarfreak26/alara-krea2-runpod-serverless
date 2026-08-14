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
 * Contract used here:
 * - validate:  GET /v1/capabilities            (Authorization: Bearer)
 * - sessions:  GET /api/sessions               -> {data:[...]}
 * - history:   GET /api/sessions/{id}/messages -> {data:[...]}
 * - delete:    DELETE /api/sessions/{id}
 * - models:    GET /v1/models                  -> {data:[{id}]}
 * - chat:      POST /v1/chat/completions  {model, messages, stream:true}
 *              + header X-Hermes-Session-Id binding the Hermes session;
 *              streaming arrives as SSE deltas plus `hermes.tool.progress`
 *              tool events; dropping the connection interrupts the turn.
 *
 * This surface has no profiles, rename, approvals, or per-session
 * reasoning/fast config — those are feature-gated off.
 */
class ApiServerGateway(
    private val baseUrl: HttpUrl,
    private val token: String,
    private val scope: CoroutineScope,
    private val json: Json = HermesLiveGateway.defaultJson,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : HermesGateway {

    private val jsonMedia = "application/json".toMediaType()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection

    override val features: GatewayFeatures = GatewayFeatures.API_SERVER

    private val _sessionsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val sessionsChanged: SharedFlow<Unit> = _sessionsChanged

    private val handles = ConcurrentHashMap<String, ApiSessionHandle>()

    private fun url(vararg segments: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        segments.forEach { builder.addPathSegments(it) }
        return builder.build()
    }

    private fun request(target: HttpUrl): Request.Builder =
        Request.Builder().url(target).header("Authorization", "Bearer $token")

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
        val caps = getJson(url("v1/capabilities"))
        val version = (caps as? JsonObject)?.get("version")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
        _connection.value = ConnectionState.Connected(version)
        version ?: "connected"
    }

    override suspend fun listProfiles(): List<HermesProfile> = emptyList()

    private fun dataRows(element: JsonElement): JsonArray = when (element) {
        is JsonArray -> element
        is JsonObject -> (element["data"] ?: element["sessions"] ?: element["messages"]) as? JsonArray
            ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }

    override suspend fun listSessions(profileId: String?): List<SessionSummary> {
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
        throw HermesRpcException("rename is not supported by the API server surface")
    }

    override suspend fun deleteSession(sessionKey: String) {
        handles.remove(sessionKey)?.close()
        withContext(Dispatchers.IO) {
            client.newCall(request(url("api/sessions", sessionKey)).delete().build())
                .execute().use { response ->
                    // 404 = already gone; that's success for a delete.
                    if (!response.isSuccessful && response.code != 404) {
                        throw HermesHttpException(response.code, "delete failed: HTTP ${response.code}")
                    }
                }
        }
        _sessionsChanged.tryEmit(Unit)
    }

    inner class ApiSessionHandle(override val sessionKey: String) : SessionHandle {
        override val profileId: String = "default"

        private val _timeline = MutableStateFlow(TimelineState(sessionKey))
        override val timeline: StateFlow<TimelineState> = _timeline

        private val _config = MutableStateFlow(SessionConfig())
        override val config: StateFlow<SessionConfig> = _config

        private var streamJob: Job? = null
        @Volatile private var activeCall: okhttp3.Call? = null
        @Volatile private var interrupted = false
        private val seenTools = ConcurrentHashMap.newKeySet<String>()

        override suspend fun refresh() {
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

        override suspend fun send(text: String) {
            if (streamJob?.isActive == true) {
                throw HermesRpcException("a turn is already streaming; stop it first")
            }
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
            val body = buildJsonObject {
                put("model", _config.value.model ?: "hermes-agent")
                put("stream", true)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", text)
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
            val job = scope.launch(Dispatchers.IO) {
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
            streamJob = job
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
            } else {
                reduce(WireEvent(type = "turn.end", sessionId = sessionKey))
            }
            seenTools.clear()
            _sessionsChanged.tryEmit(Unit)
            // Converge with the authoritative transcript once the server settles.
            scope.launch { runCatching { refresh() } }
        }

        override suspend fun interrupt() {
            // No interrupt endpoint on this surface: dropping the SSE
            // connection is the documented interrupt signal.
            interrupted = true
            activeCall?.cancel()
            streamJob = null
        }

        override suspend fun respondApproval(entryId: EntryId, choice: String) {
            throw HermesRpcException("approvals are not delivered on the API server surface")
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
