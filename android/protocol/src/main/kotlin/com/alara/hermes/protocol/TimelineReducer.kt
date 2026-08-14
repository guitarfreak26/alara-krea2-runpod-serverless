package com.alara.hermes.protocol

import com.alara.hermes.protocol.wire.ProtocolMessage
import com.alara.hermes.protocol.wire.WireEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure reducer that folds gateway history + streamed events into an ordered
 * transcript. No IO, no Android deps — the whole cross-device sync story is
 * testable right here.
 *
 * The gateway does not provide stable message ids or sequence numbers for
 * chat history (upstream gap), so identity is synthesized:
 * history rows are keyed by ordinal, streamed assistant turns by a local
 * generation counter. On resume/reconnect the authoritative history replaces
 * local rows wholesale (rehydrate, don't replay).
 */
data class TimelineState(
    val sessionKey: String,
    val entries: List<ChatEntry> = emptyList(),
    val generation: Long = 0,
    val streamingAssistantId: EntryId? = null,
    val activeReasoningId: EntryId? = null,
    val running: Boolean = false,
    val statusText: String? = null,
)

object TimelineReducer {

    // ---- history -----------------------------------------------------------

    /** Replace local entries with authoritative history from REST/resume. */
    fun rehydrate(state: TimelineState, messages: List<ProtocolMessage>, nowMs: Long): TimelineState {
        val entries = messages.mapIndexedNotNull { index, message ->
            historyEntry(state.sessionKey, index, message, nowMs)
        }
        return state.copy(
            entries = entries,
            generation = state.generation + 1,
            streamingAssistantId = null,
            activeReasoningId = null,
        )
    }

    private fun historyEntry(
        sessionKey: String,
        ordinal: Int,
        message: ProtocolMessage,
        nowMs: Long,
    ): ChatEntry? {
        if (message.displayKind == "hidden") return null
        // row_id is the durable messages.id — the only stable identity the
        // backend provides; ordinal is the fallback for legacy rows.
        val identity = message.stableId ?: "ord$ordinal"
        val timestamp = message.timestamp?.let { (it * 1000).toLong() } ?: nowMs
        if (message.role?.lowercase() == "tool") {
            val name = message.name ?: message.toolName ?: "tool"
            return ChatEntry.ToolRun(
                id = EntryId("history-tool:$sessionKey:$identity"),
                timestampMs = timestamp,
                tool = name,
                label = name.replaceFirstChar { it.uppercase() },
                detail = message.context.orEmpty(),
                status = ToolRunStatus.SUCCEEDED,
            )
        }
        val role = when (message.role?.lowercase()) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> Role.SYSTEM
        }
        val text = extractText(message).trim()
        if (text.isEmpty()) return null
        if (looksLikeToolResult(text)) return null
        return ChatEntry.Message(
            id = EntryId("history:$sessionKey:$identity"),
            timestampMs = timestamp,
            role = role,
            text = text,
        )
    }

    /**
     * History `content` arrives as a plain string, an object, or an array of
     * typed parts ({type:"text"|"output_text", text}). Normalize all of them.
     */
    fun extractText(message: ProtocolMessage): String {
        message.text?.takeIf { it.isNotBlank() }?.let { return it }
        return extractContentText(message.content)
    }

    private fun extractContentText(content: JsonElement?): String = when (content) {
        null -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("\n") { part -> extractContentText(part) }
            .trim('\n')
        is JsonObject -> {
            val type = (content["type"] as? JsonPrimitive)?.contentOrNull
            when {
                type == "image" || type == "input_image" -> ""
                else -> (content["text"] as? JsonPrimitive)?.contentOrNull
                    ?: extractContentText(content["content"])
            }
        }
    }

    /** Tool results occasionally leak into message content on some backends. */
    fun looksLikeToolResult(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("<untrusted_tool_result") ||
            (trimmed.startsWith("{") && trimmed.contains("\"tool_call_id\""))
    }

    // ---- streaming ---------------------------------------------------------

    fun reduce(state: TimelineState, event: WireEvent, nowMs: Long): TimelineState {
        val payload = event.payload as? JsonObject
        return when (event.type) {
            "message.start" -> startAssistant(state, nowMs)
            "message.delta" -> appendDelta(state, payload.str("text"), nowMs)
            "message.interim" -> sealInterim(state, payload.str("text"), payload.bool("already_streamed"), nowMs)
            "message.complete" -> completeAssistant(
                state,
                payload.str("rendered")?.takeIf { it.isNotBlank() } ?: payload.str("text"),
                nowMs,
            )
            "reasoning.delta", "thinking.delta" -> appendReasoning(state, payload.str("text"), nowMs)
            "reasoning.available" -> replaceReasoning(state, payload.str("text"), nowMs)
            "status.update" -> state.copy(statusText = payload.str("text"), running = true)
            "tool.start" -> toolStart(state, payload, nowMs)
            "tool.progress" -> toolProgress(state, payload)
            "tool.complete" -> toolComplete(state, payload)
            "approval.request" -> approvalRequest(state, payload, nowMs)
            "clarify.request" -> clarifyRequest(state, payload, nowMs)
            "turn.end" -> finishTurn(state)
            "error" -> errorEvent(state, payload, nowMs)
            else -> state // unknown events are tolerated by contract
        }
    }

    fun isTerminalEvent(type: String): Boolean =
        type == "message.complete" || type == "turn.end" || type == "turn.error" || type == "error"

    private fun assistantId(state: TimelineState, generation: Long) =
        EntryId("assistant:${state.sessionKey}:$generation")

    private fun startAssistant(state: TimelineState, nowMs: Long): TimelineState {
        if (state.streamingAssistantId != null) return state.copy(running = true)
        val generation = state.generation + 1
        val id = assistantId(state, generation)
        val entry = ChatEntry.Message(
            id = id,
            timestampMs = nowMs,
            role = Role.ASSISTANT,
            text = "",
            streaming = true,
        )
        return state.copy(
            entries = state.entries + entry,
            generation = generation,
            streamingAssistantId = id,
            running = true,
        )
    }

    private fun appendDelta(state: TimelineState, text: String?, nowMs: Long): TimelineState {
        if (text.isNullOrEmpty()) return state
        val target = state.streamingAssistantId
            ?: return appendDelta(startAssistant(state, nowMs), text, nowMs)
        return state.copy(
            running = true,
            entries = state.entries.map { entry ->
                if (entry.id == target && entry is ChatEntry.Message) {
                    entry.copy(text = entry.text + text)
                } else {
                    entry
                }
            },
        )
    }

    /** Interim seals the current bubble; the next delta opens a fresh one. */
    private fun sealInterim(
        state: TimelineState,
        text: String?,
        alreadyStreamed: Boolean?,
        nowMs: Long,
    ): TimelineState {
        val withText = if (alreadyStreamed == true || text.isNullOrEmpty()) {
            state
        } else {
            appendReplacingOrDelta(state, text, nowMs)
        }
        return withText.copy(
            streamingAssistantId = null,
            entries = withText.entries.map { entry ->
                if (entry.id == withText.streamingAssistantId && entry is ChatEntry.Message) {
                    entry.copy(streaming = false)
                } else {
                    entry
                }
            },
        )
    }

    private fun appendReplacingOrDelta(state: TimelineState, text: String, nowMs: Long): TimelineState {
        val target = state.streamingAssistantId ?: return appendDelta(state, text, nowMs)
        return state.copy(
            entries = state.entries.map { entry ->
                if (entry.id == target && entry is ChatEntry.Message) {
                    if (entry.text.isEmpty()) entry.copy(text = text) else entry.copy(text = entry.text + text)
                } else {
                    entry
                }
            },
        )
    }

    private fun completeAssistant(state: TimelineState, finalText: String?, nowMs: Long): TimelineState {
        val target = state.streamingAssistantId
        val entries = when {
            target != null -> state.entries.map { entry ->
                if (entry.id == target && entry is ChatEntry.Message) {
                    val text = when {
                        finalText.isNullOrBlank() -> entry.text
                        // Trust the final text when it extends/replaces what streamed.
                        finalText.length >= entry.text.length -> finalText
                        else -> entry.text
                    }
                    entry.copy(text = text, streaming = false)
                } else {
                    entry
                }
            }
            !finalText.isNullOrBlank() -> {
                val generation = state.generation + 1
                state.entries + ChatEntry.Message(
                    id = assistantId(state, generation),
                    timestampMs = nowMs,
                    role = Role.ASSISTANT,
                    text = finalText,
                )
            }
            else -> state.entries
        }
        return state.copy(
            entries = entries.filterNot { it is ChatEntry.Approval && !it.resolved },
            streamingAssistantId = null,
            activeReasoningId = null,
            running = false,
            statusText = null,
        )
    }

    private fun appendReasoning(state: TimelineState, text: String?, nowMs: Long): TimelineState {
        if (text.isNullOrEmpty()) return state.copy(running = true)
        val active = state.activeReasoningId
        if (active != null) {
            return state.copy(
                running = true,
                entries = state.entries.map { entry ->
                    if (entry.id == active && entry is ChatEntry.Reasoning) {
                        entry.copy(summary = entry.summary + text)
                    } else {
                        entry
                    }
                },
            )
        }
        val id = EntryId("reasoning:${state.sessionKey}:${state.generation}:${state.entries.size}")
        return state.copy(
            running = true,
            activeReasoningId = id,
            entries = state.entries + ChatEntry.Reasoning(
                id = id,
                timestampMs = nowMs,
                summary = text,
                active = true,
            ),
        )
    }

    private fun replaceReasoning(state: TimelineState, text: String?, nowMs: Long): TimelineState {
        if (text.isNullOrEmpty()) return state
        val active = state.activeReasoningId ?: return appendReasoning(state, text, nowMs)
        return state.copy(
            entries = state.entries.map { entry ->
                if (entry.id == active && entry is ChatEntry.Reasoning) {
                    entry.copy(summary = text, active = false)
                } else {
                    entry
                }
            },
            activeReasoningId = null,
        )
    }

    private fun toolId(state: TimelineState, payload: JsonObject?): String {
        val wireId = payload.str("tool_id") ?: payload.str("tool_call_id") ?: payload.str("id")
        return wireId ?: "anon:${payload.str("name").orEmpty()}"
    }

    private fun toolStart(state: TimelineState, payload: JsonObject?, nowMs: Long): TimelineState {
        val id = EntryId("tool:${state.sessionKey}:${toolId(state, payload)}:${state.entries.size}")
        val name = payload.str("name") ?: "tool"
        val context = payload.str("context") ?: payload.str("args_text") ?: ""
        return state.copy(
            running = true,
            entries = state.entries + ChatEntry.ToolRun(
                id = id,
                timestampMs = nowMs,
                tool = name,
                label = toolLabel(name, context),
                detail = context,
                status = ToolRunStatus.RUNNING,
            ),
        )
    }

    private fun findToolEntry(state: TimelineState, payload: JsonObject?): EntryId? {
        val wireId = payload.str("tool_id") ?: payload.str("tool_call_id") ?: payload.str("id")
        val name = payload.str("name")
        // Prefer the server tool id; else the most recent running tool of that name.
        val byId = wireId?.let { id ->
            state.entries.lastOrNull { it is ChatEntry.ToolRun && it.id.value.contains(":$id:") }
        }
        val match = byId ?: state.entries.lastOrNull {
            it is ChatEntry.ToolRun && it.status == ToolRunStatus.RUNNING &&
                (name == null || it.tool == name)
        }
        return match?.id
    }

    private fun toolProgress(state: TimelineState, payload: JsonObject?): TimelineState {
        val target = findToolEntry(state, payload) ?: return state
        val progress = payload.str("summary") ?: payload.str("text") ?: return state
        return state.copy(
            entries = state.entries.map { entry ->
                if (entry.id == target && entry is ChatEntry.ToolRun) entry.copy(detail = progress) else entry
            },
        )
    }

    private fun toolComplete(state: TimelineState, payload: JsonObject?): TimelineState {
        val target = findToolEntry(state, payload) ?: return state
        val failed = payload.bool("failed") == true
        val summary = payload.str("summary") ?: payload.str("result_text")
        val duration = payload?.get("duration_s")?.let { (it as? JsonPrimitive)?.doubleOrNull }
        return state.copy(
            entries = state.entries.map { entry ->
                if (entry.id == target && entry is ChatEntry.ToolRun) {
                    entry.copy(
                        status = if (failed) ToolRunStatus.FAILED else ToolRunStatus.SUCCEEDED,
                        detail = buildString {
                            append(summary ?: entry.detail)
                            duration?.let { append("  ·  ${"%.1f".format(it)}s") }
                        },
                    )
                } else {
                    entry
                }
            },
        )
    }

    private fun approvalRequest(state: TimelineState, payload: JsonObject?, nowMs: Long): TimelineState {
        val command = payload.str("command") ?: payload.str("description") ?: "Approve action?"
        val choices = (payload?.get("choices") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("once", "session", "deny")
        val id = EntryId("approval:${state.sessionKey}:${state.entries.size}")
        return state.copy(
            running = true,
            entries = state.entries + ChatEntry.Approval(
                id = id,
                timestampMs = nowMs,
                // Approvals are session-keyed on the wire (no request id).
                requestId = "",
                prompt = command,
                options = choices,
            ),
        )
    }

    private fun clarifyRequest(state: TimelineState, payload: JsonObject?, nowMs: Long): TimelineState {
        val question = payload.str("question") ?: "The agent needs clarification."
        val requestId = payload.str("request_id") ?: return state
        val choices = (payload?.get("choices") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val id = EntryId("clarify:${state.sessionKey}:$requestId")
        return state.copy(
            running = true,
            entries = state.entries + ChatEntry.Approval(
                id = id,
                timestampMs = nowMs,
                requestId = requestId,
                prompt = question,
                options = choices,
            ),
        )
    }

    fun resolveApproval(state: TimelineState, entryId: EntryId): TimelineState = state.copy(
        entries = state.entries.map { entry ->
            if (entry.id == entryId && entry is ChatEntry.Approval) entry.copy(resolved = true) else entry
        },
    )

    private fun finishTurn(state: TimelineState): TimelineState = state.copy(
        running = false,
        statusText = null,
        streamingAssistantId = null,
        activeReasoningId = null,
        entries = state.entries.map { entry ->
            if (entry is ChatEntry.Message && entry.streaming) entry.copy(streaming = false) else entry
        },
    )

    private fun errorEvent(state: TimelineState, payload: JsonObject?, nowMs: Long): TimelineState {
        val message = payload.str("error") ?: payload.str("text") ?: "The agent reported an error."
        return finishTurn(state).copy(
            entries = finishTurn(state).entries + ChatEntry.SystemNote(
                id = EntryId("error:${state.sessionKey}:${state.entries.size}"),
                timestampMs = nowMs,
                text = message,
            ),
        )
    }

    private fun toolLabel(name: String, context: String): String = when {
        name.contains("terminal") || name.contains("bash") || name.contains("shell") -> "Running terminal"
        name.contains("read") -> "Reading files"
        name.contains("edit") || name.contains("write") -> "Editing files"
        name.contains("search") || name.contains("web") -> "Searching"
        name.contains("browser") -> "Browsing"
        else -> name.replaceFirstChar { it.uppercase() }
    }

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull
}
