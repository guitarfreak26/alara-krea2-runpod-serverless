package com.alara.hermes.protocol

import kotlinx.serialization.Serializable

/** A Hermes profile: an independent agent identity on the gateway. */
@Serializable
data class HermesProfile(
    val id: String,
    val displayName: String = id,
    val isDefault: Boolean = false,
    /** Configured model, when /v1/profiles advertises it. */
    val model: String? = null,
    /** Role/description line for rosters (Bot Mode). */
    val description: String? = null,
    /** Server-synced avatar metadata (ALARA plugin); null → geometric default. */
    val avatarShape: String? = null,
    val avatarColor: String? = null,
    val avatarUrl: String? = null,
    /** Optional roster summary so clients need not fan out per-profile queries. */
    val preview: String? = null,
    val lastActiveMs: Long? = null,
    val busy: Boolean? = null,
    /** Public @handle (presentation metadata; never derived from the id). */
    val handle: String? = null,
    /** Server-assigned roster group (e.g. "Seoyeon Team"). */
    val group: String? = null,
)

/** One member of a server-defined Bot Mode room. */
data class RoomMember(
    val profileId: String,
    val displayName: String = profileId,
    /** "manager" | "specialist" | future roles; server-controlled. */
    val role: String = "specialist",
    val avatarShape: String? = null,
    val avatarColor: String? = null,
    val avatarUrl: String? = null,
    /** Public @handle; falls back to displayName for mention insertion. */
    val handle: String? = null,
) {
    val mentionLabel: String get() = handle ?: displayName
}

/**
 * A server-backed Bot Mode room (see docs/BOT_ROOMS_API.md). Existence,
 * membership, roles and permissions are entirely server-controlled; the
 * client renders and operates, never administers.
 */
data class BotRoom(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val managerProfileId: String,
    val apiPrefix: String? = null,
    val members: List<RoomMember> = emptyList(),
    /** Persistent room transcript session id. */
    val sessionKey: String,
    val preview: String? = null,
    val lastActiveMs: Long? = null,
    val busy: Boolean? = null,
    val unread: Boolean? = null,
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
    val archived: Boolean = false,
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

/** A file staged on the device for the next send. */
data class OutgoingAttachment(
    val name: String,
    val mimeType: String,
    val dataBase64: String,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val dataUrl: String get() = "data:$mimeType;base64,$dataBase64"
}

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
    /** True when this is the profile's currently configured model on the server. */
    val isCurrent: Boolean = false,
)

@Serializable
data class SkillInfo(
    val name: String,
    val description: String = "",
    val category: String = "",
    val disabled: Boolean = false,
)

@Serializable
data class AutomationInfo(
    val id: String,
    val name: String,
    val schedule: String = "",
    val prompt: String = "",
    val enabled: Boolean = true,
    val paused: Boolean = false,
    val lastRunAtMs: Long? = null,
    val lastStatus: String? = null,
)

@Serializable
data class UsageWindow(
    val label: String,
    /** 0-100; null = provider didn't report. */
    val usedPercent: Double? = null,
    val resetAtMs: Long? = null,
    val detail: String? = null,
)

@Serializable
data class AccountUsage(
    val provider: String,
    val plan: String? = null,
    val windows: List<UsageWindow> = emptyList(),
    val details: List<String> = emptyList(),
    val unavailableReason: String? = null,
)

@Serializable
data class TokenTotals(
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double?,
    val sessionCount: Int,
)

@Serializable
data class UsageSummary(
    /** Sessions active in the last 24h. */
    val today: TokenTotals,
    /** Everything the session list returns. */
    val allListed: TokenTotals,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverVersion: String? = null) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
