package local.phantom.companion.core.net.pack

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог кузні їде окремим файлом саме тому, що незнайомий рід валить розбір
 * ЦІЛКОМ. Цей набір тримає обидві половини домовленості: старий світовий
 * каталог не мусить постраждати, а новий — не мусить брехати половиною списку.
 *
 * СТОРОЖ ПЕРЕПИСАНО 16.09, І ОСЬ ЧОМУ. Попередня редакція звіряла роди зі
 * СВОЇМ ЖЕ переліком `listOf("geocoder", "terrain_dem", "basemap_pmtiles")` —
 * тобто питала код, чи згоден він сам із собою. Відповідь була «так» весь
 * час, поки роздача публікувала `GEOCODER`, `DEM`, `BASEMAP_PMTILES` і
 * `TRANSIT_GTFS`, а телефон не бачив ЖОДНОГО з 49 живих пакетів. Тепер рядки
 * нижче — дослівні з `try.phantom-os.dev/packs/forge-catalog.json`: сторож
 * звіряється з дротом, а не з собою.
 *
 * ЗНІМОК ПЕРЕЗНЯТО 18.09, коли роздача набула пʼятого роду: 24 країни
 * `COUNTRY_PLACES` поїхали в той самий каталог, і рядків стало 73. Знімок
 * 16.09 прибрано, а не лишено поруч: два знімки, обидва названі «живими»,
 * означали б, що один із них бреше, і невідомо котрий.
 *
 * ПЕРЕЗНЯТО ЩЕ РАЗ 19.09, і цього разу змінились не роди, а ЧИСЛА: 25 із 26
 * геокодерів мали в каталозі й манiфестах байти, спечені до перепікання
 * 13.09, тож телефон відхиляв кожне завантаження. Документи перегенеровано
 * під наявні файли й перепідписано тим самим ключем; рядків так само 73.
 */
class ForgeCatalogWireTest {

    private val signer = WorldPackSigner()

    /**
     * Дослівний рядок живого каталогу (2026-09-19), лише без `sig` документа.
     *
     * Числа тут змінились 19.09 і не через нову піч: до 13.09 каталог і
     * манiфести оголошували байти, спечені ДО перепікання, а роздавались уже
     * інші — `PackFetcher` бачив розбіжність і викидав завантажене
     * (`PackFailure.Corrupt`). Лікували документи, не байти: 25 манiфестів
     * перегенеровано під наявні файли й перепідписано, і `bytes` із `bakedAt`
     * у каталозі поїхали слідом.
     */
    private val geocoderRow =
        """{"bakedAt":"2026-09-13T02:29:29Z","bytes":960057344,"country":"PL",""" +
            """"id":"geocoder-pl","kind":"GEOCODER","labelUa":"Польща","minAppBuild":8}"""

    private fun body(vararg rows: String) = """{"packs":[${rows.joinToString(",")}]}"""

    private fun client(text: String, requested: MutableList<String> = mutableListOf()) =
        WorldPackCatalogClient(
            baseUrl = { "https://packs.example" },
            publicKeyHex = { signer.publicHex },
            engine = MockEngine { request ->
                requested += request.url.encodedPath
                respond(text, HttpStatusCode.OK, headersOf())
            },
        )

    @Test
    fun `a signed forge catalog is read into rows`() = runTest {
        val seen = mutableListOf<String>()
        val result = client(signer.sign(body(geocoderRow)), seen).forgeCatalog()
        assertTrue(result is ForgeCatalog.Ok)
        val packs = (result as ForgeCatalog.Ok).packs
        assertEquals(1, packs.size)
        assertEquals("geocoder-pl", packs.first().id)
        assertEquals(ForgePackKind.GEOCODER, packs.first().kind)
        assertEquals("Польща", packs.first().labelUa)
        assertTrue("читається саме forge-catalog.json", seen.single().endsWith("/forge-catalog.json"))
    }

    /**
     * Роди — ДОСЛІВНО ті, що лежать у живому каталозі. Перелік знято з
     * `try.phantom-os.dev/packs/forge-catalog.json` 18.09: GEOCODER ×26,
     * DEM ×18, COUNTRY_PLACES ×24, BASEMAP_PMTILES ×4, TRANSIT_GTFS ×1 —
     * разом 73 рядки. Пʼятий рід уже не «майбутній»: `publish_country_packs.py`
     * виклав ним 24 країни, і саме цей рядок ловить розбіжність ДО того, як
     * телефон побачить нечитний каталог.
     */
    @Test
    fun `every kind the forge bakes has a name on this side`() = runTest {
        val rows = listOf("GEOCODER", "DEM", "BASEMAP_PMTILES", "TRANSIT_GTFS", "COUNTRY_PLACES")
            .mapIndexed { i, kind ->
                """{"id":"p$i","kind":"$kind","country":"MD","labelUa":"рядок $i","bytes":${1000 + i}}"""
            }
        val result = client(signer.sign(body(*rows.toTypedArray()))).forgeCatalog()
        assertEquals(
            listOf(
                ForgePackKind.GEOCODER,
                ForgePackKind.TERRAIN_DEM,
                ForgePackKind.BASEMAP_PMTILES,
                ForgePackKind.TRANSIT_GTFS,
                ForgePackKind.COUNTRY_PLACES,
            ),
            (result as ForgeCatalog.Ok).packs.map { it.kind },
        )
    }

