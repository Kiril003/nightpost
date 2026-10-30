package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Смуга відмови мусить бути ДОСЯЖНОЮ, а не лише написаною.
 *
 * Вона була збудована й відрендерена, і жоден рядок у дереві її не будував:
 * провал маршруту йшов у `postNotice` — пігулкою, яка спливає й зникає. Людина
 * лишалась стояти без маршруту, без причини і без напрямку, а тест на смугу був
 * би зелений, бо смуга сама по собі працює. Це найчастіша вада цього репозиторію,
 * тому тут перевіряється не вигляд, а ланцюг: хто будує → хто передає → хто малює.
 */
class RouteFailureBandTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun source(name: String): String {
        val f = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$name",
        )
        assertTrue("$name не знайдено — перевірка вже нічого не боронить", f.isFile)
        return f.readLines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `відмову хтось будує`() {
        assertTrue(
            "жоден рядок не будує RouteFailure — смуга знову недосяжна, " +
                "а провал маршруту знову лише спливає пігулкою",
            source("MapRouteCoordinator.kt").contains("_routeFailure.value = RouteFailure("),
        )
    }

    @Test
    fun `відмову хтось передає в смугу`() {
        val chrome = source("MapChrome.kt")
        assertTrue(
            "MapChrome не передає failure = routeFailure — смуга отримує лише null",
            chrome.contains("failure = routeFailure"),
        )
        assertTrue(
            "гілка нижнього кластера не вмикається на самій відмові: коли маршруту " +
                "немає й розрахунок скінчився, смузі нема де зʼявитись",
            chrome.contains("routeFailure != null ->"),
        )
    }

    @Test
    fun `відмова знімається новою спробою і скиданням`() {
        val coord = source("MapRouteCoordinator.kt")
        assertEquals(
            "відмова мусить гаситись рівно двічі — на початку нової спроби і в " +
                "clearRoute; інакше вона переживе маршрут, який уже будується",
            2,
            Regex("_routeFailure\\.value = null").findAll(coord).count(),
        )
    }

    /* ── Числа, які смуга показує, а не лише малює ────────────────────────── */

    @Test
    fun `азимут на чотири сторони світу`() {
        // Схід і захід — саме той випадок, де очевидна відповідь неправильна:
        // великим колом із 50° пн. ш. «рівно на схід» дає 89.6°, бо дуга
        // вигинається до полюса. Тому азимут ОКРУГЛЮЄТЬСЯ, а не обрізається:
        // обрізання показало б 89 і зробило б із заокруглення намір.
        val kyiv = 50.4501 to 30.5234
        assertEquals(0, bearingDegrees(kyiv, 51.4501 to 30.5234))
        assertEquals(90, bearingDegrees(kyiv, 50.4501 to 31.5234))
        assertEquals(180, bearingDegrees(kyiv, 49.4501 to 30.5234))
        assertEquals(270, bearingDegrees(kyiv, 50.4501 to 29.5234))
    }

    @Test
    fun `азимут ніколи не виходить за коло`() {
        // 359.7° округлилось би до 360 — напрямку, якого не існує.
        val p = 50.4501 to 30.5234
        val all = listOf(
            51.4501 to 30.5200, 51.4501 to 30.5234, 50.4501 to 31.5234,
            49.4501 to 30.5234, 50.4501 to 29.5234, 51.0 to 30.5230,
        ).map { bearingDegrees(p, it) }
        all.forEach { assertTrue("азимут $it° поза колом 0..359", it in 0..359) }
    }

    @Test
    fun `пряма Київ — Львів збігається з відомою відстанню`() {
        // 468 км по великому колу; допуск 5 км на округлення радіуса Землі.
        val m = straightMeters(50.4501 to 30.5234, 49.8397 to 24.0297)
        assertTrue("Київ–Львів по прямій = ${m.toInt()} м, а має бути ≈468 км", m in 463_000.0..473_000.0)
    }

    @Test
    fun `нульова відстань не дає ні NaN, ні негативу`() {
        val p = 50.4501 to 30.5234
        assertEquals(0.0, straightMeters(p, p), 0.001)
    }
}
