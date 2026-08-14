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

    private fun capabilitiesBody(sessionResources: Boolean = true, sessionUpdate: Boolean = true) = buildString {
        append("""{"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-4",""")
        append(""""auth":{"type":"bearer","required":true},""")
        append(""""features":{"chat_completions":true,"session_resources":$sessionResources},""")
        append(""""endpoints":{"chat_completions":{"method":"POST","path":"/v1/chat/completions"}""")
        if (sessionResources) append(""","sessions":{"method":"GET","path":"/api/sessions"}""")
        if (sessionUpdate) append(""","session_update":{"method":"PATCH","path":"/api/sessions/{session_id}"}""")
        append("}}")
    }

    @Test
    fun `capabilities probe validates with bearer auth`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        val result = gateway.testConnection()
        assertEquals("connected (hermes-4)", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/v1/capabilities", request.path)
        assertEquals("Bearer api-key", request.getHeader("Authorization"))
    }

    @Test
    fun `session list parses data envelope`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
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

        assertEquals("/v1/capabilities", server.takeRequest().path)
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
        // send() probes capabilities on first use, before opening the stream.
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        // finishTurn refresh fetches the authoritative transcript.
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

        assertEquals("/v1/capabilities", server.takeRequest().path)
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
        server.enqueue(MockResponse().setBody(capabilitiesBody())) // reopen refresh probes capabilities
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val again = gateway.openSession(handle.sessionKey, null)
        assertEquals(handle.sessionKey, again.sessionKey)
        handle.close()
    }

    @Test
    fun `delete treats 404 as success`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        gateway.testConnection().getOrThrow()
        server.enqueue(MockResponse().setResponseCode(404))
        gateway.deleteSession("ghost") // must not throw
        server.takeRequest() // capabilities
        assertEquals("/api/sessions/ghost", server.takeRequest().path)
    }

    @Test
    fun `token is trimmed before building the bearer header`() = runBlocking {
        val messy = ApiServerGateway(server.url("/"), "  api-key\n", scope)
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        messy.testConnection().getOrThrow()
        assertEquals("Bearer api-key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `session endpoints are skipped when capabilities does not advertise them`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody(sessionResources = false, sessionUpdate = false)))
        val sessions = gateway.listSessions(null)
        assertEquals("/v1/capabilities", server.takeRequest().path)
        assertEquals(1, server.requestCount) // no /api/sessions call followed
        assertTrue(sessions.isEmpty())
        assertTrue(!gateway.features.rename)
    }

    @Test
    fun `rename uses PATCH when advertised`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        gateway.testConnection().getOrThrow()
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        gateway.renameSession("s1", "New title")
        server.takeRequest() // capabilities
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/sessions/s1", patch.path)
        assertTrue(patch.body.readUtf8().contains("New title"))
        assertTrue(gateway.features.rename)
    }

    @Test
    fun `features are gated for this surface`() {
        assertEquals(GatewayFeatures.API_SERVER, gateway.features)
        assertTrue(!gateway.features.profiles && !gateway.features.rename)
    }
}
