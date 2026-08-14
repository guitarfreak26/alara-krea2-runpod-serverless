package com.alara.hermes.protocol

import com.alara.hermes.protocol.wire.ProtocolMessage
import com.alara.hermes.protocol.wire.WireEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineReducerTest {

    private val now = 1_000_000L

    private fun event(type: String, payload: JsonObject? = null) =
        WireEvent(type = type, sessionId = "run1", payload = payload)

    private fun start() = TimelineState(sessionKey = "stored1")

    // --- streaming ---------------------------------------------------------

    @Test
    fun `full turn stream produces one assistant message`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("message.start"), now)
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "Hel") }), now)
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "lo") }), now)
        assertTrue(state.running)
        state = TimelineReducer.reduce(state, event("message.complete", buildJsonObject {
            put("text", "Hello")
            put("status", "complete")
        }), now)

        val messages = state.entries.filterIsInstance<ChatEntry.Message>()
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].text)
        assertFalse(messages[0].streaming)
        assertFalse(state.running)
    }

    @Test
    fun `delta without start synthesizes assistant bubble`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "hi") }), now)
        val messages = state.entries.filterIsInstance<ChatEntry.Message>()
        assertEquals(1, messages.size)
        assertEquals(Role.ASSISTANT, messages[0].role)
        assertTrue(messages[0].streaming)
    }

    @Test
    fun `interim seals bubble and next delta opens a new one`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("message.start"), now)
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "part 1") }), now)
        state = TimelineReducer.reduce(state, event("message.interim", buildJsonObject {
            put("text", "part 1")
            put("already_streamed", true)
        }), now)
        assertNull(state.streamingAssistantId)
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "part 2") }), now)
        val messages = state.entries.filterIsInstance<ChatEntry.Message>()
        assertEquals(2, messages.size)
        assertEquals("part 1", messages[0].text)
        assertEquals("part 2", messages[1].text)
    }

    @Test
    fun `complete prefers rendered over streamed text`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "raw") }), now)
        state = TimelineReducer.reduce(state, event("message.complete", buildJsonObject {
            put("rendered", "raw but rendered")
            put("text", "raw")
        }), now)
        val message = state.entries.filterIsInstance<ChatEntry.Message>().single()
        assertEquals("raw but rendered", message.text)
    }

    // --- reasoning ---------------------------------------------------------

    @Test
    fun `reasoning deltas accumulate and available replaces`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("reasoning.delta", buildJsonObject { put("text", "think ") }), now)
        state = TimelineReducer.reduce(state, event("reasoning.delta", buildJsonObject { put("text", "more") }), now)
        var reasoning = state.entries.filterIsInstance<ChatEntry.Reasoning>().single()
        assertEquals("think more", reasoning.summary)
        assertTrue(reasoning.active)

        state = TimelineReducer.reduce(state, event("reasoning.available", buildJsonObject { put("text", "final summary") }), now)
        reasoning = state.entries.filterIsInstance<ChatEntry.Reasoning>().single()
        assertEquals("final summary", reasoning.summary)
        assertFalse(reasoning.active)
    }

    // --- tools -------------------------------------------------------------

    @Test
    fun `tool lifecycle start progress complete keyed by tool_id`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("tool.start", buildJsonObject {
            put("tool_id", "t1")
            put("name", "bash")
            put("context", "ls -la")
        }), now)
        state = TimelineReducer.reduce(state, event("tool.progress", buildJsonObject {
            put("tool_id", "t1")
            put("summary", "running")
        }), now)
        var tool = state.entries.filterIsInstance<ChatEntry.ToolRun>().single()
        assertEquals(ToolRunStatus.RUNNING, tool.status)

        state = TimelineReducer.reduce(state, event("tool.complete", buildJsonObject {
            put("tool_id", "t1")
            put("name", "bash")
            put("summary", "3 files")
            put("duration_s", 1.5)
        }), now)
        tool = state.entries.filterIsInstance<ChatEntry.ToolRun>().single()
        assertEquals(ToolRunStatus.SUCCEEDED, tool.status)
        assertTrue(tool.detail.contains("3 files"))
    }

    @Test
    fun `failed tool marked failed`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("tool.start", buildJsonObject {
            put("tool_id", "t9")
            put("name", "web_search")
        }), now)
        state = TimelineReducer.reduce(state, event("tool.complete", buildJsonObject {
            put("tool_id", "t9")
            put("failed", true)
        }), now)
        val tool = state.entries.filterIsInstance<ChatEntry.ToolRun>().single()
        assertEquals(ToolRunStatus.FAILED, tool.status)
    }

    // --- approvals ---------------------------------------------------------

    @Test
    fun `approval request rendered with server choices and resolved on respond`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("approval.request", buildJsonObject {
            put("command", "rm -rf build")
            putJsonArray("choices") { add("once"); add("session"); add("deny") }
        }), now)
        val approval = state.entries.filterIsInstance<ChatEntry.Approval>().single()
        assertEquals(listOf("once", "session", "deny"), approval.options)
        assertEquals("", approval.requestId) // session-keyed on the wire

        state = TimelineReducer.resolveApproval(state, approval.id)
        assertTrue(state.entries.filterIsInstance<ChatEntry.Approval>().single().resolved)
    }

    @Test
    fun `clarify request carries request id`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("clarify.request", buildJsonObject {
            put("request_id", "req42")
            put("question", "Which branch?")
        }), now)
        val approval = state.entries.filterIsInstance<ChatEntry.Approval>().single()
        assertEquals("req42", approval.requestId)
    }

    // --- errors / terminal -------------------------------------------------

    @Test
    fun `error event finishes turn and adds note`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("message.start"), now)
        state = TimelineReducer.reduce(state, event("error", buildJsonObject { put("error", "boom") }), now)
        assertFalse(state.running)
        assertTrue(state.entries.any { it is ChatEntry.SystemNote && it.text == "boom" })
    }

    @Test
    fun `unknown events are tolerated`() {
        var state = start()
        val before = state
        state = TimelineReducer.reduce(state, event("pet.changed"), now)
        assertEquals(before.entries, state.entries)
    }

    // --- history / rehydrate ----------------------------------------------

    @Test
    fun `rehydrate keys entries by durable row_id`() {
        val messages = listOf(
            ProtocolMessage(role = "user", text = "hello", rowId = 11),
            ProtocolMessage(role = "assistant", text = "hi there", rowId = 12),
        )
        val state = TimelineReducer.rehydrate(start(), messages, now)
        assertEquals(
            listOf("history:stored1:11", "history:stored1:12"),
            state.entries.map { it.id.value },
        )
    }

    @Test
    fun `rehydrate replaces streamed state wholesale`() {
        var state = start()
        state = TimelineReducer.reduce(state, event("message.delta", buildJsonObject { put("text", "partial") }), now)
        val messages = listOf(
            ProtocolMessage(role = "user", text = "prompt", rowId = 1),
            ProtocolMessage(role = "assistant", text = "full reply", rowId = 2),
        )
        state = TimelineReducer.rehydrate(state, messages, now)
        val texts = state.entries.filterIsInstance<ChatEntry.Message>().map { it.text }
        assertEquals(listOf("prompt", "full reply"), texts)
        assertNull(state.streamingAssistantId)
    }

    @Test
    fun `rehydrate is idempotent across reconnects`() {
        val messages = listOf(
            ProtocolMessage(role = "user", text = "same", rowId = 5),
            ProtocolMessage(role = "assistant", text = "answer", rowId = 6),
        )
        val once = TimelineReducer.rehydrate(start(), messages, now)
        val twice = TimelineReducer.rehydrate(once, messages, now)
        assertEquals(once.entries.map { it.id }, twice.entries.map { it.id })
        assertEquals(once.entries.size, twice.entries.size)
    }

    @Test
    fun `hidden rows and tool-result leaks are dropped`() {
        val messages = listOf(
            ProtocolMessage(role = "user", text = "q", rowId = 1),
            ProtocolMessage(role = "assistant", text = "keep", rowId = 2),
            ProtocolMessage(role = "assistant", text = "secret", rowId = 3, displayKind = "hidden"),
            ProtocolMessage(role = "user", text = "<untrusted_tool_result>x</untrusted_tool_result>", rowId = 4),
        )
        val state = TimelineReducer.rehydrate(start(), messages, now)
        val texts = state.entries.filterIsInstance<ChatEntry.Message>().map { it.text }
        assertEquals(listOf("q", "keep"), texts)
    }

    @Test
    fun `tool history rows become succeeded tool cards`() {
        val messages = listOf(
            ProtocolMessage(role = "tool", name = "bash", context = "git status", rowId = 7),
        )
        val state = TimelineReducer.rehydrate(start(), messages, now)
        val tool = state.entries.filterIsInstance<ChatEntry.ToolRun>().single()
        assertEquals("bash", tool.tool)
        assertEquals(ToolRunStatus.SUCCEEDED, tool.status)
    }

    @Test
    fun `content parts array is flattened to text`() {
        val json = Json { ignoreUnknownKeys = true }
        val message = json.decodeFromString(
            ProtocolMessage.serializer(),
            """{"role":"assistant","row_id":9,
                "content":[{"type":"text","text":"para one"},{"type":"text","text":"para two"}]}""",
        )
        assertEquals("para one\npara two", TimelineReducer.extractText(message))
    }

    @Test
    fun `numeric history ids survive lenient parsing`() {
        val json = Json { ignoreUnknownKeys = true }
        val message = json.decodeFromString(
            ProtocolMessage.serializer(),
            """{"id": 12345, "role": "user", "text": "hello"}""",
        )
        assertEquals("12345", message.stableId)
    }
}
