package com.alara.hermes.protocol.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Wire DTOs for the Hermes gateway REST + WebSocket surface.
 * Field names follow the gateway contract observed in
 * NousResearch/hermes-agent (tui_gateway) and cross-checked against
 * luinbytes/hermes-android (MIT). See docs/UPSTREAM.md for pinned refs.
 */

/** History message ids arrive as strings, numbers, or not at all. */
object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** A stored session row from `GET /api/profiles/sessions`. */
@Serializable
data class StoredSession(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("session_id") val sessionId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val id: String? = null,
    val title: String? = null,
    val profile: String? = null,
    val source: String? = null,
    val model: String? = null,
    val preview: String? = null,
    @SerialName("last_message") val lastMessage: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    val running: Boolean? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
) {
    /** Durable identity used for resume + REST history. */
    val durableId: String? get() = sessionId ?: id
}

@Serializable
data class StoredSessionPage(
    val sessions: List<StoredSession> = emptyList(),
    val total: Int? = null,
)

/**
 * One transcript message from `GET /api/sessions/{id}/messages` or
 * `session.resume`. `row_id` (WS projection) / `id` (REST rows) is the durable
 * messages.id — the only stable message identity the backend provides.
 */
@Serializable
data class ProtocolMessage(
    @Serializable(with = FlexibleStringSerializer::class)
    val id: String? = null,
    @SerialName("row_id") val rowId: Long? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    val text: String? = null,
    val timestamp: Double? = null,
    val name: String? = null,
    val context: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
) {
    /** Durable message identity where the backend supplies one. */
    val stableId: String? get() = rowId?.toString() ?: id
}

@Serializable
data class SessionMessagesResult(
    val messages: List<ProtocolMessage> = emptyList(),
)

@Serializable
data class InflightState(
    val user: String? = null,
    val assistant: String? = null,
    val streaming: Boolean? = null,
    val corrections: List<String> = emptyList(),
    val error: String? = null,
    val status: String? = null,
    val recoverable: Boolean? = null,
)

@Serializable
data class QueuedState(
    val user: String? = null,
)

@Serializable
data class SessionRuntimeInfo(
    val cwd: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val fast: Boolean? = null,
    val running: Boolean? = null,
    val title: String? = null,
    @SerialName("profile_name") val profileName: String? = null,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    @SerialName("desktop_contract") val desktopContract: Int? = null,
)

/** Result of `session.resume`. `session_id` is the runtime id; `session_key` durable. */
@Serializable
data class SessionResumeResult(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("session_id") val sessionId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("session_key") val sessionKey: String? = null,
    val resumed: Boolean? = null,
    val messages: List<ProtocolMessage> = emptyList(),
    val status: String? = null,
    val running: Boolean? = null,
    val inflight: InflightState? = null,
    val queued: QueuedState? = null,
    val info: SessionRuntimeInfo? = null,
)

@Serializable
data class SessionCreateResult(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("session_id") val sessionId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    val info: SessionRuntimeInfo? = null,
)

@Serializable
data class ProfileInfo(
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
)

@Serializable
data class ProfilesResult(
    val profiles: List<ProfileInfo> = emptyList(),
)

@Serializable
data class ModelOptionEntry(
    @Serializable(with = FlexibleStringSerializer::class)
    val id: String? = null,
    val name: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("is_current") val isCurrent: Boolean? = null,
) {
    val resolvedId: String? get() = id ?: model ?: name
}

@Serializable
data class ModelProviderEntry(
    val slug: String? = null,
    val name: String? = null,
    @SerialName("is_current") val isCurrent: Boolean? = null,
    val authenticated: Boolean? = null,
    val models: List<ModelOptionEntry> = emptyList(),
)

@Serializable
data class ModelOptionsResult(
    val providers: List<ModelProviderEntry> = emptyList(),
    val models: List<ModelOptionEntry> = emptyList(),
    val current: String? = null,
)

@Serializable
data class WsTicketResult(
    val ticket: String? = null,
)

@Serializable
data class AuthProviderInfo(
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("supports_password") val supportsPassword: Boolean? = null,
)

@Serializable
data class AuthProvidersResult(
    val providers: List<AuthProviderInfo> = emptyList(),
)
