package local.phantom.companion.feature.stream.ui.map

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.geo.sky.SkyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-1 — стик між випеченим асетом і кодом, що на нього спирається.
 *
 * `:tools:obsidian` доводить, що стиль правильний сам по собі. Тут доводиться
 * інше: що ЦЕЙ модуль домовився з ним про ті самі імена. Обидві половини
 * компілюються окремо і не бачать одна одну — саме тому розбіжність між ними
 * не падає збіркою, а тихо малює маршрут під землею.
 */
class ObsidianStyleContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Джерело без коментарів — сторож читає код, а не те, що про нього
     *  написали поруч (той самий прийом, що в MapThemeWiringTest). */
    private fun sourceOf(name: String): String {
        val file = File("src/main/java/local/phantom/companion/feature/stream/ui/map/$name")
        assertTrue("$name не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
        }
    }

    private fun assetOf(styleUrl: String): File {
        assertTrue("шлях стилю більше не асет: $styleUrl", styleUrl.startsWith("asset://"))
        val file = File("src/main/assets/${styleUrl.removePrefix("asset://")}")
        assertTrue("асет не випечено: ${file.absolutePath} — запусти :tools:obsidian:bake", file.isFile)
        return file
    }

    private fun layersOf(styleUrl: String) =
        json.parseToJsonElement(assetOf(styleUrl).readText()).jsonObject["layers"]!!.jsonArray
            .map { it.jsonObject }

    private fun id(l: Map<String, kotlinx.serialization.json.JsonElement>) =
        l["id"]!!.jsonPrimitive.content

    /** Усі чотири варіанти — один склад шарів. Мінімал стишує чорнило, а не
     *  вимітає зміст, і саме це тут прибито. */
    @Test
    fun `every variant ships, and ships the same layers`() {
        val night = layersOf(OBSIDIAN_NIGHT_STYLE).map(::id)
        assertTrue("стиль підозріло малий", night.size >= 45)
        for (style in listOf(OBSIDIAN_DAY_STYLE, OBSIDIAN_DUSK_STYLE, OBSIDIAN_MINIMAL_STYLE)) {
            assertEquals("$style розійшовся з ніччю складом шарів", night, layersOf(style).map(::id))
        }
    }

    /**
     * Три землі автоматичного шляху, кожна за своєю фазою.
     *
     * Сутінковий асет випікався з ATLAS-1 і нікому не діставався: полярність
     * їхала сюди `Boolean`-ом, тож `obsidian-dusk.json` лежав у застосунку
     * мертвим вантажем. Золота година свідомо лишається ДНЕМ — інакше земля
     * темніє до заходу, а це вже спіймали на пристрої.
     */
    @Test
    fun `the auto path resolves one ground per phase`() {
        val urls = obsidianUrls()
        assertEquals(OBSIDIAN_DAY_STYLE, urls.resolve(SkyPhase.DAY))
        assertEquals("золота година потемніла — земля піде в ніч до заходу", OBSIDIAN_DAY_STYLE, urls.resolve(SkyPhase.GOLDEN))
        assertEquals(OBSIDIAN_DUSK_STYLE, urls.resolve(SkyPhase.DUSK))
        assertEquals(OBSIDIAN_NIGHT_STYLE, urls.resolve(SkyPhase.NIGHT))
    }

    /** Чужий постачальник має рівно дві землі, і просити в нього третю означало
     *  б удавати те, чого він не має. Сутінки в нього — ніч, без вигадок. */
    @Test
    fun `a foreign provider is never asked for a ground it does not have`() {
        // MapTiler, бо в нього світлий і темний — РІЗНІ URL: на постачальнику з
        // однією основою ця перевірка проходила б і зламаною.
        val foreign = mapTilerUrls("k")
        assertTrue("перевірка втратила сенс: у постачальника одна основа", foreign.light != foreign.dark)
        assertEquals("сутінки в чужого стилю — це його ніч, без вигадок", foreign.dark, foreign.resolve(SkyPhase.DUSK))
        assertEquals(foreign.dark, foreign.resolve(SkyPhase.NIGHT))
        assertEquals(foreign.light, foreign.resolve(SkyPhase.GOLDEN))
        assertEquals(foreign.light, foreign.resolve(SkyPhase.DAY))
    }

    /** МІНІМАЛ на рейці мусить вести до ВЛАСНОГО файла: доти він мовчки віддавав
     *  день, тобто рейка показувала три написи й дві мапи. */
    @Test
    fun `the minimal rail option has a style of its own`() {
        assertTrue("мінімал знову бере денний асет", OBSIDIAN_MINIMAL_STYLE != OBSIDIAN_DAY_STYLE)
        assertTrue(
            "рейка МІНІМАЛ не веде до obsidian-minimal — напис бреше про те, що вмикає",
            sourceOf("MapCanvas.kt").contains("MapBasemap.MINIMAL -> OBSIDIAN_MINIMAL_STYLE"),
        )
    }

    /**
     * Форсована основа НЕ перезавантажується від сонця.
     *
     * Третя земля дала сонцю ще одну нагоду смикнути стиль, тож старий баг
     * варто прибити на місці, поки він не воскрес: ключ привʼязки на
     * примусовому шляху — саме імʼя вибору й нічого більше. Варто вписати туди
     * фазу «для повноти», і захід сонця почне перевантажувати стиль під
     * оператором, який свідомо просив ТЕМНУ й нікуди її не міняв.
     */
    @Test
    fun `a forced basemap keys on the choice alone, never on the sun`() {
        val declaration = sourceOf("MapScreen.kt").substringAfter("val styleKey =", "")
        assertTrue("styleKey більше не оголошено в MapScreen", declaration.isNotEmpty())
        val forced = declaration.substringBefore("?:")
        assertTrue("примусовий шлях більше не читає сам вибір", forced.contains("basemapOverride"))
        for (solar in listOf("skyPhase", "isDarkTheme", "sky?", "SkyPhase")) {
            assertTrue(
                "у ключ примусової основи заліз $solar — захід сонця перевантажить чужий вибір",
                !forced.contains(solar),
            )
        }
        assertTrue(
            "автоматичний шлях перестав ключуватись на розвʼязаному URL — три землі зіллються в одну",
            declaration.substringAfter("?:").take(200).contains("autoStyleUrl"),
        )
    }

    @Test
    fun `the ground layer is first, and is the signature speaksObsidian reads`() {
        for (style in listOf(OBSIDIAN_NIGHT_STYLE, OBSIDIAN_DAY_STYLE)) {
            val layers = layersOf(style)
            assertEquals(
                "перший шар більше не тло — installNotDownloadedHatch і installHillshade " +
                    "вставляються ВІДРАЗУ НАД ним",
                OBSIDIAN_GROUND_LAYER,
                id(layers.first()),
            )
            assertEquals("background", layers.first()["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `every label sits above every fill and line`() {
        // installBuildingExtrusions і installWaterTruth шукають ПЕРШИЙ
        // символьний шар і сідають під нього. Символ, загублений серед заливок,
        // означав би екструзії під землею.
        val types = layersOf(OBSIDIAN_NIGHT_STYLE).map { it["type"]!!.jsonPrimitive.content }
        val first = types.indexOf("symbol")
        assertTrue("у стилі немає підписів", first >= 0)
        assertTrue(
            "символьний шар опинився серед заливок — якір «під перший символ» зламано",
            types.drop(first).all { it == "symbol" },
        )
    }

    @Test
    fun `exactly one vector source, because the app picks the first one it finds`() {
        val sources = json.parseToJsonElement(assetOf(OBSIDIAN_NIGHT_STYLE).readText())
            .jsonObject["sources"]!!.jsonObject
        assertEquals("джерел мусить бути рівно одне", 1, sources.size)
        val only = sources.values.single().jsonObject
        assertEquals("vector", only["type"]!!.jsonPrimitive.content)
        assertTrue(
            "з асета зникла атрибуція OSM — ODbL не залежить від того, чий це стиль",
            only["attribution"]!!.jsonPrimitive.content.contains("OpenStreetMap"),
        )
    }

    @Test
    fun `all four repainters stand down on our own ground`() {
        // Ворота стоять УСЕРЕДИНІ проходів, а не в місці виклику: контрибуції
        // лишаються оголошеними (MapDepthFirewallTest і MapThemeWiringTest
        // читають саме той файл), але над власним стилем не роблять нічого.
        val repainters = mapOf(
            "MapGroundCartography.kt" to "applyGroundCartography",
            "MapLabelVoice.kt" to "applyLabelVoice",
            "MapGroundBackfill.kt" to "installGroundBackfill",
            "MapGroundFacts.kt" to "installGroundFacts",
        )
        val blind = repainters.filterNot { (file, _) ->
            File("src/main/java/local/phantom/companion/feature/stream/ui/map/$file")
                .readText()
                .contains("speaksObsidian()) return")
        }
        assertEquals(
            "перефарбувальник ${blind.values.joinToString(", ")} пише поверх власної " +
                "картографії числами, підібраними під чужу",
            emptyMap<String, String>(),
            blind,
        )
    }

    @Test
    fun `the offline pack key never follows polarity`() {
        val night = MapStyleConfig(TileProviderId.OBSIDIAN, null, null).resolveStyleUrl(isDark = true)
        val day = MapStyleConfig(TileProviderId.OBSIDIAN, null, null).resolveStyleUrl(isDark = false)
        assertTrue("полярність більше не два локальні файли", night != day)
        assertEquals(
            "ключ пакета розійшовся з джерелом тайлів — скачане до ATLAS-1 стало «незавантаженим»",
            openFreeMapUrls().light,
            offlineStyleUrlFor(TileProviderId.OBSIDIAN, null, null),
        )
    }
}
