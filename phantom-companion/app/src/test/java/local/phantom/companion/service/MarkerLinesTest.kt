package local.phantom.companion.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerLinesTest {
    @Test
    fun stripsEveryMarkerKind() {
        assertEquals("Завдання · Лаб 4", MarkerLines.strip("Завдання · Лаб 4\n⟦edu1:abc_-⟧"))
        assertEquals("Замовлення", MarkerLines.strip("Замовлення\nPH-ORD:eyJ"))
        assertEquals("Статус", MarkerLines.strip("Статус\nPH-ORS:eyJ"))
        assertEquals("Змінa", MarkerLines.strip("Змінa\n[np:swap:1]"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        assertEquals("привіт\nяк ти", MarkerLines.strip("привіт\nяк ти"))
        assertEquals("у тексті [np:x] посеред", MarkerLines.strip("у тексті [np:x] посеред"))
    }

    @Test
    fun cutOffTagIsStrippedToo() {
        assertEquals("Завдання", MarkerLines.strip("Завдання\n⟦edu1:abc"))
    }

    @Test
    fun stripsMarkerLinesWhereverTheyAre() {
        assertEquals("Завдання\nдеталі", MarkerLines.strip("Завдання\n⟦edu1:abc⟧\nдеталі"))
        assertEquals("a\nb", MarkerLines.strip("PH-ORD:eyJ\na\nb\nPH-ORS:x"))
    }

    @Test
    fun markerOnlyBodyHasAKind() {
        assertEquals(MarkerLines.Kind.ORDER, MarkerLines.markerOnlyKind("PH-ORD:eyJ"))
        assertEquals(MarkerLines.Kind.UPDATE, MarkerLines.markerOnlyKind("[np:swap:1]"))
        assertEquals("", MarkerLines.strip("⟦edu1:abc⟧"))
        assertEquals(null, MarkerLines.markerOnlyKind("Завдання\n⟦edu1:abc⟧"))
        assertEquals(null, MarkerLines.markerOnlyKind("привіт"))
    }

    @Test
    fun stickerShowsItsEmojiNotTheTag() {
        assertEquals("☕", MarkerLines.sticker("☕\n⟦st1:d1/tea⟧"))
        assertEquals("☕", MarkerLines.strip("☕\n⟦st1:d1/tea⟧"))
        assertEquals(null, MarkerLines.sticker("привіт\n⟦st1:d1/tea⟧"))
        assertEquals(null, MarkerLines.sticker("☕"))
        assertEquals(MarkerLines.Kind.STICKER, MarkerLines.markerOnlyKind("⟦st1:d1/tea⟧"))
    }

    @Test
    fun notificationLineNamesTheSticker() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val src = java.io.File(dir!!, "app/src/main/java/local/phantom/companion/service/PeerNotifications.kt").readText()
        val fn = src.substringAfter("fun notifyMessage(").substringBefore("val active")
        org.junit.Assert.assertTrue("плашка не каже «Стікер»", fn.contains("MarkerLines.sticker(body)") && fn.contains("peer_marker_sticker"))
    }
}
