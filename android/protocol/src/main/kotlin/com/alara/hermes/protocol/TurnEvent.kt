package com.alara.hermes.protocol

/**
 * Coarse lifecycle signals for agent turns, consumed by the notification
 * layer. These fire in addition to (not instead of) the per-session timeline.
 */
sealed interface TurnEvent {
    val sessionKey: String

    /** A turn this client submitted has been accepted by the backend. */
    data class Started(
        override val sessionKey: String,
        /** Structured-run id when the turn runs via /v1/runs; null otherwise. */
        val runId: String?,
    ) : TurnEvent

    data class Completed(
        override val sessionKey: String,
        /** Short preview of the final reply; may be empty. */
        val preview: String,
    ) : TurnEvent

    data class Failed(
        override val sessionKey: String,
        val error: String,
    ) : TurnEvent

    data class ApprovalRequested(
        override val sessionKey: String,
        val prompt: String,
    ) : TurnEvent
}
