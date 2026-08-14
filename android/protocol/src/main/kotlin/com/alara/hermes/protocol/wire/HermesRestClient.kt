package com.alara.hermes.protocol.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** How this client authenticates against the gateway. */
sealed interface HermesCredential {
    /** Static gateway token: `Authorization: Bearer` on REST, `?token=` on WS. */
    data class Token(val token: String) : HermesCredential

    /** Dashboard session cookies (`hermes_session_at` et al.); WS uses minted tickets. */
    data class DashboardSession(val cookieHeader: String) : HermesCredential
}

class HermesHttpException(val code: Int, message: String) : Exception(message)

/**
 * Typed access to the Hermes gateway REST surface (paths under `/api/`).
 * Endpoint paths follow the hermes-agent web/dashboard server contract; see
 * docs/UPSTREAM.md for the pinned upstream references.
 */
class HermesRestClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: HttpUrl,
    @Volatile var credential: HermesCredential,
) {
    private val jsonMedia = "application/json".toMediaType()

    private fun url(vararg segments: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder()
        segments.forEach { builder.addPathSegments(it) }
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun Request.Builder.authenticated(): Request.Builder = apply {
        when (val cred = credential) {
            is HermesCredential.Token -> {
                header("Authorization", "Bearer ${cred.token}")
                header("X-Hermes-Session-Token", cred.token)
            }
            is HermesCredential.DashboardSession -> header("Cookie", cred.cookieHeader)
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HermesHttpException(response.code, "HTTP ${response.code} ${request.url.encodedPath}")
            }
            body
        }
    }

    private suspend fun getRaw(url: HttpUrl): String =
        execute(Request.Builder().url(url).authenticated().get().build())

    private suspend fun postRaw(url: HttpUrl, body: JsonElement): String =
        execute(
            Request.Builder().url(url).authenticated()
                .post(body.toString().toRequestBody(jsonMedia)).build(),
        )

    suspend fun status(): JsonElement =
        json.parseToJsonElement(getRaw(url("api/status")))

    /**
     * Stored sessions — the rich REST list (`web_routers/sessions.py`).
     * `limit` is server-capped at 100; pinned rows are back-filled past the
     * limit by the server, so don't re-slice the result.
     */
    suspend fun listSessions(
        profile: String?,
        limit: Int = 100,
        offset: Int = 0,
        excludeSources: String? = "cron,kanban,tool",
        archived: String = "exclude",
    ): List<StoredSession> {
        val raw = getRaw(
            url(
                "api/sessions",
                query = mapOf(
                    "limit" to limit.coerceAtMost(100).toString(),
                    "offset" to offset.toString(),
                    "order" to "recent",
                    "archived" to archived,
                    "profile" to profile,
                    "exclude_sources" to excludeSources,
                ),
            ),
        )
        return parseSessionRows(json.parseToJsonElement(raw))
    }

    /** Rename / pin / archive via `PATCH /api/sessions/{id}`. */
    suspend fun patchSession(
        sessionId: String,
        profile: String?,
        title: String? = null,
        pinned: Boolean? = null,
        archived: Boolean? = null,
    ) {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            pinned?.let { put("pinned", it) }
            archived?.let { put("archived", it) }
        }
        val target = url("api/sessions", sessionId, query = mapOf("profile" to profile))
        execute(
            Request.Builder().url(target).authenticated()
                .patch(body.toString().toRequestBody(jsonMedia)).build(),
        )
    }

    private fun parseSessionRows(element: JsonElement): List<StoredSession> {
        val array = when (element) {
            is JsonArray -> element
            is JsonObject ->
                (element["sessions"] ?: element["data"] ?: element["items"]) as? JsonArray
                    ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { row ->
            runCatching { json.decodeFromJsonElement(StoredSession.serializer(), row) }.getOrNull()
        }
    }

    suspend fun sessionMessages(sessionId: String, profile: String?): List<ProtocolMessage> {
        val raw = getRaw(
            url("api/sessions", sessionId, "messages", query = mapOf("profile" to profile)),
        )
        val element = json.parseToJsonElement(raw)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["messages"] ?: element["data"]) as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { row ->
            runCatching { json.decodeFromJsonElement(ProtocolMessage.serializer(), row) }.getOrNull()
        }
    }

    suspend fun searchSessions(queryText: String, profile: String?, limit: Int = 50): List<StoredSession> {
        val raw = getRaw(
            url(
                "api/sessions/search",
                query = mapOf("q" to queryText, "limit" to limit.toString(), "profile" to profile),
            ),
        )
        return parseSessionRows(json.parseToJsonElement(raw))
    }

    suspend fun listProfiles(): List<ProfileInfo> {
        val raw = getRaw(url("api/profiles"))
        val element = json.parseToJsonElement(raw)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["profiles"] ?: element["data"]) as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { row ->
            when (row) {
                is JsonObject ->
                    runCatching { json.decodeFromJsonElement(ProfileInfo.serializer(), row) }.getOrNull()
                else -> ProfileInfo(name = row.toString().trim('"'))
            }
        }
    }

    /** Mint a single-use, short-TTL WebSocket ticket using the current credential. */
    suspend fun mintWsTicket(): String {
        val raw = postRaw(url("api/auth", "ws-ticket"), buildJsonObject { })
        val result = json.decodeFromString(WsTicketResult.serializer(), raw)
        return result.ticket ?: throw HermesRpcException("gateway returned no ws ticket")
    }

    companion object {
        fun parseBaseUrl(raw: String): HttpUrl? {
            val candidate = if (raw.contains("://")) raw else "http://$raw"
            return candidate.trimEnd('/').toHttpUrlOrNull()
        }
    }
}

/**
 * Dashboard password login: exchanges username/password for the
 * `hermes_session_*` cookie set. Cookie names may carry `__Host-`/`__Secure-`
 * prefixes on HTTPS binds.
 */
class DashboardAuthClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: HttpUrl,
) {
    private val jsonMedia = "application/json".toMediaType()

    suspend fun listProviders(): List<AuthProviderInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/auth/providers").build())
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string().orEmpty()
            runCatching {
                json.decodeFromString(AuthProvidersResult.serializer(), body).providers
            }.getOrDefault(emptyList())
        }
    }

    suspend fun passwordLogin(provider: String, username: String, password: String): HermesCredential.DashboardSession =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("provider", provider)
                put("username", username)
                put("password", password)
                put("next", "")
            }
            val request = Request.Builder()
                .url(baseUrl.newBuilder().addPathSegments("auth/password-login").build())
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            val noRedirects = client.newBuilder().followRedirects(false).build()
            noRedirects.newCall(request).execute().use { response ->
                val cookies = response.headers.values("Set-Cookie")
                val sessionCookies = cookies.mapNotNull(::extractSessionCookie)
                if (response.code >= 400 || sessionCookies.none { it.first.endsWith("hermes_session_at") }) {
                    throw HermesHttpException(response.code, "dashboard login failed")
                }
                HermesCredential.DashboardSession(
                    sessionCookies.joinToString("; ") { (name, value) -> "$name=$value" },
                )
            }
        }

    private fun extractSessionCookie(header: String): Pair<String, String>? {
        val match = SESSION_COOKIE.find(header) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    companion object {
        private val SESSION_COOKIE =
            Regex("((?:__Host-|__Secure-)?hermes_session_(?:at|rt|provider))=([^;,\\s]+)")
    }
}
