package local.phantom.companion.core.data.country

import java.io.File
import java.security.MessageDigest
import local.phantom.companion.core.net.pack.PackFailure
import local.phantom.companion.core.net.pack.PackInspection
import local.phantom.companion.core.net.pack.PackPromotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Фетчер робить лише те, що віддали ці ворота — тож саме тут доводиться, що
 * збірка без якоря довіри й чужий підпис не стають списком файлів до качання.
 */
class CountryPackGateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = PackSigner()
    private val poi = """{"places":[1]}"""

    private fun store(key: String = signer.publicHex) = CountryPackStore(
        root = temp.root,
        publicKeyHex = key,
        appBuild = 100,
        audit = { emptyList() },
    )

    private fun hex(body: String) = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun withPoi(version: Int = 1) = signer.sign(
        polandManifest(
            version = version,
            extra = ""","poiAsset":"poi.json","files":[{"name":"poi.json",""" +
                """"sha256":"${hex(poi)}","bytes":${poi.toByteArray().size}}]""",
        ),
    )

    @Test
    fun `підписаний манiфест стає списком файлів із хешами`() {
        val seen = (CountryPackGate(store()).inspect(withPoi(4)) as PackInspection.Ready).want

        assertEquals("PL", seen.packId)
        assertEquals(4, seen.version)
        assertEquals(listOf("poi.json"), seen.files.map { it.name })
        assertEquals(hex(poi), seen.files.single().sha256)
    }

    @Test
    fun `чужий підпис не стає списком файлів`() {
        val outcome = CountryPackGate(store()).inspect(PackSigner().sign(polandManifest()))

        assertTrue((outcome as PackInspection.Refused).reason is PackFailure.Signature)
    }

    @Test
    fun `збірка без якоря довіри не дає качати нічого`() {
        val outcome = CountryPackGate(store(key = "")).inspect(withPoi())

        assertTrue((outcome as PackInspection.Refused).reason is PackFailure.Signature)
    }

    @Test
    fun `оголошений асет без хеша зупиняє завантаження до першого байта`() {
        val text = signer.sign(polandManifest(extra = ""","poiAsset":"poi.json""""))

        val outcome = CountryPackGate(store()).inspect(text)

        val reason = (outcome as PackInspection.Refused).reason
        assertTrue(reason is PackFailure.Refused)
        assertTrue("poi.json" in reason.detail)
    }

    @Test
    fun `версія на диску — те, з чим фетчер звіряє знайдене`() {
        val s = store()
        val gate = CountryPackGate(s)
        assertNull(gate.installedVersion("PL"))

        File(gate.stagingDir("PL"), "poi.json").writeText(poi)
        gate.promote("PL", withPoi(7))

        assertEquals(7, gate.installedVersion("PL"))
    }

    @Test
    fun `встановлення повідомляє викликача — реєстр оновлює він, не ворота`() {
        var announced: InstalledPack? = null
        val gate = CountryPackGate(store()) { announced = it }
        File(gate.stagingDir("PL"), "poi.json").writeText(poi)

        val outcome = gate.promote("PL", withPoi(2))

        assertEquals(PackPromotion.Installed(2), outcome)
        assertEquals("PL", announced?.id)
    }

    @Test
    fun `невиправна відмова не тримає мегабайти вистоювання`() {
        val gate = CountryPackGate(store())
        File(gate.stagingDir("PL"), "poi.json").writeText(poi)
        // Ліцензійне порушення наступною спробою не лікується.
        val strict = CountryPackGate(
            CountryPackStore(temp.root, signer.publicHex, 100) { listOf("«pl-base»: без атрибуції") },
        )

        val outcome = strict.promote("PL", withPoi())

        assertTrue((outcome as PackPromotion.Refused).reason is PackFailure.Refused)
        assertFalse(File(gate.stagingDir("PL"), "poi.json").exists())
    }

    @Test
    fun `неповне вистоювання зберігається — наступна спроба докачає`() {
        val gate = CountryPackGate(store())
        val staged = gate.stagingDir("PL")
        File(staged, "poi.json").writeText("не той розмір, не той вміст")

        assertTrue(gate.promote("PL", withPoi()) is PackPromotion.Refused)

        assertTrue(File(staged, "poi.json").isFile)
    }
}
