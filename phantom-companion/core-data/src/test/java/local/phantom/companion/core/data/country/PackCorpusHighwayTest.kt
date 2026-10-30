package local.phantom.companion.core.data.country

import androidx.test.core.app.ApplicationProvider
import java.io.File
import local.phantom.companion.core.data.poi.OfflinePlacePack
import local.phantom.companion.core.data.poi.OfflineStreetPack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * МАГІСТРАЛЬ ДАНИХ — чи вміє ЗАВАНТАЖЕНИЙ пак привезти місця й вулиці.
 *
 * ЩО БУЛО ЗЛАМАНО, виміряно 16.09. `PackManifest` знав рівно один корпусний
 * асет — `poiAsset`. Поля `placesAsset`/`streetsAsset`/`settlementsAsset`
 * існували ЛИШЕ хардкодом у [UkrainePack], тобто для вбудованої країни.
 * Завантажуваний пак фізично не міг привезти ані місця, ані вулиці — і саме
 * тому 24 спечені країни Європи (563 МБ, 06.09) не мали куди їхати, а людина
 * в роумінгу бачила мапу без жодного місця.
 *
 * ЧОГО ЦЕЙ НАБІР НЕ РОБИТЬ: не перевіряє розбір рядка — його тримають
 * `OfflinePlacePackTest` і `OfflineStreetPackTest` на справжніх корпусах.
 * Тут доводиться лише ДОРОГА: оголошений паком асет доїжджає, лежить у
 * каталозі ЦЬОГО пака, читається тим самим потоком і стає рядками газетира
 * БЕЗ третього розбирача.
 */
