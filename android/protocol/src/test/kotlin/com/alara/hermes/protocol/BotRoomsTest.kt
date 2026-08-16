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

/** Mocked contract tests for docs/BOT_ROOMS_API.md. */
class BotRoomsTest {

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

    private fun capabilities(rooms: Boolean) = MockResponse().setBody(
        buildString {
            append("""{"object":"hermes.api_server.capabilities","model":"hermes-4",""")
            append(""""features":{"chat_completions":true,"session_resources":true,"run_submission":true""")
            if (rooms) append(""","bot_mode_rooms":true""")
            append("},")
            append(""""endpoints":{"sessions":{},"session_update":{},"runs":{}}}""")
        },
    )

    private val roomsBody = """{"object":"hermes.bot_mode.rooms","rooms":[
        {"id":"team-a","display_name":"Team A","api_prefix":"","manager":"mgr",
         "members":[
           {"profile":"mgr","display_name":"Manager A","role":"manager"},
           {"profile":"spec","display_name":"Spec A","role":"specialist"}
         ],
         "session_id":"room_team_a","preview":"done","last_active":1755.0,"busy":false},
        {"id":"team-b","display_name":"Team B","manager":"mgr2",
         "members":[{"profile":"mgr2","display_name":"Manager B","role":"manager"}],
         "session_id":"room_team_b"}
    ]}"""

    @Test
    fun `capability absent hides rooms and keeps chats working`() = runBlocking {
        server.enqueue(capabilities(rooms = false))
        gateway.testConnection().getOrThrow()
        assertTrue(!gateway.features.botRooms)
        val result = runCatching { gateway.listBotRooms() }
        assertTrue(result.isFailure) // no fake rooms, no HTTP call
        assertEquals(1, server.requestCount)
        // Plain sessions still open on the same gateway.
        server.enqueue(capabilities(rooms = false))
        val handle = gateway.openSession(null, null)
        assertTrue(handle.sessionKey.isNotBlank())
        handle.close()
    }

    @Test
    fun `rooms are discovered dynamically`() = runBlocking {
        server.enqueue(capabilities(rooms = true))
        gateway.testConnection().getOrThrow()
        server.takeRequest()
        server.enqueue(MockResponse().setBody(roomsBody))
        val rooms = gateway.listBotRooms()
        assertEquals("/v1/bot-mode/rooms", server.takeRequest().path)
        assertEquals(listOf("team-a", "team-b"), rooms.map { it.id })
        assertEquals("Manager A", rooms[0].members.first { it.role == "manager" }.displayName)
        assertEquals("room_team_a", rooms[0].sessionKey)
        assertEquals(1755000L, rooms[0].lastActiveMs)
        // Minimal second room parses without optional fields.
        assertEquals(null, rooms[1].preview)

        server.enqueue(MockResponse().setBody("""{"rooms":[]}"""))
        assertTrue(gateway.listBotRooms().isEmpty())

        server.enqueue(MockResponse().setBody("""{"unexpected":true}"""))
        assertTrue(runCatching { gateway.listBotRooms() }.isFailure)
    }

    @Test
    fun `room send carries structured mentions and idempotency id`() = runBlocking {
        server.enqueue(capabilities(rooms = true))
        gateway.testConnection().getOrThrow()
        server.takeRequest()
        server.enqueue(MockResponse().setBody(roomsBody))
        val room = gateway.listBotRooms().first()
        server.takeRequest()

        val handle = gateway.openRoom(room)
        // refresh() loads the persistent transcript first.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":1,"role":"user","content":"hi"}]}"""))
        handle.refresh()
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"room_id":"team-a","message_id":"m1","run_id":"run1","duplicate":false}""",
            ),
        )
        // Manager-only frontstage: specialist frames become activity rows,
        // one final manager message, media rides the manager output.
        val sse = buildString {
            append("""data: {"event":"manager.started","profile":"mgr","seq":1}""").append("\n\n")
            append("""data: {"event":"specialist.started","specialist":"spec","display_name":"Spec A","task":"QC audio","seq":2}""").append("\n\n")
            append("""data: {"event":"specialist.progress","specialist":"spec","status":"editing","seq":3}""").append("\n\n")
            // Duplicate seq must not double-render after reconnects.
            append("""data: {"event":"specialist.progress","specialist":"spec","status":"editing","seq":3}""").append("\n\n")
            append("""data: {"event":"specialist.completed","specialist":"spec","status":"ok","seq":4}""").append("\n\n")
            append("""data: {"event":"media.available","path":"/opt/x/final.mp4","seq":5}""").append("\n\n")
            append("""data: {"event":"manager.completed","output":"All done — reel attached.","seq":6}""").append("\n\n")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        // afterTurn refresh converges with the authoritative transcript.
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        handle.sendWithMentions("@Spec A please review", listOf("spec"), clientMessageId = "cid-123")

        val post = server.takeRequest()
        assertEquals("/v1/bot-mode/rooms/team-a/messages", post.path)
        val body = post.body.readUtf8()
        assertTrue(body.contains("\"mentions\":[\"spec\"]"))
        assertTrue(body.contains("\"client_message_id\":\"cid-123\""))
        assertTrue(!body.contains("api-key"))

        withTimeout(10.seconds) {
            while (handle.timeline.value.running) delay(50)
        }
        val entries = handle.timeline.value.entries
        val assistant = entries.filterIsInstance<ChatEntry.Message>()
            .filter { it.role == Role.ASSISTANT }
        assertEquals(1, assistant.size) // one manager voice frontstage
        assertTrue(assistant.first().text.contains("MEDIA:/opt/x/final.mp4"))
        assertTrue(assistant.first().text.contains("All done"))
        val activity = entries.filterIsInstance<ChatEntry.ToolRun>()
        assertEquals(1, activity.size) // specialist collapsed into ONE activity row
        assertEquals("Spec A", activity.first().label)
        handle.close()
    }

    @Test
    fun `unknown mention is rejected without dispatching an agent`() = runBlocking {
        server.enqueue(capabilities(rooms = true))
        gateway.testConnection().getOrThrow()
        server.takeRequest()
        server.enqueue(MockResponse().setBody(roomsBody))
        val room = gateway.listBotRooms().first()
        server.takeRequest()
        val handle = gateway.openRoom(room)
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        handle.refresh()
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error":{"code":"invalid_mention","message":"unknown member: ghost"}}""",
            ),
        )
        val result = runCatching { handle.sendWithMentions("@ghost do things", listOf("ghost")) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unknown member"))
        assertTrue(!handle.timeline.value.running) // turn settled, nothing streams
        handle.close()
    }
}
