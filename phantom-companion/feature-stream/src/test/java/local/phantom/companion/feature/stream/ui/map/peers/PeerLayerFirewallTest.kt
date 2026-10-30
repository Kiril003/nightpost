package local.phantom.companion.feature.stream.ui.map.peers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ДВА ЗАКОНИ КОНТРАКТУ, ЯКІ КОМПІЛЯТОР НЕ БАЧИТЬ.
 *
 * **№1 — жодна чужа точка не стає ключем зовнішнього запиту.** Ні тайл по її
 * координатах, ні реверс-геокод, ні Overpass. Це не «ми так не робимо» — це
 * має бути НЕМОЖЛИВО за побудовою: у шара контактів немає жодної мережевої
 * залежності взагалі, і додати її треба буде спершу тут.
 *
 * **№2 — чужу точку не інтерполюють.** Інерційно вести можна лише себе.
 * Анімований перехід між двома тіками виглядає гарно рівно доти, доки хтось не
 * повірить у рух, якого не було: розрив тіків — це чесний розрив, а не привід
 * домалювати дорогу між ними.
 *
 * GATE LAW: сторож читає джерело файлом, повз up-to-date Gradle — на валі
 * запускати з `--rerun`.
 */
class PeerLayerFirewallTest {

    /** Дороги в мережу. Жодної з них у цьому каталозі бути не може. */
    private val networkMarkers = listOf(
        "core.net", "okhttp", "retrofit", "java.net.", "HttpURL",
        "Overpass", "Nominatim", "Photon", "geocode", "TileProvider",
    )

    /** Усе, що вміє рухати щось між двома значеннями по часу. */
    private val animationMarkers = listOf(
        "ValueAnimator", "ObjectAnimator", "Animatable", "animateFloat",
        "animateDpAsState", "animateFloatAsState", "rememberInfiniteTransition",
        "infiniteRepeatable", "animateColorAsState", "updateTransition",
    )

    /** Чужий домен: месенджер кладе точку, мапа читає. Більше нічого. */
    private val messengerMarkers = listOf("messenger", "ratchet", "double_ratchet", "clientId")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun peerSources(): List<File> {
        val dir = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/peers",
        )
        assertTrue("каталог peers/ не знайдено — сторож нічого не боронить", dir.isDirectory)
        val files = dir.listFiles { f: File -> f.extension == "kt" }?.toList().orEmpty()
        assertTrue("у peers/ немає джерел — сторож осліп", files.size >= 3)
        return files
    }

    private fun offenders(markers: List<String>): List<String> = peerSources().flatMap { file ->
        val body = file.readText()
        markers.filter { body.contains(it, ignoreCase = true) }.map { "${file.name}: $it" }
    }

    @Test
    fun `the contact layer has no way to reach the network at all`() {
        assertEquals(
            "закон №1: чужа точка не стає ключем зовнішнього запиту. Немає локального " +
                "тайла — чесна порожнеча, а не запит по чужих координатах.\n",
            emptyList<String>(),
            offenders(networkMarkers),
        )
    }

    @Test
    fun `no animator ever touches a peer position`() {
        assertEquals(
            "закон №2: чужу точку не інтерполюємо. Розрив тіків — чесний розрив, " +
                "а не вигаданий рух між ними.\n",
            emptyList<String>(),
            offenders(animationMarkers),
        )
    }

    @Test
    fun `the map side knows nothing about the messenger that fills it`() {
        assertEquals(
            "мапа читає PeerPoint і більше нічого: месенджерний тип тут склеїв би " +
                "два боки назавжди.\n",
            emptyList<String>(),
            offenders(messengerMarkers),
        )
    }

    @Test
    fun `the anonymous crowd plane never mixes into the named one`() {
        // Закон №7: іменовані контакти і `live_marks` — різні шари. Спільне
        // джерело GeoJSON злило б їх у одну площину непомітно.
        assertEquals(
            "іменований контакт не є краудовою міткою і не ділить із нею джерело\n",
            emptyList<String>(),
            offenders(listOf("live_marks", "HAZARD_", "SpatialLayer", "EventMark")),
        )
    }
}
