package local.phantom.companion.core.data.country

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Що лежить на диску, якщо телефон убили посеред встановлення? Тільки одне з
 * двох — цілий СТАРИЙ пак або цілий НОВИЙ. Кожна точка вбивства відтворена
 * формою на диску, а не потоками.
 */
class PackStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = PackSigner()
    private val v1 = """{"places":[1]}"""
    private val v2 = """{"places":[1,2]}"""
    private val metro = """{"lines":[]}"""

    private fun store(): CountryPackStore = CountryPackStore(
        root = temp.root,
        publicKeyHex = signer.publicHex,
        appBuild = 100,
        audit = { emptyList() },
    )

    private fun hex(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun spec(name: String, body: String) =
        """{"name":"$name","sha256":"${hex(body)}","bytes":${body.toByteArray().size}}"""

    /** Манiфест Польщі з одним або двома файлами й хешами на кожен. */
    private fun manifest(version: Int, poi: String, withMetro: Boolean = false): String {
        val files = listOfNotNull(spec("poi.json", poi), spec("metro.json", metro).takeIf { withMetro })
        val metroField = if (withMetro) ""","metroAsset":"metro.json"""" else ""
        return signer.sign(
            polandManifest(
                version = version,
                extra = ""","poiAsset":"poi.json"$metroField,"files":[${files.joinToString(",")}]""",
            ),
        )
    }

    private fun stage(s: CountryPackStore, poi: String, withMetro: Boolean = false) {
        val dir = s.stagingDir("PL")
        File(dir, "poi.json").writeText(poi)
        if (withMetro) File(dir, "metro.json").writeText(metro)
    }

    private fun installed(): List<InstalledPack> = store().downloaded()

    private fun live(name: String) = File(temp.root, "PL/$name")

    // ── звичайна дорога ─────────────────────────────────────────────────

    @Test
    fun `вистояний пак стає видимим разом зі своїми файлами`() {
        val s = store()
        stage(s, v1)

        assertTrue(s.installStaged(manifest(1, v1)) is PackInstall.Accepted)

        assertEquals(listOf("PL"), installed().map { it.id })
        assertEquals(v1, live("poi.json").readText())
        assertTrue(live("manifest.json").isFile)
    }

    @Test
    fun `оновлення підмінює каталог цілком, а не змішує версії`() {
        val s = store()
        stage(s, v1, withMetro = true)
        s.installStaged(manifest(1, v1, withMetro = true))

        stage(store(), v2)
        assertTrue(store().installStaged(manifest(2, v2)) is PackInstall.Accepted)

        assertEquals(2, installed().single().version)
        assertEquals(v2, live("poi.json").readText())
        // Файл, якого нова версія не оголошувала, не лишається пасажиром.
        assertFalse(live("metro.json").exists())
    }

    @Test
    fun `версія назад не приймається навіть вистояна`() {
        val s = store()
        stage(s, v2)
        s.installStaged(manifest(3, v2))

        stage(store(), v1)
        val outcome = store().installStaged(manifest(2, v1))

        assertEquals(PackReject.OLDER_VERSION, (outcome as PackInstall.Rejected).reason)
        assertEquals(3, installed().single().version)
        assertEquals(v2, live("poi.json").readText())
    }

    // ── закон «манiфест останнім» ───────────────────────────────────────

    @Test
    fun `невистояний файл — пака не існує, а не існує наполовину`() {
        val outcome = store().installStaged(manifest(1, v1))

        assertEquals(PackReject.INCOMPLETE, (outcome as PackInstall.Rejected).reason)
        assertTrue(installed().isEmpty())
        assertFalse(File(temp.root, "PL").exists())
    }

    @Test
    fun `асет без хеша дротом не їде`() {
        val s = store()
        File(s.stagingDir("PL"), "poi.json").writeText(v1)
        val text = signer.sign(polandManifest(extra = ""","poiAsset":"poi.json""""))

        val outcome = s.installStaged(text)

        assertEquals(PackReject.MALFORMED, (outcome as PackInstall.Rejected).reason)
        assertTrue("причина називає асет", "poi.json" in outcome.detail)
        assertTrue(installed().isEmpty())
    }

    @Test
    fun `файл не того розміру не пускається у видимий каталог`() {
        val s = store()
        File(s.stagingDir("PL"), "poi.json").writeText(v2)

        val outcome = s.installStaged(manifest(1, v1))

        assertEquals(PackReject.INCOMPLETE, (outcome as PackInstall.Rejected).reason)
        assertTrue(installed().isEmpty())
    }

    // ── точки вбивства ──────────────────────────────────────────────────

    @Test
    fun `вбивство посеред вистоювання лишає старий пак цілим`() {
        val s = store()
        stage(s, v1)
        s.installStaged(manifest(1, v1))

        // Половина нової версії доїхала й на цьому все обірвалось.
        File(store().stagingDir("PL"), "poi.json").writeText(v2.take(4))

        assertEquals(1, installed().single().version)
        assertEquals(v1, live("poi.json").readText())
    }

    /**
     * Найтонша точка: файли доїхали, манiфест уже записаний у ВИСТОЮВАННЯ, а
     * підміни каталогу ще не було. Вистояний манiфест не робить пака — його
     * там не бачить жоден читач, бо пак розпізнається на рівень вище.
     */
    @Test
    fun `манiфест у вистоюванні ще не пак`() {
        val s = store()
        stage(s, v1)
        s.installStaged(manifest(1, v1))

        val staged = store().stagingDir("PL")
        File(staged, "poi.json").writeText(v2)
        File(staged, "manifest.json").writeText(manifest(2, v2))

        assertEquals(1, installed().single().version)
        assertEquals(v1, live("poi.json").readText())
    }

    @Test
    fun `вбивство між підмінами повертає старий пак цілим`() {
        val s = store()
        stage(s, v1)
        s.installStaged(manifest(1, v1))

        // Форма «старий відкладено, новий стати не встиг».
        val retired = File(temp.root, "${PackStaging.RETIRED}/PL")
        retired.parentFile?.mkdirs()
        assertTrue(File(temp.root, "PL").renameTo(retired))

        val packs = installed()

        assertEquals(listOf("PL"), packs.map { it.id })
        assertEquals(1, packs.single().version)
        assertEquals(v1, live("poi.json").readText())
        assertFalse(File(temp.root, PackStaging.RETIRED).exists())
    }

    @Test
    fun `вбивство після підміни лишає новий пак цілим і без дубля`() {
        val s = store()
        stage(s, v1)
        s.installStaged(manifest(1, v1))
        stage(store(), v2)
        store().installStaged(manifest(2, v2))

        // Форма «новий став, прибирання старого не встигло».
        val retired = File(temp.root, "${PackStaging.RETIRED}/PL")
        retired.mkdirs()
        File(retired, "manifest.json").writeText(manifest(1, v1))
        File(retired, "poi.json").writeText(v1)

        val packs = installed()

        assertEquals("рівно один PL, не два", listOf("PL"), packs.map { it.id })
        assertEquals(2, packs.single().version)
        assertEquals(v2, live("poi.json").readText())
        assertFalse("відкладене прибирається, а не тече на диску", File(temp.root, PackStaging.RETIRED).exists())
    }

    @Test
    fun `службові каталоги не читаються як паки`() {
        val s = store()
        stage(s, v1)

        assertTrue(installed().isEmpty())
        assertNull(store().installedVersion("PL"))
    }
}
