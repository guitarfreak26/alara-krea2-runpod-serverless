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

    private fun capabilitiesBody(
        sessionResources: Boolean = true,
        sessionUpdate: Boolean = true,
        archivedListing: Boolean = false,
    ) = buildString {
        append("""{"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-4",""")
        append(""""auth":{"type":"bearer","required":true},""")
        append(""""features":{"chat_completions":true,"session_resources":$sessionResources""")
        if (archivedListing) append(""","session_archived_listing":true""")
        append("},")
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
        server.enqueue(MockResponse().setBody(capabilitiesBody())) // openSession probes capabilities
        val handle = gateway.openSession(null, null)
        assertTrue(handle.sessionKey.startsWith("mob-"))
        val again = gateway.openSession(handle.sessionKey, null) // reopen: caller drives refresh
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
    fun `model inventory marks the profile's configured model as current`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"providers":[
                    {"slug":"openai","name":"OpenAI","is_current":false,"authenticated":true,
                     "models":["gpt-sol"]},
                    {"slug":"moonshot","name":"Moonshot","is_current":true,"authenticated":true,
                     "models":["kimi-k3","kimi-k3-turbo"]}
                 ],"model":"kimi-k3","provider":"moonshot"}""",
            ),
        )
        val models = gateway.listModels(null)
        assertEquals("/api/model/options", server.takeRequest().path)
        // Current model sorts first and is marked; others are not.
        assertEquals("kimi-k3", models.first().id)
        assertTrue(models.first().isCurrent)
        assertEquals("moonshot", models.first().provider)
        assertTrue(models.filter { it.id != "kimi-k3" }.none { it.isCurrent })
        assertEquals(setOf("gpt-sol", "kimi-k3", "kimi-k3-turbo"), models.map { it.id }.toSet())
    }

    @Test
    fun `model inventory falls back to v1 models when unavailable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"hermes-agent"}]}"""))
        val models = gateway.listModels(null)
        assertEquals(listOf("hermes-agent"), models.map { it.id })
        assertTrue(models.none { it.isCurrent })
    }

    @Test
    fun `archived listing capability filters the main list and serves the archived view`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody(archivedListing = true)))
        gateway.testConnection().getOrThrow()
        assertTrue(gateway.features.archivedListing)
        server.takeRequest() // capabilities

        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"s1","title":"live","started_at":1.0}]}"""),
        )
        val active = gateway.listSessions(null)
        assertEquals("/api/sessions?archived=exclude", server.takeRequest().path)
        assertEquals(listOf("s1"), active.map { it.key })

        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"s9","title":"desktop archived","started_at":2.0}]}"""),
        )
        val archived = gateway.listArchivedSessions(null)
        assertEquals("/api/sessions?archived=only", server.takeRequest().path)
        // Rows from the archived filter carry the flag even when the payload omits it.
        assertEquals(listOf("s9"), archived.map { it.key })
        assertTrue(archived.all { it.archived })
    }

    @Test
    fun `archived listing degrades on older builds`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        gateway.testConnection().getOrThrow()
        assertTrue(!gateway.features.archivedListing)
        server.takeRequest() // capabilities

        // Main list must not send the filter param an old build would reject.
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        gateway.listSessions(null)
        assertEquals("/api/sessions", server.takeRequest().path)

        // Archived listing fails loudly instead of returning a wrong list.
        val result = runCatching { gateway.listArchivedSessions(null) }
        assertTrue(result.isFailure)
        assertEquals(2, server.requestCount) // no extra HTTP call was made
    }

    @Test
    fun `named profiles route through p-prefix mirrors`() = runBlocking {
        val gw = ApiServerGateway(
            server.url("/"), "api-key", scope,
            profilesProvider = { listOf("kimi", "grok") },
        )
        // Old build: /v1/profiles discovery 404s, user-entered names apply.
        server.enqueue(MockResponse().setResponseCode(404))
        val profiles = gw.listProfiles()
        assertEquals("/v1/profiles", server.takeRequest().path)
        assertEquals(listOf("default", "kimi", "grok"), profiles.map { it.id })
        assertTrue(profiles.first { it.id == "default" }.isDefault)
        assertTrue(gw.features.profiles)

        gw.setActiveProfile("kimi")
        // The switch invalidates capabilities: the mirror re-probes under /p/kimi/.
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"k1","title":"kimi chat","started_at":1.0}]}"""))
        val sessions = gw.listSessions("kimi")
        assertEquals("/p/kimi/v1/capabilities", server.takeRequest().path)
        assertEquals("/p/kimi/api/sessions", server.takeRequest().path)
        assertEquals(listOf("k1"), sessions.map { it.key })
        assertEquals("kimi", sessions.first().profileId)

        // Back to default: prefix drops, capabilities re-probe again.
        gw.setActiveProfile("default")
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        gw.listSessions(null)
        assertEquals("/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/sessions", server.takeRequest().path)
    }

    @Test
    fun `server-discovered profiles carry api prefixes and win over manual names`() = runBlocking {
        val gw = ApiServerGateway(
            server.url("/"), "api-key", scope,
            profilesProvider = { listOf("stale-manual-name") },
        )
        server.enqueue(
            MockResponse().setBody(
                """{"profiles":[
                    {"name":"default","api_prefix":"","is_default":true,
                     "display_name":"Sol","description":"Manager",
                     "avatar":{"shape":"hexagon","color":"#8b5cf6"},
                     "preview":"On it — rendering now","last_active":1755400000.0,"busy":true},
                    {"name":"kimi","api_prefix":"/p/kimi"},
                    {"name":"grok","api_prefix":"/p/grok"}
                 ]}""",
            ),
        )
        val profiles = gw.listProfiles()
        assertEquals("/v1/profiles", server.takeRequest().path)
        assertEquals(listOf("default", "kimi", "grok"), profiles.map { it.id })
        assertTrue(gw.features.profiles)
        // Enriched metadata parses; the minimal rows still work beside it.
        val sol = profiles.first()
        assertEquals("Sol", sol.displayName)
        assertEquals("hexagon", sol.avatarShape)
        assertEquals("#8b5cf6", sol.avatarColor)
        assertEquals("On it — rendering now", sol.preview)
        assertEquals(1755400000000L, sol.lastActiveMs)
        assertEquals(true, sol.busy)
        assertEquals("kimi", profiles[1].displayName)
        assertEquals(null, profiles[1].busy)

        gw.setActiveProfile("grok")
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        gw.listSessions("grok")
        assertEquals("/p/grok/v1/capabilities", server.takeRequest().path)
        assertEquals("/p/grok/api/sessions", server.takeRequest().path)
    }

    @Test
    fun `account usage parses the single object shape`() = runBlocking {
        server.enqueue(MockResponse().setBody(capabilitiesBody()))
        gateway.testConnection().getOrThrow()
        server.takeRequest()
        server.enqueue(
            MockResponse().setBody(
                """{"object":"hermes.account_usage",
                    "windows":[{"label":"5h window","used_percent":37.5,"reset_at":1755300000,"detail":"resets soon"}],
                    "details":["Codex plan: pro"],
                    "unavailable_reason":null}""",
            ),
        )
        val usage = gateway.accountUsage()
        assertEquals("/v1/usage", server.takeRequest().path)
        assertEquals(1, usage.size)
        assertEquals(37.5, usage.first().windows.first().usedPercent!!, 0.01)
        assertEquals(listOf("Codex plan: pro"), usage.first().details)
    }

    @Test
    fun `media urls are profile scoped and never carry the token`() = runBlocking {
        val gw = ApiServerGateway(server.url("/"), "api-key", scope)
        val root = gw.mediaUrl("/opt/data/media-jobs/a b/final-reel.mp4")!!
        assertTrue(root.endsWith("/v1/media?path=%2Fopt%2Fdata%2Fmedia-jobs%2Fa%20b%2Ffinal-reel.mp4"))
        assertTrue(!root.contains("api-key"))

        gw.setActiveProfile("solseoyeon")
        val scoped = gw.mediaUrl("/opt/data/media-jobs/x.mp4")!!
        assertTrue(scoped.contains("/p/solseoyeon/v1/media?path="))
        // Another profile can never reuse the previous profile's route.
        gw.setActiveProfile("malgrok")
        val other = gw.mediaUrl("/opt/data/media-jobs/x.mp4")!!
        assertTrue(other.contains("/p/malgrok/") && !other.contains("solseoyeon"))
    }

    @Test
    fun `features are gated for this surface`() {
        assertTrue(!gateway.features.profiles && !gateway.features.rename)
        // Thinking/fast are supported per-request via model_options.
        assertTrue(gateway.features.sessionConfig)
        // Approvals require the runs surface, unknown until capabilities load.
        assertTrue(!gateway.features.approvals)
    }
}
