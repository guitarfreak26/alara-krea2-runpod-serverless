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

class ApiServerRunsTest {

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

    private fun runsCapabilities() =
        """{"model":"hermes-4",
            "features":{"chat_completions":true,"run_submission":true,"session_resources":false},
            "endpoints":{"runs":{"method":"POST","path":"/v1/runs"}}}"""

    @Test
    fun `runs turn maps structured events onto the timeline`() = runBlocking {
        server.enqueue(MockResponse().setBody(runsCapabilities()))
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"run_id":"run_abc","status":"started"}"""),
        )
        val sse = buildString {
            append(""": keepalive""").append("\n\n")
            append("""data: {"event":"reasoning.available","run_id":"run_abc","text":"planning"}""").append("\n\n")
            append("""data: {"event":"tool.started","run_id":"run_abc","tool":"terminal","preview":"ls"}""").append("\n\n")
            append("""data: {"event":"tool.completed","run_id":"run_abc","tool":"terminal","duration":1.2,"error":false}""").append("\n\n")
            append("""data: {"event":"message.delta","run_id":"run_abc","delta":"Hello "}""").append("\n\n")
            append("""data: {"event":"message.delta","run_id":"run_abc","delta":"world"}""").append("\n\n")
            append("""data: {"event":"run.completed","run_id":"run_abc","output":"Hello world","usage":{}}""").append("\n\n")
            append(": stream closed\n\n")
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse),
        )

        val handle = gateway.openSession(null, null)
        handle.send("hi")

        withTimeout(10.seconds) {
            while (handle.timeline.value.running) delay(50)
        }

        // Request order: capabilities, run submit (with session binding), events.
        assertEquals("/v1/capabilities", server.takeRequest().path)
        val submit = server.takeRequest()
        assertEquals("/v1/runs", submit.path)
        val submitBody = submit.body.readUtf8()
        assertTrue(submitBody.contains("\"input\":\"hi\""))
        assertTrue(submitBody.contains("\"session_id\":\"${handle.sessionKey}\""))
        assertEquals("/v1/runs/run_abc/events", server.takeRequest().path)

        val timeline = handle.timeline.value
        val assistant = timeline.entries.filterIsInstance<ChatEntry.Message>()
            .last { it.role == Role.ASSISTANT }
        assertEquals("Hello world", assistant.text)
        val tool = timeline.entries.filterIsInstance<ChatEntry.ToolRun>().single()
        assertEquals(ToolRunStatus.SUCCEEDED, tool.status)
        assertTrue(timeline.entries.filterIsInstance<ChatEntry.Reasoning>().isNotEmpty())
        handle.close()
    }

    @Test
    fun `approval request pauses run and respond posts choice`() = runBlocking {
        server.enqueue(MockResponse().setBody(runsCapabilities()))
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("""{"run_id":"run_ap","status":"started"}"""),
        )
        // Stream delivers an approval request then stays open (simulated by
        // ending the body; settle comes from run status).
        val sse = buildString {
            append("""data: {"event":"approval.request","run_id":"run_ap","command":"rm -rf build","choices":["once","session","deny"]}""")
            append("\n\n")
            append("""data: {"event":"approval.responded","run_id":"run_ap","choice":"once","resolved":1}""").append("\n\n")
            append("""data: {"event":"run.completed","run_id":"run_ap","output":"done","usage":{}}""").append("\n\n")
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse),
        )
        // approval POST response
        server.enqueue(
            MockResponse().setBody("""{"object":"hermes.run.approval_response","run_id":"run_ap","choice":"once","resolved":1}"""),
        )

        val handle = gateway.openSession(null, null)
        handle.send("dangerous please")

        withTimeout(10.seconds) {
            while (handle.timeline.value.entries.none { it is ChatEntry.Approval }) delay(25)
        }
        val approval = handle.timeline.value.entries.filterIsInstance<ChatEntry.Approval>().first()
        assertEquals(listOf("once", "session", "deny"), approval.options)
        assertTrue(approval.prompt.contains("rm -rf build"))

        withTimeout(10.seconds) {
            while (handle.timeline.value.running) delay(25)
        }
        // approval.responded resolves the card even if answered from Desktop.
        assertTrue(
            handle.timeline.value.entries.filterIsInstance<ChatEntry.Approval>().all { it.resolved },
        )
        handle.close()
    }

    @Test
    fun `image attachments ride chat completions even when runs are available`() = runBlocking {
        server.enqueue(MockResponse().setBody(runsCapabilities()))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"nice pic\"}}]}\n\ndata: [DONE]\n\n",
            ),
        )
        val handle = gateway.openSession(null, null)
        handle.send(
            "what is this?",
            listOf(OutgoingAttachment("shot.png", "image/png", "aGVsbG8=")),
        )
        withTimeout(10.seconds) {
            while (handle.timeline.value.running) delay(50)
        }
        assertEquals("/v1/capabilities", server.takeRequest().path)
        val chat = server.takeRequest()
        assertEquals("/v1/chat/completions", chat.path)
        val body = chat.body.readUtf8()
        assertTrue(body.contains("image_url"))
        assertTrue(body.contains("data:image/png;base64,aGVsbG8="))
        handle.close()
    }
}
