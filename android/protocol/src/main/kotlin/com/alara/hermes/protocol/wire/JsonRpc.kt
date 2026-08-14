package com.alara.hermes.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * JSON-RPC 2.0 framing for the Hermes gateway WebSocket (`/api/ws`).
 * Frame shapes adapted from luinbytes/hermes-android (MIT); see
 * THIRD_PARTY_NOTICES.md.
 */

@Serializable
data class JsonRpcRequest(
    val id: Long,
    val method: String,
    val params: JsonElement,
    val jsonrpc: String = "2.0",
)

/** Server error object surfaced through [HermesRpcException]. */
@Serializable
data class JsonRpcError(
    val code: Int? = null,
    val message: String? = null,
    val data: JsonElement? = null,
)

/**
 * Incoming frame: either a response (`id` + `result`/`error`) or an event
 * notification (`method == "event"` with [WireEvent] params).
 */
@Serializable
data class JsonRpcFrame(
    val id: Long? = null,
    val method: String? = null,
    val params: WireEvent? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val jsonrpc: String? = null,
)

/** Gateway push event: `{type, session_id|sid, payload?}`. */
@Serializable
data class WireEvent(
    val type: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("sid") val sid: String? = null,
    val payload: JsonElement? = null,
) {
    /** Some gateway builds emit `sid` instead of `session_id`. */
    val resolvedSessionId: String? get() = sessionId ?: sid
}

class HermesRpcException(
    message: String,
    val rpcCode: Int? = null,
) : Exception(message)
