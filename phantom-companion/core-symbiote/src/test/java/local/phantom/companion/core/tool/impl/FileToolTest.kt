package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileToolTest {

    private lateinit var tempDir: File
    private lateinit var tool: FileTool
    // Application() is a valid stub under isReturnDefaultValues=true; FileTool uses sandboxProvider lambda.
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
        tempDir = Files.createTempDirectory("file_tool_test").toFile()
        tool = FileTool(sandboxProvider = { tempDir })
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // Happy-path: write → read → list → delete
    // -------------------------------------------------------------------------

    @Test
    fun `write then read returns same content`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        val writeResult = tool.execute(
            args = buildJsonObject {
                put("op", "write")
                put("path", "hello.txt")
                put("content", "phantom agent rocks")
            },
            context = ctx,
        ) { }

        assertTrue("write should succeed, got $writeResult", writeResult is ToolResult.Ok)

        val readResult = tool.execute(
            args = buildJsonObject {
                put("op", "read")
                put("path", "hello.txt")
            },
            context = ctx,
        ) { }

        assertTrue("read should succeed, got $readResult", readResult is ToolResult.Ok)
        val ok = readResult as ToolResult.Ok
        assertEquals("phantom agent rocks", ok.output)
    }

    @Test
    fun `append adds content to existing file`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        tool.execute(buildJsonObject { put("op", "write"); put("path", "a.txt"); put("content", "line1\n") }, ctx) {}
        tool.execute(buildJsonObject { put("op", "append"); put("path", "a.txt"); put("content", "line2\n") }, ctx) {}

        val read = tool.execute(buildJsonObject { put("op", "read"); put("path", "a.txt") }, ctx) {}
        val content = (read as ToolResult.Ok).output
        assertTrue("file should contain both lines", content.contains("line1") && content.contains("line2"))
    }

    @Test
    fun `list returns entry with correct name`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        // Create a sub-directory with a file inside it
        tool.execute(buildJsonObject { put("op", "mkdir"); put("path", "subdir") }, ctx) {}
        tool.execute(buildJsonObject { put("op", "write"); put("path", "subdir/note.txt"); put("content", "x") }, ctx) {}

        val listResult = tool.execute(buildJsonObject { put("op", "list"); put("path", "subdir") }, ctx) {}
        assertTrue("list should succeed, got $listResult", listResult is ToolResult.Ok)

        val ok = listResult as ToolResult.Ok
        val entries = ok.structured?.get("entries")?.jsonArray
        assertTrue("entries should not be empty", entries != null && entries.isNotEmpty())
        val firstName = entries!![0].jsonObject["name"]?.jsonPrimitive?.content
        assertEquals("note.txt", firstName)
    }

    @Test
    fun `an empty directory says so instead of answering with nothing`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        tool.execute(buildJsonObject { put("op", "mkdir"); put("path", "порожня") }, ctx) {}

        val listResult = tool.execute(buildJsonObject { put("op", "list"); put("path", "порожня") }, ctx) {}
        assertTrue("list should succeed, got $listResult", listResult is ToolResult.Ok)

        val ok = listResult as ToolResult.Ok
        assertEquals("Тека «порожня» порожня.", ok.output)
        assertEquals(0, ok.structured?.get("entries")?.jsonArray?.size)
    }

    @Test
    fun `a directory with entries still answers with the names`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        tool.execute(buildJsonObject { put("op", "write"); put("path", "повна/є.txt"); put("content", "x") }, ctx) {}

        val ok = tool.execute(
            buildJsonObject { put("op", "list"); put("path", "повна") },
            ctx,
        ) {} as ToolResult.Ok
        assertEquals("є.txt", ok.output)
    }

    @Test
    fun `delete removes the file`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        tool.execute(buildJsonObject { put("op", "write"); put("path", "del.txt"); put("content", "bye") }, ctx) {}

        val deleteResult = tool.execute(buildJsonObject { put("op", "delete"); put("path", "del.txt") }, ctx) {}
        assertTrue("delete should succeed, got $deleteResult", deleteResult is ToolResult.Ok)

        // Subsequent read must fail
        val readResult = tool.execute(buildJsonObject { put("op", "read"); put("path", "del.txt") }, ctx) {}
        assertTrue("file should be gone", readResult is ToolResult.Err)
        assertEquals("not_found", (readResult as ToolResult.Err).code)
    }

    @Test
    fun `recursive delete removes directory tree`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_WRITE, ToolPermission.FILE_READ)

        tool.execute(buildJsonObject { put("op", "mkdir"); put("path", "tree/a") }, ctx) {}
        tool.execute(buildJsonObject { put("op", "write"); put("path", "tree/a/f.txt"); put("content", "x") }, ctx) {}

        val result = tool.execute(
            buildJsonObject {
                put("op", "delete")
                put("path", "tree")
                put("recursive", true)
            },
            ctx,
        ) {}
        assertTrue("recursive delete should succeed, got $result", result is ToolResult.Ok)
        assertTrue("tree dir should be gone", !File(tempDir, "tree").exists())
    }

    // -------------------------------------------------------------------------
    // Permission denial
    // -------------------------------------------------------------------------

    @Test
    fun `read without FILE_READ permission returns PermissionDenied`() = runTest {
        val ctx = makeContext() // no permissions

        val result = tool.execute(
            args = buildJsonObject { put("op", "read"); put("path", "any.txt") },
            context = ctx,
        ) { }

        assertTrue("expected PermissionDenied, got $result", result is ToolResult.PermissionDenied)
        assertEquals(ToolPermission.FILE_READ, (result as ToolResult.PermissionDenied).permission)
    }

    @Test
    fun `write without FILE_WRITE permission returns PermissionDenied`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_READ) // only read

        val result = tool.execute(
            args = buildJsonObject { put("op", "write"); put("path", "x.txt"); put("content", "y") },
            context = ctx,
        ) { }

        assertTrue("expected PermissionDenied, got $result", result is ToolResult.PermissionDenied)
        assertEquals(ToolPermission.FILE_WRITE, (result as ToolResult.PermissionDenied).permission)
    }

    // -------------------------------------------------------------------------
    // Path traversal
    // -------------------------------------------------------------------------

    @Test
    fun `path traversal attempt returns traversal error`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_READ)

        val result = tool.execute(
            args = buildJsonObject {
                put("op", "read")
                put("path", "../../etc/passwd")
            },
            context = ctx,
        ) { }

        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals("traversal", (result as ToolResult.Err).code)
    }

    @Test
    fun `deep relative traversal attempt returns traversal error`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_READ)

        // "../../../../etc/passwd" normalises to a path outside the tempDir sandbox
        val result = tool.execute(
            args = buildJsonObject {
                put("op", "read")
                put("path", "../../../../etc/passwd")
            },
            context = ctx,
        ) { }

        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals("traversal", (result as ToolResult.Err).code)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `missing op returns bad_args`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_READ)
        val result = tool.execute(buildJsonObject { put("path", "foo.txt") }, ctx) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `missing path returns bad_args`() = runTest {
        val ctx = makeContext(ToolPermission.FILE_READ)
        val result = tool.execute(buildJsonObject { put("op", "read") }, ctx) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `manifest has correct id and sunset`() {
        assertEquals("tool.file", tool.manifest.id)
        assertEquals(120, tool.manifest.sunsetAfterDaysIdle)
    }
}
