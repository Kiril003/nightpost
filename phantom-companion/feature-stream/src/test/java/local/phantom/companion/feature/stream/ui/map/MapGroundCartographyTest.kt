package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.feature.stream.ui.map.atlas.MapTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Земля мусить лишатися землею, яку видно.
 *
 * Тут стережеться те, чого компілятор не бачить і що вже одного разу тихо
 * сталося: колір, який ні від чого не відрізняється, прохід, поставлений не в
 * тому місці, і чужий стиль, у якому шарів удвічі менше.
 */
class MapGroundCartographyTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun source(relative: String): List<String> {
        val file = File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/$relative")
        assertTrue("$relative не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines()
    }

    /* ── Мова підпису ──────────────────────────────────────────────────── */

    @Test
    fun `a label that carries a name is rewritten, and nothing else is`() {
        // Дослівно з https://tiles.openfreemap.org/styles/liberty (16.08): саме
        // цей вираз і дає «Chornobyl Чорнобиль» у два рядки на кожному місті.
        val libertyPlaceLabel =
            """["case",["has","name:nonlatin"],["concat",["get","name:latin"],"\n",["get","name:nonlatin"]],""" +
                """["get","name:latin"]]"""
        assertTrue("підпис місця не переписується — подвоєння лишається", labelsAName(libertyPlaceLabel))
        assertTrue(labelsAName("{name:latin}"))

        // Ці не про імʼя. Переписати їх означає написати назву вулиці там, де
        // має стояти номер дороги або номер будинку.
        assertFalse("щит дороги переписано на назву", labelsAName("""["get","ref"]"""))
        assertFalse("номер будинку переписано на назву", labelsAName("{housenumber}"))
        assertFalse("шар без підпису не має чіпатись", labelsAName(null))
    }

    /* ── Земля має структуру ───────────────────────────────────────────── */

    /**
     * Скільки кольорів мусять розрізнятися оком.
     *
     * Дефект, заради якого цей файл існує: вода `#0F0F13` і забудова `#15151A`
     * на тлі `#0C0C0C` — три числа, які людина бачить як одну чорну пляму.
     * Поріг у 12 одиниць евклідової відстані sRGB відкидає рівно такий випадок
     * (вода проти тла давала там 4.4) і лишає простір тонким переходам.
     */
    private val separation = 12.0

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test
    fun `every kind of ground is told apart from bare land, in both polarities`() {
        listOf("ніч" to groundPalette(isDark = true), "день" to groundPalette(isDark = false))
            .forEach { (name, p) ->
                mapOf(
                    "вода" to p.water,
                    "ліс" to p.wood,
                    "трава" to p.grass,
                    "поле" to p.farmland,
                    "болото" to p.wetland,
                    "забудова" to p.residential,
                    "торгівля" to p.commercial,
                    "промзона" to p.industrial,
                    "карʼєр" to p.rock,
                    "будинок" to p.building,
                ).forEach { (what, colour) ->
                    val d = distance(colour, p.land)
                    assertTrue(
                        "$name: $what ${hex(colour)} проти землі ${hex(p.land)} — " +
                            "відстань ${"%.1f".format(d)}, це одна пляма",
                        d >= separation,
                    )
                }
            }
    }

    @Test
    fun `water is never the same value as the forest beside it`() {
        // Одна з двох речей, які мапа мусить розрізняти навіть крізь червоне
        // світло: де можна їхати і де вода.
        listOf("ніч" to groundPalette(isDark = true), "день" to groundPalette(isDark = false))
            .forEach { (name, p) ->
                assertTrue(
                    "$name: вода ${hex(p.water)} і ліс ${hex(p.wood)} злилися",
                    distance(p.water, p.wood) >= separation,
                )
            }
    }

    /* ── Досяжність і порядок ──────────────────────────────────────────── */

