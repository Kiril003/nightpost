package local.phantom.companion.feature.stream.ui.people

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Надісланий маршрут лишає рядок у розмові НАЗАВЖДИ — і саме тому рядок
 * мусить старіти вголос.
 *
 * Живе положення тече без сліду, а маршрут надсилають рукою, тож він і не
 * зникає ([PeerLive.isCargo] не бере його навмисно). Але «лишається» і
 * «лишається свіжим» — різні речі: рядок, який через добу показує ті самі
 * числа й ту саму дію, бреше тим більше, чим довше лежить.
 *
 * Вирок береться з ЧИСЛА, а не з рядка ресурсу: у модульних тестах R —
 * заглушка, і порівняння двох різних `R.string` могло б збігтись на нулях.
 * Тому межу стереже enum, а слово — окремим читанням джерела.
 */
class PeerRouteAgeTest {

    private val until = 1_725_381_000_000L

    @Test
    fun `строк — це межа, а не «майже»`() {
        assertEquals(PeerRouteAge.LIVE, peerRouteAge(until, until - 1L))
        // Рівно на строку вже протух: та сама межа, за якою маршрут викидає
        // приймач і перестає малювати мапа. Інакше три поверхні розійшлись би.
        assertEquals(PeerRouteAge.EXPIRED, peerRouteAge(until, until))
        assertEquals(PeerRouteAge.EXPIRED, peerRouteAge(until, until + 1L))
    }

    @Test
    fun `рядок після строку каже про це словом, а не мовчить`() {
        val bubble = source(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/" +
                "PeerRouteBubble.kt",
        )

        assertTrue(
            "бульбашка не питає про вік — рядок доби давнини виглядає свіжим",
            "peerRouteAge(route.expiresAtMs" in bubble,
        )
        assertTrue(
            "назва рядка не міняється після строку",
            "peer_route_title_expired" in bubble,
        )
        // Дія лишається — але НЕ як жива: слово мусить сказати, що це вже
        // перепланування, а не поїздка чужим свіжим планом.
        assertTrue(
            "дія після строку прикидається живою",
            "peer_route_take_expired" in bubble,
        )
        assertTrue(
            "вирок нікуди не заходить — слово обране не за віком",
            "if (expired)" in bubble,
        )
    }

    @Test
    fun `слово про строк є обома мовами`() {
        val uk = source("feature-stream/src/main/res/values-uk/strings.xml")
        val en = source("feature-stream/src/main/res/values/strings.xml")

        listOf("peer_route_title_expired", "peer_route_take_expired").forEach {
            assertTrue("немає українського рядка $it", "\"$it\"" in uk)
            assertTrue("немає англійського рядка $it", "\"$it\"" in en)
        }
    }

    private fun source(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь репозиторію не знайдено") as Nothing, rel)
        assertTrue("сторож осліп: немає $rel", f.isFile)
        return f.readText()
    }
}
