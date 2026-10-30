package local.phantom.companion.ui.messenger.preview

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import local.phantom.companion.ui.messenger.Served
import local.phantom.companion.ui.messenger.WebMessengerAssets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreviewStoreTest {

    private lateinit var tempRoot: File
    private lateinit var previewDir: File
    private var nowMs: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("preview-store-test").toFile()
        previewDir = File(tempRoot, "preview")
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // ── 1. PreviewStore contract ─────────────────────────────────────────

    @Test
    fun `unsupported kind, non-positive size and oversize are refused with empty id`() {
        val store = PreviewStore(previewDir, clock = { nowMs })

        assertEquals("", store.begin("exe", "a.exe", "application/octet-stream", 100L))
        assertEquals("", store.begin("png", "a.png", "image/png", 100L))
        assertEquals("", store.begin("", "a.pdf", "application/pdf", 100L))

        assertEquals("", store.begin("pdf", "a.pdf", "application/pdf", 0L))
        assertEquals("", store.begin("pdf", "a.pdf", "application/pdf", -1L))
        assertEquals("", store.begin("heic", "a.heic", "image/heic", 0L))
        assertEquals("", store.begin("heic", "a.heic", "image/heic", -50L))

        assertEquals("", store.begin("pdf", "big.pdf", "application/pdf", PreviewStore.MAX_PDF_BYTES + 1L))
        assertEquals("", store.begin("heic", "big.heic", "image/heic", PreviewStore.MAX_HEIC_BYTES + 1L))

        val okPdf = store.begin("pdf", "ok.pdf", "application/pdf", PreviewStore.MAX_PDF_BYTES)
        assertTrue(PreviewStore.ID_REGEX.matches(okPdf))
        val okHeic = store.begin("heic", "ok.heic", "image/heic", PreviewStore.MAX_HEIC_BYTES)
        assertTrue(PreviewStore.ID_REGEX.matches(okHeic))
    }

    @Test
    fun `chunks in order assemble the exact bytes`() {
        val store = PreviewStore(previewDir, clock = { nowMs })
        val part0 = "First chunk of bytes - ".encodeToByteArray()
        val part1 = "second chunk - ".encodeToByteArray()
        val part2 = "final third chunk.".encodeToByteArray()
        val expected = part0 + part1 + part2

        val id = store.begin("pdf", "doc.pdf", "application/pdf", expected.size.toLong())
        assertTrue(PreviewStore.ID_REGEX.matches(id))

        assertTrue(store.chunk(id, 0, b64(part0)))
        assertFalse(store.complete(id))
        assertTrue(store.chunk(id, 1, b64(part1)))
        assertFalse(store.complete(id))
        assertTrue(store.chunk(id, 2, b64(part2)))
        assertTrue(store.complete(id))

        val assembled = File(previewDir, id).readBytes()
        assertArrayEquals(expected, assembled)
    }

    @Test
    fun `gap, repeat, bad base64, piece over 1 MiB and overflow return false`() {
        val store = PreviewStore(previewDir, clock = { nowMs })
        val piece = byteArrayOf(1, 2, 3, 4)
        val id = store.begin("pdf", "doc.pdf", "application/pdf", 10L)

        // Unknown id
        assertFalse(store.chunk("00000000-0000-4000-8000-000000000000", 0, b64(piece)))

        // Gap at start (seq 1 before seq 0)
        assertFalse(store.chunk(id, 1, b64(piece)))
        assertFalse(store.chunk(id, -1, b64(piece)))

        // Valid seq 0 (4 bytes written)
        assertTrue(store.chunk(id, 0, b64(piece)))

        // Repeat seq 0
        assertFalse(store.chunk(id, 0, b64(piece)))

        // Gap (seq 2 when expecting seq 1)
        assertFalse(store.chunk(id, 2, b64(piece)))

        // Bad base64
        assertFalse(store.chunk(id, 1, "not-valid-base64!!!"))
        assertFalse(store.chunk(id, 1, ""))

        // Overflow: 4 bytes written + 7 bytes = 11 > declared 10
        assertFalse(store.chunk(id, 1, b64(ByteArray(7) { 9 })))

        // Remaining 6 bytes succeed at seq 1
        assertTrue(store.chunk(id, 1, b64(ByteArray(6) { 7 })))
        assertTrue(store.complete(id))

        // Further chunk after completion overflows
        assertFalse(store.chunk(id, 2, b64(byteArrayOf(1))))

        // Piece > 1 MiB (1_048_577 bytes) on a large declared file
        val bigFileId = store.begin("pdf", "large.pdf", "application/pdf", 5L * 1024 * 1024)
        val overOneMiB = ByteArray(PreviewStore.MAX_CHUNK_BYTES + 1) { 0x2A }
        assertFalse(store.chunk(bigFileId, 0, b64(overOneMiB)))

        // Piece of exact 1 MiB succeeds
        val exactOneMiB = ByteArray(PreviewStore.MAX_CHUNK_BYTES) { 0x2A }
        assertTrue(store.chunk(bigFileId, 0, b64(exactOneMiB)))
    }

    @Test
    fun `open before complete returns false`() {
        var opened = 0
        val store = PreviewStore(
            dir = previewDir,
            clock = { nowMs },
            pdfProbe = { 3 },
            openViewer = { opened++; true },
        )
        val payload = "%PDF-1.7\n1 0 obj << /Type /Page >>\n%%EOF".encodeToByteArray()
        val id = store.begin("pdf", "incomplete.pdf", "application/pdf", (payload.size + 10).toLong())
        assertTrue(store.chunk(id, 0, b64(payload)))
        assertFalse(store.complete(id))
        assertFalse(store.open(id))
        assertEquals(0, opened)
    }

    @Test
    fun `sweep deletes files older than 10 minutes and keeps fresh ones`() {
        val store = PreviewStore(previewDir, clock = { nowMs })
        val oldId = store.begin("pdf", "old.pdf", "application/pdf", 3L)
        assertTrue(store.chunk(oldId, 0, b64(byteArrayOf(1, 2, 3))))
        val oldJpg = File(previewDir, "$oldId.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            setLastModified(nowMs)
        }
        assertTrue(File(previewDir, oldId).isFile)
        assertTrue(oldJpg.isFile)

        // Advance 5 minutes and create a fresh entry
        nowMs += 5 * 60_000L
        val freshId = store.begin("heic", "fresh.heic", "image/heic", 3L)
        assertTrue(store.chunk(freshId, 0, b64(byteArrayOf(4, 5, 6))))

        // Advance another 6 minutes: oldId is 11 min old (> 10 min), freshId is 6 min old (<= 10 min)
        nowMs += 6 * 60_000L
        store.sweep()

        assertFalse("old file should be deleted", File(previewDir, oldId).exists())
        assertFalse("old jpg should be deleted", oldJpg.exists())
        assertTrue("fresh file should be kept", File(previewDir, freshId).isFile)

        // Also test sweep triggered automatically on previewBegin
        nowMs += 5 * 60_000L // freshId is now 11 min old
        val newestId = store.begin("pdf", "newest.pdf", "application/pdf", 2L)
        assertFalse("expired freshId should be deleted on next begin", File(previewDir, freshId).exists())
        assertTrue("newestId should exist", File(previewDir, newestId).isFile)
    }

    @Test
    fun `ids never escape the preview directory`() {
        val store = PreviewStore(previewDir, clock = { nowMs })
        val outside = File(tempRoot, "x")
        outside.writeBytes("sensitive".encodeToByteArray())

        for (hostile in listOf("../x", "../../x", "a/../x", "/etc/passwd", "..\\x", "preview/../x")) {
            assertFalse(store.chunk(hostile, 0, b64(byteArrayOf(1, 2, 3))))
            assertFalse(store.complete(hostile))
            assertFalse(store.open(hostile))
            assertEquals("", store.imageUrl(hostile))
            assertFalse(store.delete(hostile))
            assertNull(store.safeFile(hostile))
        }
        assertEquals("sensitive", outside.readText())
    }

    @Test
    fun `delete removes id and jpg files when viewer closes`() {
        val store = PreviewStore(
            dir = previewDir,
            clock = { nowMs },
            heicDecoder = { _, out, _, _ ->
                out.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                true
            },
        )
        val raw = byteArrayOf(1, 2, 3, 4)
        val id = store.begin("heic", "pic.heic", "image/heic", raw.size.toLong())
        assertTrue(store.chunk(id, 0, b64(raw)))
        val url = store.imageUrl(id)
        assertEquals("${WebMessengerAssets.MOUNT}preview/$id.jpg", url)
        assertNotNull(store.readPreviewJpeg("$id.jpg"))
        assertTrue(File(previewDir, id).isFile)
        assertTrue(File(previewDir, "$id.jpg").isFile)

        assertTrue(store.delete(id))
        assertFalse(File(previewDir, id).exists())
        assertFalse(File(previewDir, "$id.jpg").exists())
        assertNull(store.readPreviewJpeg("$id.jpg"))
    }

    // ── 2. Hostile PDF fixtures ──────────────────────────────────────────

    @Test
    fun `truncated and encrypted PDF fixtures return false on open without throwing`() {
        val store = PreviewStore(previewDir, clock = { nowMs })

        // Hostile fixture 1: truncated PDF ("%PDF-1.7\n" + garbage, no %%EOF)
        val truncated = ("%PDF-1.7\n" + "garbage-truncated-stream-\u0000\u00ff").encodeToByteArray()
        val idTrunc = store.begin("pdf", "trunc.pdf", "application/pdf", truncated.size.toLong())
        assertTrue(store.chunk(idTrunc, 0, b64(truncated)))
        assertTrue(store.complete(idTrunc))
        assertFalse("truncated PDF must fail open", store.open(idTrunc))

        // Hostile fixture 2: encrypted PDF stub
        val encrypted = "%PDF-1.7\n1 0 obj\n<< /Filter /Standard /V 4 /R 4 /Length 128 /Encrypt 2 0 R >>\nendobj\n%%EOF"
            .encodeToByteArray()
        val idEnc = store.begin("pdf", "enc.pdf", "application/pdf", encrypted.size.toLong())
        assertTrue(store.chunk(idEnc, 0, b64(encrypted)))
        assertTrue(store.complete(idEnc))
        assertFalse("encrypted PDF must fail open", store.open(idEnc))

        // Fake PdfProbe throwing IOException
        val ioStore = PreviewStore(
            dir = previewDir,
            clock = { nowMs },
            pdfProbe = { throw IOException("corrupt xref") },
        )
        val idIo = ioStore.begin("pdf", "io.pdf", "application/pdf", 4L)
        assertTrue(ioStore.chunk(idIo, 0, b64(byteArrayOf(1, 2, 3, 4))))
        assertFalse(ioStore.open(idIo))

        // Fake PdfProbe throwing SecurityException
        val secStore = PreviewStore(
            dir = previewDir,
            clock = { nowMs },
            pdfProbe = { throw SecurityException("password required") },
        )
        val idSec = secStore.begin("pdf", "sec.pdf", "application/pdf", 4L)
        assertTrue(secStore.chunk(idSec, 0, b64(byteArrayOf(1, 2, 3, 4))))
        assertFalse(secStore.open(idSec))
    }

    @Test
    fun `real PdfRenderer implementation catches both IOException and SecurityException`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("repo root not found")
        val src = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/preview/PreviewStore.kt").readText()

        assertTrue("must use Android PdfRenderer", src.contains("PdfRenderer("))
        assertTrue("must catch IOException", Regex("""catch\s*\(\s*\w+\s*:\s*IOException\s*\)""").containsMatchIn(src))
        assertTrue("must catch SecurityException", Regex("""catch\s*\(\s*\w+\s*:\s*SecurityException\s*\)""").containsMatchIn(src))
    }

    // ── 3. HEIC garbage bytes ────────────────────────────────────────────

    @Test
    fun `garbage HEIC bytes make previewImageUrl return empty string and open return false`() {
        val store = PreviewStore(previewDir, clock = { nowMs })
        val garbage = "not a valid heic image at all".encodeToByteArray()
        val id = store.begin("heic", "bad.heic", "image/heic", garbage.size.toLong())
        assertTrue(store.chunk(id, 0, b64(garbage)))
        assertTrue(store.complete(id))

        assertEquals("", store.imageUrl(id))
        assertFalse(store.open(id))
        assertFalse(File(previewDir, "$id.jpg").exists())

        // Also verify PDF id passed to previewImageUrl returns "" (HEIC only)
        val pdfBytes = "%PDF-1.7\n1 0 obj << /Type /Page >>\n%%EOF".encodeToByteArray()
        val pdfId = store.begin("pdf", "doc.pdf", "application/pdf", pdfBytes.size.toLong())
        assertTrue(store.chunk(pdfId, 0, b64(pdfBytes)))
        assertEquals("", store.imageUrl(pdfId))
    }

    @Test
    fun `WebMessengerAssets serves only preview uuid jpg under MOUNT`() {
        val uuid = "01234567-89ab-4cde-8f01-23456789abcd"
        val ok = WebMessengerAssets.resolve("https://phantom.local/webmsgr/preview/$uuid.jpg") { null }
        assertEquals(Served.Preview("$uuid.jpg"), ok)

        // Raw preview id without .jpg or non-uuid names are blocked
        assertEquals(Served.Blocked, WebMessengerAssets.resolve("https://phantom.local/webmsgr/preview/$uuid") { null })
        assertEquals(Served.Blocked, WebMessengerAssets.resolve("https://phantom.local/webmsgr/preview/other.jpg") { null })
        assertEquals(Served.Blocked, WebMessengerAssets.resolve("https://phantom.local/webmsgr/preview/../$uuid.jpg") { null })
    }

    // ── 4. Reach: WebMessengerBridge declares the 4 @JavascriptInterface methods and delegates to PreviewStore ──

    @Test
    fun `WebMessengerBridge declares the four preview methods with JavascriptInterface and delegates to PreviewStore`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("repo root not found")
        val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()

        val expected = listOf(
            "fun previewBegin(kind: String, name: String, mime: String, size: Long): String" to "previewStore.begin(kind, name, mime, size)",
            "fun previewChunk(id: String, seq: Int, b64: String): Boolean" to "previewStore.chunk(id, seq, b64)",
            "fun previewOpen(id: String): Boolean" to "previewStore.open(id)",
            "fun previewImageUrl(id: String): String" to "previewStore.imageUrl(id)",
        )

        for ((sig, delegation) in expected) {
            val at = bridge.indexOf(sig)
            assertTrue("missing $sig in WebMessengerBridge", at > 0)
            val before = bridge.substring(0, at).trimEnd()
            assertTrue("$sig must be annotated with @JavascriptInterface", before.endsWith("@JavascriptInterface"))
            val window = bridge.substring(at, minOf(bridge.length, at + 220))
            assertTrue("$sig must delegate via $delegation", window.contains(delegation))
        }
    }

    @Test
    fun `viewer activity is registered and not exported`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val manifest = File(dir ?: error("repo root not found"), "app/src/main/AndroidManifest.xml").readText()
        val block = manifest.substringAfter("android:name=\".ui.messenger.preview.PreviewViewerActivity\"", "").substringBefore("/>")
        assertTrue("PreviewViewerActivity missing from manifest", block.isNotEmpty())
        assertTrue("PreviewViewerActivity must not be exported", block.contains("android:exported=\"false\""))
    }

    @Test
    fun `an opened file survives the sweep while the viewer reads it`() {
        val store = PreviewStore(previewDir, clock = { nowMs }, pdfProbe = { 1 }, openViewer = { true })
        val payload = "%PDF-1.7\n1 0 obj << /Type /Page >>\n%%EOF".encodeToByteArray()
        val id = store.begin("pdf", "slow.pdf", "application/pdf", payload.size.toLong())
        assertTrue(store.chunk(id, 0, b64(payload)))
        nowMs += 9 * 60_000L
        assertTrue(store.open(id))
        nowMs += 2 * 60_000L
        store.sweep()
        assertTrue(File(previewDir, id).isFile)
    }

    @Test
    fun `HEIC that claims huge dimensions is refused before decode`() {
        assertTrue(heicTooLarge(20_000, 20_000))
        assertTrue(heicTooLarge(0, 100))
        assertFalse(heicTooLarge(4032, 3024))
    }

    @Test
    fun `viewer hides its window from recents while the app lock is on`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val src = File(dir ?: error("repo root not found"), "app/src/main/java/local/phantom/companion/ui/messenger/preview/PreviewViewerActivity.kt").readText()
        val onCreate = src.substringAfter("override fun onCreate(").substringBefore("setContent")
        assertTrue(onCreate.contains("AppLockStore.of(this).enabled"))
        assertTrue(onCreate.contains("FLAG_SECURE"))
    }

    @Test
    fun `every HEIC decode path checks the pixel cap`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val base = File(dir ?: error("repo root not found"), "app/src/main/java/local/phantom/companion/ui/messenger/preview")
        for (name in listOf("PreviewStore.kt", "PreviewViewerActivity.kt")) {
            val src = File(base, name).readText()
            val decodes = Regex("ImageDecoder\\.decodeBitmap\\(").findAll(src).count()
            val caps = Regex("heicTooLarge\\(info\\.size").findAll(src).count()
            assertEquals("$name: every decodeBitmap needs a heicTooLarge guard", decodes, caps)
        }
    }
}
