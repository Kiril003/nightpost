package local.phantom.companion.core.tool.impl

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpToolTest {

    private lateinit var allowlist: HostAllowlist
    // Application() is a valid stub under isReturnDefaultValues=true; HttpTool never calls appContext.
    private val stubContext = Application()

    private fun makeContext(vararg perms: ToolPermission) = ToolContext(
        appContext = stubContext,
        operatorId = "test-operator",
        requestId = "test-request",
        agentDepth = 0,
        approvedPermissions = setOf(*perms),
    )

    @Before
    fun setUp() {
        allowlist = HostAllowlist()
    }

    private fun buildTool(engine: MockEngine): HttpTool =
        HttpTool(
            httpClient = HttpClient(engine),
            hostAllowlist = allowlist,
        )

    @Test
    fun `GET to allowlisted host returns Ok with body and status 200`() = runTest {
        allowlist.approve("example.com")

        val engine = MockEngine { _ ->
            respond(
                content = """{"hello":"world"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val tool = buildTool(engine)
        val chunks = mutableListOf<String>()

        val result = tool.execute(
            args = buildJsonObject { put("url", "http://example.com/api") },
            context = makeContext(ToolPermission.NETWORK),
            emit = { event ->
                if (event is ToolEvent.Chunk) chunks += event.text
            },
        )

        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue("body should contain hello", ok.output.contains("hello"))

        val status = ok.structured?.get("status")?.jsonPrimitive?.int
        assertEquals(200, status)
        assertTrue("chunk should be emitted for application/json", chunks.isNotEmpty())
    }

    @Test
    fun `unapproved subdomain of allowlisted host is rejected and explicit subdomain is permitted`() = runTest {
        allowlist.approve("example.com")

        val engine = MockEngine { _ ->
            respond(content = "ok", status = HttpStatusCode.OK)
        }
        val tool = buildTool(engine)

        val unapprovedSubdomain = tool.execute(
            args = buildJsonObject { put("url", "http://api.example.com/v1") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }

        assertTrue(
            "implicit *.example.com subdomain must be rejected, got $unapprovedSubdomain",
            unapprovedSubdomain is ToolResult.Err,
        )
        assertEquals("forbidden_host", (unapprovedSubdomain as ToolResult.Err).code)

        allowlist.approve("api.example.com")
        val explicitSubdomain = tool.execute(
            args = buildJsonObject { put("url", "http://api.example.com/v1") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }
        assertTrue("explicitly approved subdomain should be allowed, got $explicitSubdomain", explicitSubdomain is ToolResult.Ok)
    }

    @Test
    fun `forbidden host returns forbidden_host error`() = runTest {
        // allowlist is empty — no hosts approved
        val engine = MockEngine { _ ->
            respond(content = "should not reach", status = HttpStatusCode.OK)
        }
        val tool = buildTool(engine)

        val result = tool.execute(
            args = buildJsonObject { put("url", "http://evil.com/steal") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }

        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals("forbidden_host", (result as ToolResult.Err).code)
    }

    @Test
    fun `missing url returns bad_args error`() = runTest {
        val engine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.OK) }
        val tool = buildTool(engine)

        val result = tool.execute(
            args = buildJsonObject { },
            context = makeContext(ToolPermission.NETWORK),
        ) { }

        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `server 500 response returns http_500 error`() = runTest {
        allowlist.approve("example.com")

        val engine = MockEngine { _ ->
            respond(
                content = "internal error",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val tool = buildTool(engine)

        val result = tool.execute(
            args = buildJsonObject { put("url", "http://example.com/fail") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }

        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals("http_500", (result as ToolResult.Err).code)
    }

    @Test
    fun `GET redirect is not followed automatically and returns status and Location`() = runTest {
        allowlist.approve("api.example.com")
        var hitInternal = false

        val engine = MockEngine { request ->
            if (request.url.host == "internal.corp") {
                hitInternal = true
                respond("secret", HttpStatusCode.OK)
            } else {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://internal.corp/secret"),
                )
            }
        }
        val tool = buildTool(engine)

        val result = tool.execute(
            args = buildJsonObject { put("url", "https://api.example.com/redirect") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }

        assertTrue("redirect must not hit internal.corp", !hitInternal)
        assertTrue("expected Ok with redirect info, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertEquals(302, ok.structured?.get("status")?.jsonPrimitive?.int)
        assertEquals("https://internal.corp/secret", ok.structured?.get("location")?.jsonPrimitive?.content)
        assertTrue(ok.output.contains("302") && ok.output.contains("https://internal.corp/secret"))
    }

    @Test
    fun `URL with userinfo or backslash is rejected as bad_url`() = runTest {
        allowlist.approve("trusted.example.com")
        allowlist.approve("evil.com")

        val engine = MockEngine { _ -> respond("ok", HttpStatusCode.OK) }
        val tool = buildTool(engine)

        val userInfoRes = tool.execute(
            args = buildJsonObject { put("url", "https://trusted.example.com:80@evil.com/path") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }
        assertTrue(userInfoRes is ToolResult.Err)
        assertEquals("bad_url", (userInfoRes as ToolResult.Err).code)

        val backslashRes = tool.execute(
            args = buildJsonObject { put("url", "https://trusted.example.com\\@evil.com/path") },
            context = makeContext(ToolPermission.NETWORK),
        ) { }
        assertTrue(backslashRes is ToolResult.Err)
        assertEquals("bad_url", (backslashRes as ToolResult.Err).code)
    }

    @Test
    fun `manifest has correct id and sunset`() {
        val engine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.OK) }
        val tool = buildTool(engine)
        assertEquals("tool.http", tool.manifest.id)
        assertEquals(90, tool.manifest.sunsetAfterDaysIdle)
    }
}
