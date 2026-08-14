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

class ApiServerGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var gateway: ApiServerGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        gateway = ApiServerGateway(server.url("/"), "api-key", scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `capabilities probe validates with bearer auth`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":"0.21.0","features":["chat"]}"""))
        val result = gateway.testConnection()
        assertEquals("0.21.0", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/v1/capabilities", request.path)
        assertEquals("Bearer api-key", request.getHeader("Authorization"))
    }

    @Test
    fun `session list parses data envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                    {"id":"s1","title":"video batch","model":"hermes-4","source":"cli",
                     "message_count":4,"preview":"rendered 3 clips","started_at":1755000000.0,
                     "ended_at":null},
                    {"id":"s2","title":"old","started_at":1754000000.0,"ended_at":1754000100.0}
                 ]}""",
            ),
        )
        val sessions = gateway.listSessions(null)
        assertEquals(listOf("s1", "s2"), sessions.map { it.key })
        assertEquals("video batch", sessions[0].title)

        val request = server.takeRequest()
        assertEquals("/api/sessions", request.path)
        assertEquals("Bearer api-key", request.getHeader("Authorization"))
    }

    @Test
    fun `chat streams sse deltas and tool events into the timeline`() = runBlocking {
        val sse = buildString {
            append("event: hermes.tool.progress\n")
            append("""data: {"tool_id":"t1","name":"bash","status":"running","summary":"ls"}""")
            append("\n\n")
            append("""data: {"choices":[{"delta":{"content":"Hel"}}]}""")
            append("\n\n")
            append("""data: {"choices":[{"delta":{"content":"lo"}}]}""")
            append("\n\n")
            append("event: hermes.tool.progress\n")
            append("""data: {"tool_id":"t1","name":"bash","status":"completed","summary":"2 files"}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        // finishTurn refreshes from the transcript endpoint afterwards.
        server.enqueue(
            MockResponse().setBody(
                """{"data":[
                    {"id":1,"role":"user","content":"hi"},
                    {"id":2,"role":"assistant","content":"Hello"}
                 ]}""",
            ),
        )

        val handle = gateway.openSession(null, null)
        handle.send("hi")

        withTimeout(10.seconds) {
            while (handle.timeline.value.running) delay(50)
        }
        // Wait for the post-turn authoritative refresh to land.
        withTimeout(10.seconds) {
            while (handle.timeline.value.entries.none {
                    it is ChatEntry.Message && it.role == Role.ASSISTANT && it.text == "Hello"
                }
            ) {
                delay(50)
            }
        }

        val chatRequest = server.takeRequest()
        assertEquals("/v1/chat/completions", chatRequest.path)
        assertEquals(handle.sessionKey, chatRequest.getHeader("X-Hermes-Session-Id"))
        assertEquals("Bearer api-key", chatRequest.getHeader("Authorization"))
        assertTrue(chatRequest.body.readUtf8().contains("\"stream\":true"))

        // Rehydrated authoritative transcript replaces the streamed buffer.
        val texts = handle.timeline.value.entries
            .filterIsInstance<ChatEntry.Message>().map { it.text }
        assertEquals(listOf("hi", "Hello"), texts)
        handle.close()
    }

    @Test
    fun `client generated session ids are durable mob ids`() = runBlocking {
        val handle = gateway.openSession(null, null)
        assertTrue(handle.sessionKey.startsWith("mob-"))
        server.enqueue(MockResponse().setBody("""{"data":[]}""")) // reopen triggers a refresh
        val again = gateway.openSession(handle.sessionKey, null)
        assertEquals(handle.sessionKey, again.sessionKey)
        handle.close()
    }

    @Test
    fun `delete treats 404 as success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        gateway.deleteSession("ghost") // must not throw
        assertEquals("/api/sessions/ghost", server.takeRequest().path)
    }

    @Test
    fun `features are gated for this surface`() {
        assertEquals(GatewayFeatures.API_SERVER, gateway.features)
        assertTrue(!gateway.features.profiles && !gateway.features.rename)
    }
}
