package com.alara.hermes.protocol

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lifecycle integration test for the source-of-truth contract:
 * the SERVER session is canonical. One conversation survives
 * backgrounding, reconnect after a network drop, and process death
 * (simulated as a brand-new gateway instance) — and every request in
 * its life addresses the SAME canonical backend session id, with the
 * transcript reloaded from the server rather than replayed by the client.
 */
class SessionLifecycleIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    private val canonicalId = "api_1755150000_cafe1234"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun gateway() = ApiServerGateway(server.url("/"), "api-key", scope)

    private fun capabilities() = MockResponse().setBody(
        """{"model":"hermes-4",
            "features":{"chat_completions":true,"run_submission":true,
                        "session_resources":true,"session_chat_streaming":true},
            "endpoints":{"runs":{},"sessions":{},"session_chat_stream":{}}}""",
    )

    private fun sessionChatSse(runId: String, reply: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            buildString {
                append("event: run.started\n")
                append("""data: {"run_id":"$runId","session_id":"$canonicalId"}""").append("\n\n")
                append("event: assistant.delta\n")
                append("""data: {"message_id":"m1","delta":"$reply"}""").append("\n\n")
                append("event: run.completed\n")
                append("""data: {"run_id":"$runId","usage":{}}""").append("\n\n")
                append("event: done\n")
                append("data: {}\n\n")
            },
        )

    private fun transcript(vararg turns: Pair<String, String>) = MockResponse().setBody(
        buildString {
            append("""{"data":[""")
            append(
                turns.mapIndexed { index, (role, text) ->
                    """{"id":${index + 1},"role":"$role","content":"$text"}"""
                }.joinToString(","),
            )
            append("]}")
        },
    )

    private suspend fun awaitIdle(handle: SessionHandle) {
        withTimeout(10.seconds) {
            while (handle.timeline.value.running) delay(25)
        }
    }

    @Test
    fun `one canonical backend session survives background, reconnect and relaunch`() = runBlocking {
        // ---- Phase 1: first launch — server mints the canonical session id.
        val phase1 = gateway()
        server.enqueue(capabilities())
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"$canonicalId","source":"android"}"""))
        server.enqueue(sessionChatSse("run_1", "clip one is rendering"))
        server.enqueue(transcript("user" to "make clip one", "assistant" to "clip one is rendering"))

        val handle1 = phase1.openSession(null, null)
        assertEquals(canonicalId, handle1.sessionKey)
        handle1.send("make clip one")
        awaitIdle(handle1)

        server.takeRequest() // capabilities
        assertEquals("/api/sessions", server.takeRequest().path) // create
        val turn1 = server.takeRequest()
        assertEquals("/api/sessions/$canonicalId/chat/stream", turn1.path)
        // Source of truth is server-side: the client sends ONLY the new message.
        val turn1Body = turn1.body.readUtf8()
        assertTrue(turn1Body.contains("\"message\":\"make clip one\""))
        assertTrue(!turn1Body.contains("conversation_history"))
        assertEquals("/api/sessions/$canonicalId/messages", server.takeRequest().path) // post-turn reload

        // ---- Phase 2: app backgrounded then foregrounded — same handle,
        // transcript reloaded from Hermes, id unchanged.
        server.enqueue(transcript("user" to "make clip one", "assistant" to "clip one is rendering"))
        handle1.refresh()
        assertEquals("/api/sessions/$canonicalId/messages", server.takeRequest().path)
        assertEquals(canonicalId, handle1.sessionKey)
        handle1.close()
        phase1.disconnect()

        // ---- Phase 3: process death + relaunch (new gateway instance) and/or
        // network change (fresh connections): resume by the persisted
        // canonical id, reload messages, continue the conversation.
        val phase2 = gateway()
        server.enqueue(capabilities())
        server.enqueue(transcript("user" to "make clip one", "assistant" to "clip one is rendering"))
        server.enqueue(sessionChatSse("run_2", "clip two continues the car scene"))
        server.enqueue(
            transcript(
                "user" to "make clip one",
                "assistant" to "clip one is rendering",
                "user" to "now clip two",
                "assistant" to "clip two continues the car scene",
            ),
        )

        val handle2 = phase2.openSession(canonicalId, null)
        assertEquals(canonicalId, handle2.sessionKey)
        handle2.refresh()
        // History came back from the server, not from any client cache.
        assertEquals(
            listOf("make clip one", "clip one is rendering"),
            handle2.timeline.value.entries.filterIsInstance<ChatEntry.Message>().map { it.text },
        )
        handle2.send("now clip two")
        awaitIdle(handle2)

        server.takeRequest() // capabilities (phase 2)
        assertEquals("/api/sessions/$canonicalId/messages", server.takeRequest().path) // resume reload
        val turn2 = server.takeRequest()
        assertEquals("/api/sessions/$canonicalId/chat/stream", turn2.path) // SAME id continues
        assertTrue(!turn2.body.readUtf8().contains("conversation_history"))

        // Post-turn reload still addresses the same canonical id.
        assertEquals("/api/sessions/$canonicalId/messages", server.takeRequest().path)
        // Exactly ONE session create across the whole lifecycle: 4 phase-1
        // requests + 1 background refresh + 4 relaunch requests, no second
        // POST /api/sessions ever.
        assertEquals(9, server.requestCount)
        handle2.close()
        phase2.disconnect()
    }

    @Test
    fun `network drop mid-turn settles from run status and the session id survives`() = runBlocking {
        val gw = gateway()
        server.enqueue(capabilities())
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"$canonicalId"}"""))
        // Stream announces the run then dies mid-turn (network change).
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: run.started\ndata: {\"run_id\":\"run_x\"}\n\n" +
                    "event: assistant.delta\ndata: {\"delta\":\"working on\"}\n\n",
            ),
        )
        // Reconnect path: pollable run status settles the turn...
        server.enqueue(
            MockResponse().setBody("""{"run_id":"run_x","status":"completed","output":"clip rendered","usage":{}}"""),
        )
        // ...and the transcript reloads from the canonical session.
        server.enqueue(transcript("user" to "make the clip", "assistant" to "clip rendered"))

        val handle = gw.openSession(null, null)
        handle.send("make the clip")
        awaitIdle(handle)

        server.takeRequest() // capabilities
        server.takeRequest() // create
        assertEquals("/api/sessions/$canonicalId/chat/stream", server.takeRequest().path)
        assertEquals("/v1/runs/run_x", server.takeRequest().path) // settle after drop
        assertEquals("/api/sessions/$canonicalId/messages", server.takeRequest().path)

        val texts = handle.timeline.value.entries.filterIsInstance<ChatEntry.Message>().map { it.text }
        assertEquals(listOf("make the clip", "clip rendered"), texts)
        assertEquals(canonicalId, handle.sessionKey)
        handle.close()
        gw.disconnect()
    }
}
