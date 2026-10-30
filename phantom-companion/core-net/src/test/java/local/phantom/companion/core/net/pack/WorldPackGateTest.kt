package local.phantom.companion.core.net.pack

import java.io.File
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.crypto.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * СТОРОЖ: [PackFetcher] only ever shows a percent because
 * [PackWant.bytes] is positive by construction — [PackDownload.Downloading]
 * has no "unknown total" state to fall back to. That invariant lives here,
 * in [WorldPackGate.inspect] refusing anything that doesn't declare a real
 * size, and in the checksum it refuses to skip. If either refusal is ever
 * relaxed, this test goes red before a stripped or unmeasured pack ever
 * reaches a progress bar.
 *
 * Кожен манiфест тут ПІДПИСАНИЙ справжнім ключем: інакше після 07.09 усі ці
 * тести зеленіли б з однієї причини — «підпису немає», — і жоден більше не
 * доводив би того, заради чого написаний.
 *
 * Поріг збірки (`minAppBuild`) до 06.09 лише читався з дроту: пак країни
 * його звіряв, світовий — ні. Тепер це теж тут.
 */
class WorldPackGateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = WorldPackSigner()

    private inner class Fake(root: File, key: String = signer.publicHex, build: Int = THIS_BUILD) :
        WorldPackGate(root, { key }, { build }) {
        val applied = mutableListOf<Pair<String, File>>()
        override fun applyVerifiedFile(packId: String, file: File) {
            applied += packId to file
        }
    }

    private fun gate() = Fake(temp.newFolder("root"))
    private fun hash(bytes: String) = bytesToHex(sha256(bytes.toByteArray()))

    private fun unsigned(
        id: String = "basemap-ua-detailed",
        version: Int = 1,
        bytes: Long = 1_300_000_000L,
        sha256: String = hash("payload"),
        minAppBuild: Int = 0,
    ) = buildString {
        append("{\"id\":\"$id\",\"version\":$version,\"bytes\":$bytes,\"sha256\":\"$sha256\"")
        // Без порогу поля нема взагалі: відсутнє поле мусить читатись як 0, не як відмова.
        if (minAppBuild > 0) append(",\"minAppBuild\":$minAppBuild")
        append("}")
    }

    private fun manifest(
        id: String = "basemap-ua-detailed",
        version: Int = 1,
        bytes: Long = 1_300_000_000L,
        sha256: String = hash("payload"),
        minAppBuild: Int = 0,
    ) = signer.sign(unsigned(id, version, bytes, sha256, minAppBuild))

    // ── підпис — перша брама, не остання ─────────────────────────────────

    @Test
    fun `an unsigned manifest is refused even when every field is perfect`() {
        val outcome = gate().inspect(unsigned())
        assertEquals(
            "підпис пака: опис пакета не підписано — дзеркало могло підмінити дороги",
            ((outcome as PackInspection.Refused).reason).detail,
        )
    }

    @Test
    fun `a manifest signed by somebody else is refused`() {
        val stranger = WorldPackSigner()
        val outcome = gate().inspect(stranger.sign(unsigned()))
        assertEquals(
            "підпис пака: не сходиться з ключем цієї збірки — пакет не поїде",
            ((outcome as PackInspection.Refused).reason).detail,
        )
    }

    @Test
    fun `one changed byte after signing is enough — the swapped hash is refused`() {
        val tampered = manifest().replace(hash("payload"), hash("мій граф, не ваш"))
        assertTrue(gate().inspect(tampered) is PackInspection.Refused)
    }

    @Test
    fun `a build with no trust anchor takes nothing, not everything`() {
        val outcome = Fake(temp.newFolder("keyless"), key = "").inspect(manifest())
        assertEquals(
            "підпис пака: ключа звірки у цій збірці немає — жоден пакет не поїде",
            ((outcome as PackInspection.Refused).reason).detail,
        )
    }

    @Test
    fun `promote refuses an unsigned manifest even when the bytes are already staged`() {
        val g = gate()
        val id = "road-mesh-ua"
        File(g.stagingDir(id), "$id.bin").writeText("payload")
        assertTrue(g.promote(id, unsigned(id = id)) is PackPromotion.Refused)
        assertTrue(g.applied.isEmpty())
    }

    // ── розмір і сума — обовʼязкові, не побажані ──────────────────────────

    @Test
    fun `manifest without a positive size is refused — no bar without a denominator`() {
        val outcome = gate().inspect(manifest(bytes = 0L))
        assertTrue(outcome is PackInspection.Refused)
    }

    @Test
    fun `manifest with a negative size is refused`() {
        val outcome = gate().inspect(manifest(bytes = -1L))
        assertTrue(outcome is PackInspection.Refused)
    }

    @Test
    fun `manifest with a blank hash is refused — nothing could prove the bytes arrived intact`() {
        val outcome = gate().inspect(manifest(sha256 = ""))
        assertTrue(outcome is PackInspection.Refused)
    }

    @Test
    fun `manifest with a malformed hash is refused, not truncated or padded`() {
        val outcome = gate().inspect(manifest(sha256 = "not-hex"))
        assertTrue(outcome is PackInspection.Refused)
    }

    @Test
    fun `a well-formed manifest is accepted with the declared size carried through`() {
        val outcome = gate().inspect(manifest(bytes = 1_300_000_000L))
        val want = (outcome as PackInspection.Ready).want
        assertEquals(1_300_000_000L, want.bytes)
        assertTrue(want.files.single().sha256.length == 64)
    }

    // ── поріг збірки — звіряють, а не лише читають ────────────────────────

    @Test
    fun `a pack baked for a newer build is refused, naming both builds`() {
        val outcome = Fake(temp.newFolder("old"), build = 120).inspect(manifest(minAppBuild = 140))
        val reason = (outcome as PackInspection.Refused).reason
        assertTrue(reason is PackFailure.Refused)
        assertEquals("пак просить збірку 140, а ця — 120: спершу оновіть застосунок", reason.detail)
    }

    @Test
    fun `a pack whose floor is exactly this build is accepted and carries the floor`() {
        val outcome = Fake(temp.newFolder("exact"), build = 140).inspect(manifest(minAppBuild = 140))
        assertEquals(140, (outcome as PackInspection.Ready).want.minAppBuild)
    }

    @Test
    fun `a build that never named itself takes no pack with a floor`() {
        // 0 — типовий стан WorldPackLink.appBuild, поки :app його не встановив.
        val outcome = Fake(temp.newFolder("nameless"), build = 0).inspect(manifest(minAppBuild = 1))
        assertEquals(
            "пак просить збірку 1, а ця — 0: спершу оновіть застосунок",
            (outcome as PackInspection.Refused).reason.detail,
        )
    }

    @Test
    fun `a pack with no floor still installs on a build that never named itself`() {
        assertTrue(Fake(temp.newFolder("nameless"), build = 0).inspect(manifest()) is PackInspection.Ready)
    }

    @Test
    fun `promote refuses a too-new pack even when the bytes are staged, and drops them`() {
        val g = Fake(temp.newFolder("old"), build = 120)
        val id = "road-mesh-ua"
        val staged = File(g.stagingDir(id), "$id.bin").apply { writeText("payload") }
        val outcome = g.promote(id, manifest(id = id, version = 3, minAppBuild = 140))
        assertTrue(outcome is PackPromotion.Refused)
        assertTrue(g.applied.isEmpty())
        assertNull(g.installedVersion(id))
        assertTrue(!staged.exists())
    }

    // ── просування верифікованого файла ───────────────────────────────────

    @Test
    fun `promote refuses when the staged file never arrived`() {
        val g = gate()
        val outcome = g.promote("basemap-ua-detailed", manifest())
        assertTrue(outcome is PackPromotion.Refused)
        assertTrue(g.applied.isEmpty())
    }

    @Test
    fun `promote hands the verified file to applyVerifiedFile and remembers the version`() {
        val g = gate()
        val id = "road-mesh-ua"
        val staged = File(g.stagingDir(id), "$id.bin").apply { writeText("payload") }
        val outcome = g.promote(id, manifest(id = id, version = 3))
        assertTrue(outcome is PackPromotion.Installed)
        assertEquals(3, (outcome as PackPromotion.Installed).version)
        assertEquals(1, g.applied.size)
        assertEquals(id, g.applied.single().first)
        assertEquals(3, g.installedVersion(id))
        // Вистояне прибирається — інакше гігабайтний файл лишався б подвійно.
        assertTrue(!staged.exists())
    }

    @Test
    fun `installedVersion is null until something has actually promoted`() {
        assertNull(gate().installedVersion("basemap-pl"))
    }

    private companion object {
        /** Збірка, якій жоден манiфест без порогу нічого не закидає. */
        const val THIS_BUILD = 140
    }
}
