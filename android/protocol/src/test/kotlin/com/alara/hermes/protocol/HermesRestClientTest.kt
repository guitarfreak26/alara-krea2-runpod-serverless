package com.alara.hermes.protocol

import com.alara.hermes.protocol.wire.HermesCredential
import com.alara.hermes.protocol.wire.HermesRestClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesRestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesRestClient
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HermesRestClient(
            OkHttpClient(),
            json,
            server.url("/"),
            HermesCredential.Token("secret-token"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `session list parses rich REST rows and sends auth headers`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"sessions":[
                    {"id":"abc","title":"Fix CI","preview":"looking at logs","source":"desktop",
                     "started_at":1755000000.5,"last_active":1755100000.5,"is_active":true,
                     "message_count":12,"model":"hermes-4","pinned":true,"profile":"sol"},
                    {"id":"def","title":"H3 renders","source":"discord","last_active":1755000001.0}
                 ],"total":2,"limit":100,"offset":0}""",
            ),
        )

        val sessions = client.listSessions(profile = "sol")
        assertEquals(2, sessions.size)
        assertEquals("abc", sessions[0].durableId)
        assertEquals(true, sessions[0].pinned)
        assertEquals("sol", sessions[0].profile)

        val request = server.takeRequest()
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("secret-token", request.getHeader("X-Hermes-Session-Token"))
        assertTrue(request.path!!.startsWith("/api/sessions?"))
        assertTrue(request.path!!.contains("profile=sol"))
        assertTrue(request.path!!.contains("order=recent"))
    }

    @Test
    fun `transcript endpoint parses paged shape`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"session_id":"abc","messages":[
                    {"id":1,"role":"user","content":"hello","timestamp":1755000000.1},
                    {"id":2,"role":"assistant","content":"hi!","timestamp":1755000001.2}
                 ],"pagination":{"limit":500,"offset":0,"order":"latest","returned":2}}""",
            ),
        )
        val messages = client.sessionMessages("abc", profile = "sol")
        assertEquals(2, messages.size)
        assertEquals("1", messages[0].stableId)
        assertEquals("user", messages[0].role)

        val request = server.takeRequest()
        assertEquals("/api/sessions/abc/messages?profile=sol", request.path)
    }

    @Test
    fun `profiles endpoint tolerates object and array shapes`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"profiles":[{"name":"sol","is_default":true},{"name":"work"}]}""",
            ),
        )
        val profiles = client.listProfiles()
        assertEquals(listOf("sol", "work"), profiles.map { it.name })
        assertEquals(true, profiles[0].isDefault)
    }

    @Test
    fun `http errors carry status code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"unauthorized"}"""))
        try {
            client.listSessions(profile = null)
            throw AssertionError("expected failure")
        } catch (e: com.alara.hermes.protocol.wire.HermesHttpException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `base url parser accepts bare host and tailscale names`() {
        assertEquals(
            "http://hermes-vps:9119/",
            HermesRestClient.parseBaseUrl("hermes-vps:9119").toString(),
        )
        assertEquals(
            "https://hermes.tail1234.ts.net/",
            HermesRestClient.parseBaseUrl("https://hermes.tail1234.ts.net").toString(),
        )
        assertEquals(
            "http://100.64.0.7:9119/",
            HermesRestClient.parseBaseUrl("100.64.0.7:9119").toString(),
        )
        assertNull(HermesRestClient.parseBaseUrl("not a url at all ::"))
    }
}
