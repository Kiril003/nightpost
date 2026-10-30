package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.feature.stream.ui.map.atlas.MapTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Голос мапи мусить лунати, і лунати після ґрунту.
 *
 * Обидві помилки тут мовчазні. Не викликати [applyLabelVoice] взагалі — і
 * підписи лишаються постачальницькими, без жодного червоного тесту (це той
 * самий клас дефекту, яким у цьому дереві вже вісім файлів компілюються,
 * лежать в APK і не існують на екрані). Викликати ПЕРЕД
 * [applyGroundCartography] — і та перепише `text-field` разом зі стеком
 * шрифту, знісши кегель, регістр і розрядку, які цей прохід щойно поклав.
 */
class MapLabelVoiceTest {

    private fun canvasLines(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію з feature-stream/ не знайдено") as Nothing
        val file = File(
            root,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/" +
                "MapGroundContributions.kt",
        )
        assertTrue("MapGroundContributions.kt не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
    }

    @Test
    fun `the label voice is applied, and only after the ground`() {
        val lines = canvasLines()
        assertTrue(
            "applyLabelVoice не встановлюється жодною контрибуцією — мапа говорить чужим голосом",
            lines.any { it.contains("applyLabelVoice(") },
        )
        // ATLAS-0b: черга — це ординал ярусу, не рядок у файлі. Ґрунт кладе
        // поле підпису й стек шрифту, голос — кегель, регістр і ореол;
        // зворотний порядок зніс би кегель разом зі стеком.
        assertEquals(
            "голос підпису більше не одразу за ґрунтом",
            MapTier.GROUND_CARTOGRAPHY.ordinal + 1,
            MapTier.LABEL_VOICE.ordinal,
        )
    }

    @Test
    fun `the voice is handed the live polarity`() {
        val voiceCall = canvasLines().first { it.contains("applyLabelVoice(") }
        assertTrue(
            "applyLabelVoice не отримує живої полярності — ореол уночі мусить бути ширшим",
            voiceCall.contains(MapTheme.LIVE),
        )
    }

    // ── драбина класу × зуму ─────────────────────────────────────────────
    //
    // `match(class, interpolate(zoom))` рушій відкидає разом із `text-size`,
    // тож interpolate тепер один, а клас поїхав у виходи зупинок. Обмін
    // законний рівно тому, що зупинки — ОБʼЄДНАННЯ зламів усіх кривих: між
    // сусідніми зламами кожна крива лінійна. Ці два тести і є та підстава.

    private fun interp(curve: List<Pair<Int, Float>>, z: Double): Double {
        if (z <= curve.first().first) return curve.first().second.toDouble()
        if (z >= curve.last().first) return curve.last().second.toDouble()
        val hi = curve.indexOfFirst { it.first >= z }
        val (z0, v0) = curve[hi - 1]
        val (z1, v1) = curve[hi]
        return v0 + (v1 - v0) * (z - z0) / (z1 - z0)
    }

    @Test
    fun `sampling at the union of knots reproduces every ladder exactly`() {
        val city = listOf(4 to 11f, 7 to 13f, 11 to 16f, 14 to 18f)
        val town = listOf(7 to 10f, 10 to 12f, 14 to 14f)
        val small = listOf(9 to 9.5f, 12 to 11f, 15 to 12.5f)
        val union = (city + town + small).map { it.first }.distinct().sorted()

        for (curve in listOf(city, town, small)) {
            val sampled = union.map { it to rampAt(curve, it) }
            var z = 0.0
            while (z <= 22.0) {
                assertEquals(
                    "крива поїхала на z$z після перекладу на спільні зупинки",
                    interp(curve, z),
                    interp(sampled, z),
                    1e-6,
                )
                z += 0.5
            }
        }
    }

    @Test
    fun `a ladder holds its edge stop beyond its own range`() {
        val curve = listOf(10 to 10f, 20 to 20f)
        assertEquals(10f, rampAt(curve, 0), 1e-6f)
        assertEquals(10f, rampAt(curve, 10), 1e-6f)
        assertEquals(15f, rampAt(curve, 15), 1e-6f)
        assertEquals(20f, rampAt(curve, 40), 1e-6f)
    }
}
