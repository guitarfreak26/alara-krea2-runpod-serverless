package com.alara.hermes.protocol

import app.cash.turbine.test
import com.alara.hermes.protocol.wire.GatewaySocket
import com.alara.hermes.protocol.wire.HermesRpcException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewaySocketTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

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

    /** Fake gateway: greets with gateway.ready, answers ping method, emits an event. */
    private fun enqueueGateway(onMessage: (WebSocket, String) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send(
                        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true}}}""",
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason) // complete the close handshake
                }
            }),
        )
    }

    private fun socket(): GatewaySocket =
        GatewaySocket(OkHttpClient(), scope, json) { server.url("/api/ws") }

    @Test
    fun `request response correlation by id`() = runBlocking {
        enqueueGateway { ws, text ->
            val frame = json.parseToJsonElement(text).jsonObject
            val id = frame["id"]!!.jsonPrimitive.content
            ws.send("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming"}}""")
        }
        val socket = socket()
        socket.start()
        withTimeout(5.seconds) { socket.connected.first() }

        val result = socket.request("prompt.submit", buildJsonObject { put("text", "hi") })
        assertEquals("streaming", result.jsonObject["status"]!!.jsonPrimitive.content)
        socket.stop()
    }

    @Test
    fun `rpc error surfaces code and message`() = runBlocking {
        enqueueGateway { ws, text ->
            val id = json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
            ws.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":4001,"message":"session not found"}}""")
        }
        val socket = socket()
        socket.start()
        withTimeout(5.seconds) { socket.connected.first() }

        try {
            socket.request("session.resume", buildJsonObject { put("session_id", "nope") })
            throw AssertionError("expected HermesRpcException")
        } catch (e: HermesRpcException) {
            assertEquals(4001, e.rpcCode)
            assertTrue(e.message!!.contains("session not found"))
        }
        socket.stop()
    }

    @Test
    fun `events flow independently of requests`() = runBlocking {
        enqueueGateway { ws, _ -> }
        val socket = socket()

        socket.events.test(timeout = 10.seconds) {
            socket.start()
            val ready = awaitItem()
            assertEquals("gateway.ready", ready.type)
            cancelAndIgnoreRemainingEvents()
        }
        socket.stop()
    }

    @Test
    fun `event session id resolves sid alias`() = runBlocking {
        enqueueGateway { ws, _ ->
            ws.send(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","sid":"abc123","payload":{"text":"x"}}}""",
            )
        }
        val socket = socket()
        socket.events.test(timeout = 10.seconds) {
            socket.start()
            assertEquals("gateway.ready", awaitItem().type)
            // Trigger the server to send by making any request (no reply needed).
            scope.launch { runCatching { socket.request("noop", buildJsonObject {}, timeoutMs = 500) } }
            val delta = awaitItem()
            assertEquals("message.delta", delta.type)
            assertEquals("abc123", delta.resolvedSessionId)
            cancelAndIgnoreRemainingEvents()
        }
        socket.stop()
    }

    @Test
    fun `pending requests fail fast on disconnect and socket reconnects`() = runBlocking {
        // First connection: accept then immediately close on any request.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.cancel() // hard drop with the request pending
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        // Reconnect target.
        enqueueGateway { ws, text ->
            val id = json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
            ws.send("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        }

        val socket = socket()
        socket.start()
        withTimeout(5.seconds) { socket.connected.first() }

        try {
            socket.request("session.list", buildJsonObject {})
            throw AssertionError("expected failure")
        } catch (e: HermesRpcException) {
            assertTrue(e.message!!.contains("connection lost") || e.message!!.contains("closed"))
        }

        // The socket must recover on its own (jittered backoff, second dial).
        withTimeout(15.seconds) { socket.connected.first() }
        val result = socket.request("session.list", buildJsonObject {})
        assertEquals(true, result.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
        socket.stop()
    }
}
