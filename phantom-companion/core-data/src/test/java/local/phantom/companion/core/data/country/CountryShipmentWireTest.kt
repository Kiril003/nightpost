package local.phantom.companion.core.data.country

import java.io.File
import local.phantom.companion.core.net.pack.ForgeCatalogWire
import local.phantom.companion.core.net.pack.ForgePackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЩО ВИЙШЛО З ПЕЧІ — ТЕ ТЕЛЕФОН І БЕРЕ.
 *
 * Фікстури під `country/staged-17sep/` — не зразок, написаний рукою під цей
 * набір. Це байт-у-байт те, що `scripts/publish_country_packs.py stage` виклав
 * 17.09 на оракулі зі справжніх спечених TSV (`~/places-europe/out`, 24 країни,
 * 563 МБ сирими → 163 МБ під gzip). Дотепер скрипт не був прогнаний наскрізно
 * ЖОДНОГО разу, а те, чи візьме телефон його вихід, лишалось здогадом.
 *
 * ЧОМУ САМЕ ЦІ ПЕРЕВІРКИ. Кожна з них ловить розходження, яке ніде більше не
 * видно: піч і телефон — різні мови, різні дерева, різні машини, і
 * домовленість між ними тримається лише на цих файлах. Розійтись вони можуть
 * мовчки й у будь-який бік.
 *
 * ЧОГО ЦЕЙ НАБІР НЕ ДОВОДИТЬ — і це важливіше за те, що доводить:
 *
 *  1. **Що пак лежить у роздачі.** Фікстури тут — дерево ДО підпису, і таким
 *     телефон його відхиляє (див. перевірку нижче). Роздано 18.09: каталог
 *     несе 73 рядки, `try.phantom-os.dev/packs/DE/` віддає манiфест і 33,7 МБ,
 *     sha256 обох файлів збіглися з оголошеними. Але доводить це прогін по
 *     живій адресі, а не цей набір: він читає файли з ресурсів.
 *  2. **Що корпус читається.** 163 МБ gz у дереві не лежать; дорогу від асета
 *     до газетира тримають [PackCorpusHighwayTest] і [CountryCorpusScaleTest].
 */
class CountryShipmentWireTest {

