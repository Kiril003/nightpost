package local.phantom.companion.service

import local.phantom.companion.core.net.peer.PeerFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeLabelsTest {
    @Test
    fun voiceAndVideoNotesAreNotFiles() {
        assertEquals(NoticeLabels.Attach.VOICE, NoticeLabels.attachKind(PeerFile.VOICE, "audio/ogg"))
        assertEquals(NoticeLabels.Attach.VIDEO, NoticeLabels.attachKind(PeerFile.VIDEO_NOTE, "video/mp4"))
        assertEquals(NoticeLabels.Attach.PHOTO, NoticeLabels.attachKind(PeerFile.FILE, "image/png"))
        assertEquals(NoticeLabels.Attach.FILE, NoticeLabels.attachKind(PeerFile.FILE, "application/pdf"))
    }

    @Test
    fun unknownSenderGetsNeutralLabelNotHex() {
        assertEquals("Невідомий", NoticeLabels.senderName(null, "Невідомий"))
        assertEquals("Невідомий", NoticeLabels.senderName("  ", "Невідомий"))
        assertEquals("Ola", NoticeLabels.senderName("Ola", "Невідомий"))
    }

    @Test
    fun shortcutFallbackIsNotMe() {
        assertEquals("Невідомий", NoticeLabels.shortcutLabel(null, "", "Невідомий"))
        assertEquals("Група", NoticeLabels.shortcutLabel("Група", "Ola", "Невідомий"))
    }

    @Test
    fun groupToastOnlyOnFailure() {
        var posted: String? = null
        var failed = false
        NoticeLabels.requestGroup({ posted = it }, { failed = true }, "g1")
        assertEquals("g1", posted)
        assertFalse(failed)
        NoticeLabels.requestGroup({ error("x") }, { failed = true }, "g1")
        assertTrue(failed)
    }

    private fun src(path: String): String {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "core-net").isDirectory) dir = dir.parentFile
        return java.io.File(dir, path).readText()
    }

    @Test
    fun wiredIntoRealEntryPoints() {
        val c = src("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        assertTrue(c.contains("NoticeLabels.attachKind("))
        assertTrue(c.contains("NoticeLabels.senderName("))
        assertTrue(c.contains("MarkerLines.markerOnlyKind("))
        assertFalse(c.contains("PeerRoster.shortId(notice.fromId)"))
        val m = src("app/src/main/java/local/phantom/companion/MainActivity.kt")
        assertTrue(m.contains("NoticeLabels.requestGroup("))
        val n = src("app/src/main/java/local/phantom/companion/service/PeerNotifications.kt")
        assertTrue(n.contains("NoticeLabels.shortcutLabel("))
    }
}