    @Test
    fun `the ground is painted before a single layer of ours is installed`() {
        // Прохід перебирає ВСЕ, що знайде у стилі. Він працює тільки тому, що
        // в мить виклику там лежать самі чужі шари. Поставити його після
        // першого install — і наш маршрут, піни та шари ППО поїдуть під чужу
        // палітру, мовчки й без жодного червоного тесту.
        //
        // ATLAS-0b: порядок більше не з рядків файлу, а з ординала ярусу, тож
        // сторож питає драбину — це те саме правило, тільки тепер його не
        // можна обійти, дописавши виклик у інше місце файлу.
        assertEquals(
            "фарбування ґрунту більше не перший ярус — усе наше піде під чужу палітру",
            MapTier.GROUND_CARTOGRAPHY,
            MapTier.GROUND.first(),
        )
        val contributions = source("ui/map/MapGroundContributions.kt")
        val tierLine = contributions.indexOfFirst { it.contains("MapTier.GROUND_CARTOGRAPHY") }
        assertTrue("ґрунтова контрибуція зникла — перевірка порожня", tierLine >= 0)
        assertTrue(
            "applyGroundCartography більше не встановлюється контрибуцією ґрунту",
            contributions.any { it.contains("applyGroundCartography(") },
        )
    }

    @Test
    fun `night no longer loads the vendor's half-empty style`() {
        // Виміряно 16.08: styles/dark — 47 шарів, styles/liberty — 111. Ні POI,
        // ні парків, утричі менше класів землі. Повернути темний URL означає
        // знову віддати ночі неповну землю; і пакет офлайну, знятий уночі,
        // знову не показувався б удень.
        val urls = openFreeMapUrls()
        assertEquals("ніч і день беруть різні основи", urls.light, urls.dark)

        val root = File(repoRoot(), "feature-stream/src/main")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.contains("styles/dark") && !it.trimStart().startsWith("//") } }
            .map { it.name }
            .toList()
        assertEquals("чужий темний стиль знову завантажується", emptyList<String>(), offenders)
    }

    /* ── Земля, якої чужий стиль не малює ──────────────────────────────── */

    @Test
    fun `a field reads as a field, not as a lawn and not as a village`() {
        // Клас, що вкриває більшість площі країни, у Liberty не має шару взагалі
        // — його малює installGroundBackfill. Але «намалювати» мало: поле, яке
        // не відрізнити від трави, повертає ту саму бежеву пустку, тільки
        // дорожче. Ніч навмисно тихіша (§10 специфікації), тому міряється день.
        val p = groundPalette(isDark = false)
        listOf(
            "трава" to p.grass,
            "забудова" to p.residential,
            "камінь" to p.rock,
        ).forEach { (what, colour) ->
            val d = distance(p.farmland, colour)
            assertTrue(
                "поле ${hex(p.farmland)} проти «$what» ${hex(colour)} — відстань " +
                    "${"%.1f".format(d)}, поле читається як $what",
                d >= separation,
            )
        }
    }

    @Test
    fun `each new ground layer is installed once, not once per style load`() {
        // Стиль перезавантажується на кожній зміні полярності й основи. Шар без
        // воріт `getLayer(id) == null` додався б удруге — і MapLibre малював би
        // два однакові шари, платячи за обидва.
        val backfill = source("ui/map/MapGroundBackfill.kt").joinToString("\n")
        val layers = listOf(
            "GROUND_FARMLAND_LAYER",
            "GROUND_ROCK_LAYER",
            "GROUND_ZONES_LAYER",
            "GROUND_WETLAND_PATTERN_LAYER",
            "BUILDING_SHADOW_LAYER",
            "BUILDING_EDGE_LAYER",
        )
        val unguarded = layers.filterNot { backfill.contains("getLayer($it)") }
        assertEquals(
            "шар додається, не спитавши, чи він уже в стилі: " + unguarded.joinToString(", "),
            emptyList<String>(),
            unguarded,
        )

        val foreign = layers.filterNot { name ->
            Regex("""const val $name = "phantom-""").containsMatchIn(backfill)
        }
        assertEquals(
            "шар без нашого префікса перефарб чужої землі забере собі: " + foreign.joinToString(", "),
            emptyList<String>(),
            foreign,
        )
    }

    @Test
    fun `the sign on the ground is not the same paint as the ground under it`() {
        // Риски болота читаються ФОРМОЮ — але лише доти, доки їх видно на
        // власній заливці.
        listOf("ніч" to true, "день" to false).forEach { (name, dark) ->
            val p = groundPalette(dark)
            val marsh = distance(marshInk(dark), p.wetland)
            assertTrue(
                "$name: риски болота ${hex(marshInk(dark))} на болоті ${hex(p.wetland)} — " +
                    "відстань ${"%.1f".format(marsh)}",
                marsh >= separation,
            )
        }
    }

    @Test
    fun `there is no shadow at night, and the composite says why`() {
        assertNull("вночі малюється тінь — чорне по чорному", buildingShadowColor(isDark = true))

        val day = buildingShadowColor(isDark = false)
            ?: fail("удень тіні немає — будівлі знову без форми") as Nothing
        val lit = distance(over(day, groundPalette(isDark = false).land), groundPalette(isDark = false).land)
        assertTrue(
            "денна тінь ${hex(day)} лягає на землю відстанню ${"%.1f".format(lit)} — її не видно",
            lit >= separation,
        )
        // Те саме число на нічній землі — причина, з якої шар туди не ставиться.
        val unlit = distance(over(day, groundPalette(isDark = true).land), groundPalette(isDark = true).land)
        assertTrue(
            "на нічній землі та сама тінь дала б ${"%.1f".format(unlit)} — якщо це вже " +
                "видно, рішення «вночі тіні немає» треба переміряти, а не успадковувати",
            unlit < separation,
        )
    }

    @Test
    fun `the vendor's grey lego stays dead in both directions`() {
        // Найлегша до втрати правка всієї хвилі: прохід гасить `building-3d`, а
        // Кришталеве місто одним циклом показує ВСІ шари source-layer building —
        // і чужий сірий екструзив воскресає на нічній мапі (8.94:1).
        val kill = body(source("ui/map/MapGroundBackfill.kt"), "internal fun hideVendorVolumes")
        assertTrue(
            "hideVendorVolumes більше не гасить чужий обʼємний шар",
            kill.any { it.contains("VENDOR_BUILDING_3D") } && kill.any { it.contains("Property.NONE") },
        )
        assertTrue(
            "прохід по стилю більше не доходить до чужих екструзивів",
            source("ui/map/MapGroundCartography.kt").any {
                it.contains("FillExtrusionLayer ->") && it.contains("hideVendorVolumes")
            },
        )

        val visible = body(source("ui/map/MapTerrainLayers.kt"), "internal fun setBuildingExtrusionsVisible")
        assertTrue(
            "setBuildingExtrusionsVisible знову вмикає building-3d разом з усіма",
            visible.any { it.contains("VENDOR_BUILDING_3D") },
        )
    }

    /** Тіло функції — від оголошення до першої закривної дужки в нульовій колонці. */
    private fun body(lines: List<String>, header: String): List<String> {
        val start = lines.indexOfFirst { it.startsWith(header) }
        assertTrue("$header не знайдено — перевірка вже нічого не боронить", start >= 0)
        val end = lines.drop(start + 1).indexOfFirst { it == "}" }
        assertTrue("$header не закінчується — перевірка вже нічого не боронить", end >= 0)
        return lines.subList(start, start + end + 2)
    }

    /** Напівпрозора фарба над непрозорою: те, що справді бачить око. */
    private fun over(argb: Int, base: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Int {
            val fg = (argb shr shift) and 0xFF
            val bg = (base shr shift) and 0xFF
            return (fg * a + bg * (1 - a)).roundToInt()
        }
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
