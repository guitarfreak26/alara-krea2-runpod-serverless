package com.alara.hermes.protocol.wire

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface SocketState {
    data object Idle : SocketState
    data object Connecting : SocketState
    data object Open : SocketState
    data class Closed(val reason: String?) : SocketState
    data class Retrying(val attempt: Int, val nextDelayMs: Long) : SocketState
}

/**
 * JSON-RPC 2.0 client over the Hermes gateway WebSocket.
 *
 * Owns its own reconnect loop: indefinite jittered exponential backoff while a
 * connection is desired. Every socket carries a generation number so callbacks
 * from a stale socket can never corrupt current state, and pending requests are
 * failed fast on disconnect (correlation scheme adapted from
 * luinbytes/hermes-android, MIT).
 */
class GatewaySocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val json: Json,
    /** Called before each connect attempt; returns the socket URL (may mint a fresh ticket). */
    private val urlProvider: suspend () -> HttpUrl,
) {
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val generation = AtomicLong(0)
    private val connectMutex = Mutex()

    @Volatile private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var desired = false

    private val _state = MutableStateFlow<SocketState>(SocketState.Idle)
    val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<WireEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<WireEvent> = _events

    /** Emitted whenever a (re)connect handshake completes, so callers can rehydrate. */
    private val _connected = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 4)
    val connected: SharedFlow<Long> = _connected

    val isOpen: Boolean get() = _state.value is SocketState.Open

    suspend fun start() {
        desired = true
        connectMutex.withLock {
            if (isOpen || reconnectJob?.isActive == true) return
            launchReconnectLoop(initialDelayMs = 0)
        }
    }

    fun stop(reason: String = "client stop") {
        desired = false
        reconnectJob?.cancel()
        reconnectJob = null
        closeCurrent(reason)
        _state.value = SocketState.Idle
    }

    private fun launchReconnectLoop(initialDelayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var delayMs = initialDelayMs
            while (desired) {
                if (delayMs > 0) {
                    _state.value = SocketState.Retrying(attempt, delayMs)
                    delay(delayMs)
                }
                if (!desired) return@launch
                val ok = attemptConnect()
                if (ok) return@launch
                attempt += 1
                // 1s..30s with +-20% jitter; never gives up while desired.
                val base = (1000L shl minOf(attempt, 5)).coerceAtMost(30_000L)
                delayMs = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
            }
        }
    }

    private suspend fun attemptConnect(): Boolean {
        _state.value = SocketState.Connecting
        val url = try {
            urlProvider()
        } catch (t: Throwable) {
            _state.value = SocketState.Closed("auth: ${t.message}")
            return false
        }
        val gen = generation.incrementAndGet()
        val opened = CompletableDeferred<Boolean>()
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (gen != generation.get()) return ws.cancel()
                    webSocket = ws
                    _state.value = SocketState.Open
                    opened.complete(true)
                    _connected.tryEmit(gen)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (gen != generation.get()) return
                    handleFrame(text)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    handleDisconnect(gen, "closed $code $reason", opened)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    handleDisconnect(gen, t.message ?: "socket failure", opened)
                }
            },
        )
        val ok = try {
            withTimeout(15_000) { opened.await() }
        } catch (t: Throwable) {
            socket.cancel()
            false
        }
        return ok
    }

    private fun handleDisconnect(gen: Long, reason: String, opened: CompletableDeferred<Boolean>) {
        opened.complete(false)
        if (gen != generation.get()) return
        generation.incrementAndGet()
        webSocket = null
        failPending("connection lost: $reason")
        if (desired) {
            _state.value = SocketState.Closed(reason)
            launchReconnectLoop(initialDelayMs = 1000)
        } else {
            _state.value = SocketState.Idle
        }
    }

    private fun failPending(message: String) {
        val error = HermesRpcException(message)
        val entries = pending.entries.toList()
        pending.clear()
        entries.forEach { it.value.completeExceptionally(error) }
    }

    private fun closeCurrent(reason: String) {
        generation.incrementAndGet()
        webSocket?.close(1000, reason.take(120))
        webSocket = null
        failPending(reason)
    }

    private fun handleFrame(text: String) {
        val frame = try {
            json.decodeFromString(JsonRpcFrame.serializer(), text)
        } catch (t: Throwable) {
            return // Unknown frames are tolerated by contract.
        }
        if (frame.method == "event") {
            frame.params?.let { _events.tryEmit(it) }
            return
        }
        val id = frame.id ?: return
        val deferred = pending.remove(id) ?: return
        val error = frame.error
        if (error != null) {
            deferred.completeExceptionally(
                HermesRpcException(error.message ?: "gateway error", error.code),
            )
        } else {
            deferred.complete(frame.result ?: JsonObject(emptyMap()))
        }
    }

    /**
     * Send a request and await its result. The RPC ack for prompt submission is
     * NOT turn completion — callers must watch [events] for terminal events.
     */
    suspend fun request(method: String, params: JsonElement, timeoutMs: Long = 60_000): JsonElement {
        val ws = webSocket ?: throw HermesRpcException("not connected")
        val id = requestIds.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val payload = json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = id, method = method, params = params),
        )
        if (!ws.send(payload)) {
            pending.remove(id)
            throw HermesRpcException("send failed: socket closed")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        fun defaultOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