@RunWith(RobolectricTestRunner::class)
class PackCorpusHighwayTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = PackSigner()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val places = buildString {
        appendLine("generated=1788731051 count=3")
        appendLine("52.2297\t21.0122\tamenity\tdrinking_water\tn1\tWoda")
        appendLine("52.4064\t16.9252\tamenity\tpharmacy\tn2\tApteka")
        appendLine("50.0647\t19.9450\tford\tyes\tn3\tБрід")
    }

    private val streets = buildString {
        appendLine("generated=1788731054 count=2")
        appendLine("52.2319\t21.0067\tresidential\tMarszałkowska")
        appendLine("50.0614\t19.9366\ttertiary\tFloriańska")
    }

    @After
    fun tearDown() = CountryPackRegistry.reset()

    private fun store() = CountryPackStore(
        root = temp.root,
        publicKeyHex = signer.publicHex,
        appBuild = 100,
        audit = { emptyList() },
    )

    /** Пак Польщі з корпусами на диску й хешами в манiфесті — як із дроту. */
    private fun stagePoland(withFiles: Boolean = true, withHashes: Boolean = true): String {
        val dir = File(temp.root, "PL").apply { mkdirs() }
        if (withFiles) {
            File(dir, "places.tsv").writeText(places)
            File(dir, "streets.tsv").writeText(streets)
            File(dir, "settlements.tsv").writeText(places)
        }
        val assets = ""","placesAsset":"places.tsv","streetsAsset":"streets.tsv"""" +
            ""","settlementsAsset":"settlements.tsv""""
        val files = if (!withHashes) "" else ""","files":[""" + listOf(
            "places.tsv" to places,
            "streets.tsv" to streets,
            "settlements.tsv" to places,
        ).joinToString(",") { (name, body) ->
            """{"name":"$name","sha256":"${sha256(body)}","bytes":${body.toByteArray().size}}"""
        } + "]"
        return signer.sign(polandManifest(extra = assets + files))
    }

    @Test
    fun `манiфест оголошує місця, вулиці й поселення — і пак їх має`() {
        val s = store()
        assertTrue(s.install(stagePoland()) is PackInstall.Accepted)
        val pack = s.downloaded().single()
        assertEquals("places.tsv", pack.placesAsset)
        assertEquals("streets.tsv", pack.streetsAsset)
        assertEquals("settlements.tsv", pack.settlementsAsset)
    }

    /**
     * ТОЙ САМИЙ ПОТІК, ЩО ВЕЗЕ УКРАЇНУ. Ключове тут не кількість рядків, а
     * те, що читач — [CountryPack.readAssetStream] + [OfflinePlacePack] — не
     * знає й не має знати, чи пак вбудований, чи приїхав дротом.
     */
    @Test
    fun `місця завантаженого пака течуть у газетир тим самим розбирачем`() {
        val s = store()
        assertTrue(s.install(stagePoland()) is PackInstall.Accepted)
        val pack = s.downloaded().single()

        val rows = pack.readAssetStream(context, pack.placesAsset!!)!!
            .bufferedReader().use { reader ->
                assertEquals(1788731051L, OfflinePlacePack.parseGenerated(reader.readLine()))
                OfflinePlacePack.chunkLines(reader).flatten().toList()
            }
        assertEquals(listOf("Woda", "Apteka", "Брід"), rows.map { it.name })
        assertEquals(listOf("amenity", "amenity", "ford"), rows.map { it.category })
        // Стала особа з OSM переживає перепікання — див. OfflinePlacePack.
        assertTrue(rows.all { OfflinePlacePack.REF_TAG in it.tags })
    }

    @Test
    fun `вулиці завантаженого пака теж, і без другого розбирача`() {
        val s = store()
        assertTrue(s.install(stagePoland()) is PackInstall.Accepted)
        val pack = s.downloaded().single()

        val rows = pack.readAssetStream(context, pack.streetsAsset!!)!!
            .bufferedReader().use { reader ->
                assertEquals(1788731054L, OfflineStreetPack.parseGenerated(reader.readLine()))
                OfflineStreetPack.chunkLines(reader).flatten().toList()
            }
        assertEquals(listOf("Marszałkowska", "Floriańska"), rows.map { it.name })
        assertTrue(rows.all { it.category == OfflineStreetPack.CATEGORY })
    }

    @Test
    fun `корпус читається з каталогу ПАКА, а не зі збірки`() {
        val s = store()
        assertTrue(s.install(stagePoland()) is PackInstall.Accepted)
        s.activate()
        CountryPackRegistry.locate(52.2297, 21.0122)
        val pack = CountryPackRegistry.current()!!
        assertEquals(PackAssetSource.Directory(File(temp.root, "PL").path), pack.assetSource)
    }

    @Test
    fun `оголошений корпус без файла на диску не стає паком`() {
        val outcome = store().install(stagePoland(withFiles = false))
        assertEquals(PackReject.MALFORMED, (outcome as PackInstall.Rejected).reason)
        assertTrue("places.tsv" in outcome.detail)
    }

    /**
     * Асет без хеша неможливо звірити після завантаження. Заслін уже існував
     * для `poiAsset`; він мусив накрити й корпуси — інакше найважчі файли
     * пака (Німеччина: 37 МБ) їхали б дротом на самій довірі до дзеркала.
     */
    @Test
    fun `корпус без хеша не їде дротом`() {
        val accepted = store().inspect(stagePoland(withHashes = false))
        val manifest = (accepted as PackInstall.Accepted).pack.manifest
        assertEquals(
            listOf("places.tsv", "streets.tsv", "settlements.tsv"),
            PackInstaller.unhashedAssets(manifest),
        )
    }

    @Test
    fun `корпус із подвійною крапкою в імені не виходить за каталог пака`() {
        val text = signer.sign(polandManifest(extra = ""","placesAsset":"../../places.tsv""""))
        val outcome = store().install(text)
        assertEquals(PackReject.MALFORMED, (outcome as PackInstall.Rejected).reason)
        assertTrue("виходить за каталог" in outcome.detail)
    }

    /**
     * Стиснений корпус — та сама дорога, менший лічильник роумінгу.
     * Німеччина: 110 МБ сирим TSV проти 34 МБ під gzip.
     */
    @Test
    fun `стиснений корпус розпаковується читачем, а не розбирачем`() {
        val dir = File(temp.root, "PL").apply { mkdirs() }
        val gz = File(dir, "places.tsv.gz")
        java.util.zip.GZIPOutputStream(gz.outputStream()).use { it.write(places.toByteArray()) }
        val text = signer.sign(
            polandManifest(
                extra = ""","placesAsset":"places.tsv.gz","files":[{"name":"places.tsv.gz",""" +
                    """"sha256":"${sha256Bytes(gz.readBytes())}","bytes":${gz.length()}}]""",
            ),
        )
        val s = store()
        assertTrue(s.install(text) is PackInstall.Accepted)
        val pack = s.downloaded().single()

        val rows = pack.readAssetStream(context, pack.placesAsset!!)!!
            .bufferedReader().use { reader ->
                reader.readLine()
                OfflinePlacePack.chunkLines(reader).flatten().toList()
            }
        assertEquals(listOf("Woda", "Apteka", "Брід"), rows.map { it.name })
        // Хеш і розмір у манiфесті описують те, що ЇДЕ дротом, — стиснений файл.
        assertEquals(gz.length(), pack.manifest.fileFor("places.tsv.gz")!!.bytes)
    }

    private fun sha256Bytes(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