    /**
     * УВЕСЬ ЖИВИЙ КАТАЛОГ, ЗНЯТИЙ ІЗ РОЗДАЧІ.
     *
     * `forge-catalog-live-19sep.json` — байт у байт те, що віддає роздача
     * після перепідписання геокодерів 19.09: 73 підписані рядки, 16 312
     * байт. Виміряно на попередньому знімку: СТАРИМИ іменами розбір падав
     * цілком (жодного пакета на телефоні).
     *
     * Знімок 18.09 замінено, а не доповнено: він ніс `bytes` геокодерів,
     * спечених до 13.09 — 14,10 ГБ проти справжніх 12,11 ГБ. Дзеркало, яке
     * обіцяє «байт у байт із роздачею» і показує документ, якого там більше
     * немає, зеленітиме рівно доти, доки хтось не повірить йому в полі.
     *
     * Лічильник тут НЕ прибитий числом 73: піч додає країни, і тест, що
     * червоніє на кожну нову країну, привчають ігнорувати. Звіряється інше —
     * що розібралось СТІЛЬКИ Ж рядків, скільки їх у файлі. Саме це ламається,
     * коли зʼявляється рід, якого ця сторона не знає.
     */
    @Test
    fun `увесь живий каталог розбирається, жоден рід не обнуляє список`() {
        val text = javaClass.classLoader!!
            .getResourceAsStream("forge-catalog-live-19sep.json")!!
            .bufferedReader().readText()
        val rows = Regex("\"id\"\\s*:").findAll(text).count()
        assertTrue("знімок роздачі порожній — перезніми його", rows > 0)
        assertEquals("рід із дроту, якого ця сторона не знає", rows, ForgeCatalogWire.parseCatalog(text)?.size)
    }

    /**
     * ДОСЛІВНІ РЯДКИ — по одному на кожен рід, що справді лежить у бакеті.
     * Знімок вище ловить розбіжність оптом; цей рядок називає її поіменно.
     */
    @Test
    fun `дослівні рядки живої роздачі читаються, а не обнуляють каталог`() {
        val live = """{"packs":[
            {"bakedAt":"2026-09-12T15:41:31Z","bytes":3111966467,"country":"DE","id":"basemap-pm-de","kind":"BASEMAP_PMTILES","labelUa":"Німеччина","minAppBuild":8},
            {"bakedAt":"2026-09-12T15:31:30Z","bytes":496769456,"country":"PL","id":"dem-pl","kind":"DEM","labelUa":"Польща","minAppBuild":8},
            {"bakedAt":"2026-09-13T02:29:29Z","bytes":960057344,"country":"PL","id":"geocoder-pl","kind":"GEOCODER","labelUa":"Польща","minAppBuild":8},
            {"bakedAt":"2026-09-12T06:00:08Z","bytes":4923392,"country":"LU","id":"transit-lu","kind":"TRANSIT_GTFS","labelUa":"Люксембург","minAppBuild":8},
            {"bakedAt":"2026-09-06T21:40:00Z","bytes":33681774,"country":"DE","id":"DE","kind":"COUNTRY_PLACES","labelUa":"Німеччина — місця й вулиці","minAppBuild":13}
        ]}"""
        val parsed = ForgeCatalogWire.parseCatalog(live)
        assertEquals(5, parsed?.size)
        assertEquals(
            listOf(
                ForgePackKind.BASEMAP_PMTILES,
                ForgePackKind.TERRAIN_DEM,
                ForgePackKind.GEOCODER,
                ForgePackKind.TRANSIT_GTFS,
                ForgePackKind.COUNTRY_PLACES,
            ),
            parsed?.map { it.kind },
        )
        // `id` країни — це ISO2, а не `country-de`: з нього PackFetcher будує
        // `<база>/DE/manifest.json`, і саме за цією адресою лежать байти.
        assertEquals("DE", parsed?.last()?.id)
    }

    @Test
    fun `an unknown kind refuses the whole forge catalog, and only it`() {
        val text = body(geocoderRow, """{"id":"x","kind":"hologram","country":"MD","labelUa":"х","bytes":1}""")
        assertEquals(null, ForgeCatalogWire.parseCatalog(text))
        // Той самий телефон мусить і далі читати світовий каталог.
        val world = """{"packs":[{"id":"basemap-ua","kind":"BASEMAP","country":"UA","labelUa":"Україна","bytes":5}]}"""
        assertEquals(1, WorldPackWire.parseCatalog(world)?.size)
    }

    @Test
    fun `an unsigned forge catalog is refused, not shown`() = runTest {
        val result = client(body(geocoderRow)).forgeCatalog()
        assertTrue(result is ForgeCatalog.Unavailable)
    }

    @Test
    fun `a row without a size never becomes a tap`() = runTest {
        val zero = """{"id":"z","kind":"DEM","country":"MD","labelUa":"рельєф","bytes":0}"""
        val result = client(signer.sign(body(zero))).forgeCatalog()
        assertEquals(emptyList<ForgePackCatalogEntry>(), (result as ForgeCatalog.Ok).packs)
    }

    @Test
    fun `no key in this build means no request at all`() = runTest {
        val seen = mutableListOf<String>()
        val client = WorldPackCatalogClient(
            baseUrl = { "https://packs.example" },
            publicKeyHex = { "" },
            engine = MockEngine { request ->
                seen += request.url.encodedPath
                respond("{}", HttpStatusCode.OK, headersOf())
            },
        )
        assertTrue(client.forgeCatalog() is ForgeCatalog.Unavailable)
        assertEquals(emptyList<String>(), seen)
    }
}
