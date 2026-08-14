package com.alara.hermes.protocol

import kotlinx.serialization.Serializable

/** A Hermes profile: an independent agent identity on the gateway. */
@Serializable
data class HermesProfile(
    val id: String,
    val displayName: String = id,
    val isDefault: Boolean = false,
)

/** Where a session's turns have historically originated. */
enum class SessionSource { DESKTOP, ANDROID, DISCORD, MATRIX, CLI, OTHER }

@Serializable
data class SessionSummary(
    val key: String,
    val profileId: String,
    val title: String,
    val preview: String = "",
    val updatedAtMs: Long = 0,
    val running: Boolean = false,
    val pinned: Boolean = false,
    val source: String? = null,
    val model: String? = null,
)

/** Stable identity for a transcript entry; used for dedup across reconnects. */
@Serializable
data class EntryId(val value: String)

enum class Role { USER, ASSISTANT, SYSTEM }

enum class ToolRunStatus { RUNNING, SUCCEEDED, FAILED, AWAITING_APPROVAL }

/** One rendered row of a conversation, normalized from gateway history/stream events. */
@Serializable
sealed interface ChatEntry {
    val id: EntryId
    val timestampMs: Long

    @Serializable
    data class Message(
        override val id: EntryId,
        override val timestampMs: Long,
        val role: Role,
        val text: String,
        val streaming: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
    ) : ChatEntry

    @Serializable
    data class Reasoning(
        override val id: EntryId,
        override val timestampMs: Long,
        val summary: String,
        val active: Boolean,
    ) : ChatEntry

    @Serializable
    data class ToolRun(
        override val id: EntryId,
        override val timestampMs: Long,
        val tool: String,
        val label: String,
        val detail: String = "",
        val status: ToolRunStatus,
    ) : ChatEntry

    @Serializable
    data class Approval(
        override val id: EntryId,
        override val timestampMs: Long,
        val requestId: String,
        val prompt: String,
        val options: List<String>,
        val resolved: Boolean = false,
    ) : ChatEntry

    @Serializable
    data class SystemNote(
        override val id: EntryId,
        override val timestampMs: Long,
        val text: String,
    ) : ChatEntry
}

@Serializable
data class Attachment(
    val name: String,
    val mimeType: String,
    val url: String? = null,
    val sizeBytes: Long? = null,
)

/** Session-level agent configuration surfaced in the chat header / bottom sheet. */
@Serializable
data class SessionConfig(
    val model: String? = null,
    val provider: String? = null,
    val thinkingLevel: String? = null,
    val fastMode: Boolean? = null,
)

@Serializable
data class ModelOption(
    val id: String,
    val provider: String? = null,
    val displayName: String = id,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverVersion: String? = null) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