    private val signer = PackSigner()

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("country/staged-17sep/$path")) {
            "немає фікстури country/staged-17sep/$path"
        }.bufferedReader().use { it.readText() }

    private val catalog = checkNotNull(ForgeCatalogWire.parseCatalog(resource("forge-catalog.json"))) {
        "каталог, який виклала піч, телефон не розібрав ЦІЛКОМ"
    }

    private val countries = catalog.filter { it.kind == ForgePackKind.COUNTRY_PLACES }

    private fun manifestOf(iso2: String) = resource("$iso2/manifest.json")

    private fun read(text: String, appBuild: Int = 13, installed: Int? = null) = PackInstaller.read(
        text = text,
        publicKeyHex = signer.publicHex,
        appBuild = appBuild,
        assetSource = PackAssetSource.Directory("/nowhere"),
        audit = { emptyList() },
        installedVersion = installed,
    )

    /**
     * Піч ДОПИСУЄ свої рядки в чужий каталог, а не переписує його.
     *
     * Каталог кузні спільний: 49 рядків (GEOCODER, DEM, BASEMAP_PMTILES,
     * TRANSIT_GTFS) лежать там із 12.09 і вже підписані. Скрипт, який писав би
     * документ цілком, стер би їх — і дізнався б про це телефон, у якого зник
     * геокодер.
     */
    @Test
    fun `піч додала 24 країни й не зʼїла жодного з 49 чужих рядків`() {
        assertEquals(73, catalog.size)
        assertEquals(24, countries.size)
        val others = catalog.filter { it.kind != ForgePackKind.COUNTRY_PLACES }
        assertEquals(49, others.size)
        assertEquals(
            mapOf(
                ForgePackKind.GEOCODER to 26,
                ForgePackKind.TERRAIN_DEM to 18,
                ForgePackKind.BASEMAP_PMTILES to 4,
                ForgePackKind.TRANSIT_GTFS to 1,
            ),
            others.groupingBy { it.kind }.eachCount(),
        )
        assertTrue(catalog.all { it.wellFormed })
    }

    /**
     * `id` рядка каталогу — це АДРЕСА. `PackFetcher` будує з нього
     * `<база>/<id>/manifest.json` і сам же відмовляє, коли за адресою лежить
     * пак із іншим іменем. Розбіжність тут — 404 або відмова посеред поля.
     */
    @Test
    fun `кожен рядок країни веде на манiфест, який називає себе так само`() {
        for (row in countries) {
            val manifest = read(signer.sign(manifestOf(row.id)))
            assertTrue("${row.id}: $manifest", manifest is PackInstall.Accepted)
            assertEquals(row.id, (manifest as PackInstall.Accepted).pack.id)
            assertEquals(row.id, row.country)
        }
    }

    /**
     * Лічильник, який людина бачить ДО завантаження, і байти, які вона
     * завантажить, — одне число. Каталог оголошує суму, манiфест розписує її
     * пофайлово; сходяться вони лише тому, що їх пише той самий прохід.
     */
    @Test
    fun `байти каталогу дорівнюють сумі файлів манiфеста`() {
        var grand = 0L
        for (row in countries) {
            val pack = (read(signer.sign(manifestOf(row.id))) as PackInstall.Accepted).pack
            val sum = pack.manifest.files.sumOf { it.bytes }
            assertEquals("${row.id}: каталог оголосив ${row.bytes}, манiфест несе $sum", row.bytes, sum)
            grand += sum
        }
        // 163 МБ на 24 країни — стільки важить уся Європа в роздачі. В APK
        // такого не кладуть: людина качає свою країну, як уже качає мапу.
        assertEquals(163_420_870L, grand)
    }

    /**
     * Те, заради чого існує вся хвиля: пак, що приїхав дротом, везе МІСЦЯ Й
     * ВУЛИЦІ. До 16.09 манiфест знав лише `poiAsset`, і жодна з 24 країн не
     * мала куди їхати.
     */
    @Test
    fun `кожна країна везе місця й вулиці — з хешем і ліцензією на кожен асет`() {
        for (row in countries) {
            val pack = (read(signer.sign(manifestOf(row.id))) as PackInstall.Accepted).pack
            assertEquals("${row.id}: місця", "places.tsv.gz", pack.placesAsset)
            assertEquals("${row.id}: вулиці", "streets.tsv.gz", pack.streetsAsset)
            // Європейський випік поселень не несе — пак чесно оголошує два
            // асети, а не три з пустишкою.
            assertNull("${row.id}: поселення", pack.settlementsAsset)

            val named = pack.manifest.assetNames().toSet()
            assertEquals("${row.id}: асет без хеша або хеш без асета",
                named, pack.manifest.files.map { it.name }.toSet())
            assertTrue("${row.id}: файл без хеша", pack.manifest.files.all { it.wellFormed })
            assertEquals("${row.id}: асет без ліцензії",
                named, pack.sources.mapNotNull { it.asset }.toSet())
            assertTrue("${row.id}: джерело без атрибуції",
                pack.sources.all { it.attribution.isNotBlank() && it.license == "ODBL" })
        }
    }

    /**
     * ДЕРЕВО З ПЕЧІ — НЕ ГОТОВИЙ ПАК.
     *
     * Скрипт закінчує прогін рядком «ПІДПИСУ ЩЕ НЕМА», і ось що за ним стоїть:
     * телефон відмовляє непідписаному манiфесту, навіть коли всі суми в ньому
     * сходяться. Перевірка тут, а не в голові оператора, саме тому, що дерево
     * виглядає повним і готовим до викладення.
     */
    @Test
    fun `непідписаний манiфест телефон відхиляє`() {
        val verdict = read(manifestOf("DE"))
        assertEquals(PackReject.BAD_SIGNATURE, (verdict as PackInstall.Rejected).reason)
    }

    /**
     * `minAppBuild` у паку — 13, і збірка 13 роздана людям 18.09; до неї
     * телефон не знав ані `placesAsset`, ані роду `COUNTRY_PLACES`. Пак не має
     * вдавати, що старіша збірка його розуміє: там незнайомий рід валить
     * розбір каталогу ЦІЛКОМ, тобто відбирає ще й ті пакети, що вже працювали.
     */
    @Test
    fun `збірка 12 пак не бере, збірка 13 бере`() {
        assertTrue(countries.all { it.minAppBuild == 13 })
        val text = signer.sign(manifestOf("DE"))
        assertEquals(PackReject.APP_TOO_OLD, (read(text, appBuild = 12) as PackInstall.Rejected).reason)
        assertTrue(read(text, appBuild = 13) is PackInstall.Accepted)
    }

    /**
     * Рамка й дім не набрані в печі руками — вони зняті з `WorldTable.kt` і
     * `PlaceGazetteerTest.kt` телефона. Другий список розійшовся б із ними
     * мовчки й на одну цифру, а пак із домом поза рамкою телефон відхиляє як
     * структурно битий — тобто в полі, а не тут.
     */
    @Test
    fun `дім кожної країни лежить усередині її рамки`() {
        for (row in countries) {
            val pack = (read(signer.sign(manifestOf(row.id))) as PackInstall.Accepted).pack
            assertTrue("${row.id}: дім поза рамкою",
                pack.bounds.contains(pack.home.lat, pack.home.lon))
            assertNotNull(pack.manifest.title.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Україна вбудована в збірку, і жоден завантажений пак не має права її
     * підмінити. Піч не знає цього правила — вона просто не бачила UA серед
     * спеченого; перевірка тут стереже наступний випік, а не цей.
     */
    @Test
    fun `вбудованої України серед викладених країн немає`() {
        assertTrue(countries.none { it.id == UkrainePack.id })
    }

    /**
     * ОДНА КРАЇНА В ЗБІРЦІ — РЕШТА ДРОТОМ.
     *
     * Україна коштує 57 МБ асетів, і це вже половина пакунка. Двадцять чотири
     * країни Європи — 163 МБ під gzip; покласти їх поруч означало б зробити
     * встановлення застосунку вчетверо дорожчим для КОЖНОЇ людини заради
     * країни, у якій вона не живе. Людина качає свою країну так само, як уже
     * качає мапу й дороги.
     *
     * Заслін тут, а не в голові: додати теку до `assets/country/` — один рух
     * `cp`, і жоден інший тест його не побачить, бо код країнних паків не
     * дивиться, скільки їх лежить у збірці.
     */
    @Test
    fun `у збірці лежить рівно одна країна`() {
        val root = listOf(File("src/main/assets/country"), File("core-data/src/main/assets/country"))
            .firstOrNull { it.isDirectory } ?: error("немає src/main/assets/country")
        assertEquals(
            listOf("ua"),
            root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted(),
        )
        // Другу країну ловить перевірка вище; ця — розростання самої України.
        val bundled = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("вбудовані корпуси розрослись до $bundled байт", bundled < 80_000_000L)
    }
}
