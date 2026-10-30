package local.phantom.companion.core.net.pack

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.crypto.sha256
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Доводиться не «файл качається», а чого фетчер НЕ робить: не ходить нікуди без
 * адреси, не витрачає мегабайт на чужий підпис, не качає те, що вже стоїть, не
 * видає обрив за биті дані і не лишає рваного пака, хоч би де його вбили.
 */
class PackFetcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val poi = "{\"places\":[1,2,3]}"
    private val metro = "{\"lines\":[]}"

    // ── стенд ───────────────────────────────────────────────────────────

    private class Host {
        val files = linkedMapOf<String, ByteArray>()
        val seen = mutableListOf<String>()
        val ranges = mutableListOf<String>()

        /** Шлях, який хост віддає обрізаним — імітація обриву каналу. */
        var truncate: Pair<String, Int>? = null

        fun put(path: String, body: String) = put(path, body.toByteArray())

        fun put(path: String, body: ByteArray) {
            files[path] = body
        }

        fun engine() = MockEngine { request ->
            val path = request.url.encodedPath.trimStart('/')
            seen += path
            request.headers[HttpHeaders.Range]?.let { ranges += it }
            val body = files[path]
            val cut = truncate?.takeIf { it.first == path }?.second
            when {
                body == null -> respond("нема", HttpStatusCode.NotFound)
                request.headers[HttpHeaders.Range] != null -> {
                    val from = request.headers[HttpHeaders.Range]!!
                        .removePrefix("bytes=").substringBefore('-').toInt()
                    respond(
                        ByteReadChannel(body.copyOfRange(from, body.size)),
                        HttpStatusCode.PartialContent,
                    )
                }
                cut != null -> respond(ByteReadChannel(body.copyOfRange(0, cut)), HttpStatusCode.OK)
                else -> respond(ByteReadChannel(body), HttpStatusCode.OK)
            }
        }

        fun assets() = seen.filterNot { it.endsWith("manifest.json") }
    }

    private class FakeGate(private val root: File) : PackGate {
        var want: PackWant? = null
        var refusal: PackFailure? = null
        var installed: Int? = null
        var promoted = 0
        var promotedText: String? = null

        override fun inspect(manifestText: String): PackInspection =
            refusal?.let { PackInspection.Refused(it) } ?: PackInspection.Ready(want!!)

        override fun installedVersion(packId: String): Int? = installed

        override fun stagingDir(packId: String): File =
            File(root, packId).also { it.mkdirs() }

        override fun promote(packId: String, manifestText: String): PackPromotion {
            promoted++
            promotedText = manifestText
            return PackPromotion.Installed(want!!.version)
        }
    }

    private fun wantFile(name: String, body: String) = PackWantFile(
        name = name,
        sha256 = bytesToHex(sha256(body.toByteArray())),
        bytes = body.toByteArray().size.toLong(),
    )

    private fun fetcher(host: Host, gate: FakeGate, base: String = BASE) =
        PackFetcher(baseUrl = { base }, gate = gate, engine = host.engine())

    private fun standing(host: Host, gate: FakeGate, version: Int = 2): FakeGate {
        host.put("packs/PL/manifest.json", "{\"id\":\"PL\"}")
        host.put("packs/PL/poi.json", poi)
        host.put("packs/PL/metro.json", metro)
        gate.want = PackWant("PL", version, listOf(wantFile("poi.json", poi), wantFile("metro.json", metro)))
        return gate
    }

    private fun gate() = FakeGate(temp.newFolder("staging"))

    // ── адреса ──────────────────────────────────────────────────────────

    @Test
    fun `порожня база — це не збій, а вимкнена можливість`() = runTest {
        val host = Host()
        val outcome = fetcher(host, gate(), base = "").install("PL", allowMetered = true)

        assertEquals(PackDownload.NotConfigured, outcome)
        assertTrue("без адреси нікуди не ходимо", host.seen.isEmpty())
    }

    @Test
    fun `манiфест не доїхав — причина мережева, а не «пака нема»`() = runTest {
        val host = Host()
        val outcome = fetcher(host, gate()).install("PL", allowMetered = true)

        val reason = ((outcome as PackDownload.Failed).reason as PackFailure.Source).reason
        assertEquals(SourceUnavailable.HttpError(404), reason)
    }

    // ── підпис ──────────────────────────────────────────────────────────

    @Test
    fun `чужий підпис не коштує жодного мегабайта асетів`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        gate.refusal = PackFailure.Signature("підпис не сходиться з ключем збірки")

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        assertTrue((outcome as PackDownload.Failed).reason is PackFailure.Signature)
        assertTrue("асети не чіпаються до підпису", host.assets().isEmpty())
        assertEquals(0, gate.promoted)
    }

    @Test
    fun `збірка без якоря довіри нічого не встановлює`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        gate.refusal = PackFailure.Signature("ключ звірки пакiв не задано у збірці")

        val outcome = fetcher(host, gate).check("PL")

        assertTrue((outcome as PackDownload.Failed).reason is PackFailure.Signature)
    }

    @Test
    fun `манiфест чужої країни за адресою не встановлюється`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        gate.want = gate.want!!.copy(packId = "DE")

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        val reason = (outcome as PackDownload.Failed).reason
        assertTrue(reason is PackFailure.Refused)
        assertTrue("причина називає обидва id", "DE" in reason.detail && "PL" in reason.detail)
        assertTrue(host.assets().isEmpty())
    }

    // ── різниця версій ──────────────────────────────────────────────────

    @Test
    fun `та сама версія не качається вдруге`() = runTest {
        val host = Host()
        val gate = standing(host, gate(), version = 3)
        gate.installed = 3

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        assertEquals(PackDownload.Installed("PL", 3, fresh = false), outcome)
        assertTrue("версія та сама — асетів не чіпаємо", host.assets().isEmpty())
        assertEquals(0, gate.promoted)
    }

    @Test
    fun `перевірка бачить оновлення і не везе його`() = runTest {
        val host = Host()
        val gate = standing(host, gate(), version = 5)
        gate.installed = 4

        val outcome = fetcher(host, gate).check("PL")

        assertEquals(PackDownload.Available("PL", 5, (poi.length + metro.length).toLong()), outcome)
        assertTrue(host.assets().isEmpty())
    }

    // ── дорога ──────────────────────────────────────────────────────────

    @Test
    fun `новіший пак приїжджає цілим і манiфест іде останнім`() = runTest {
        val host = Host()
        val gate = standing(host, gate(), version = 2)
        gate.installed = 1
        val states = mutableListOf<PackDownload>()

        val outcome = fetcher(host, gate).install("PL", allowMetered = false) { states += it }

        assertEquals(PackDownload.Installed("PL", 2, fresh = true), outcome)
        val dir = gate.stagingDir("PL")
        assertEquals(poi, File(dir, "poi.json").readText())
        assertEquals(metro, File(dir, "metro.json").readText())
        assertEquals(1, gate.promoted)
        // Манiфест стає видимим лише через ворота — і лише після файлів.
        assertFalse(File(dir, "manifest.json").exists())
        assertTrue(states.any { it is PackDownload.Checking })
        assertTrue(states.any { it is PackDownload.Verifying })
        assertEquals(100, states.filterIsInstance<PackDownload.Downloading>().last().percent)
    }

    @Test
    fun `биті дані не стають паком і називають файл`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        host.put("packs/PL/metro.json", "{\"lines\":[9]}")

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        val reason = (outcome as PackDownload.Failed).reason as PackFailure.Corrupt
        assertEquals("metro.json", reason.file)
        assertEquals(0, gate.promoted)
        assertFalse(File(gate.stagingDir("PL"), "metro.json").exists())
        // Здоровий сусід лишається — наступна спроба не тягне його вдруге.
        assertEquals(poi, File(gate.stagingDir("PL"), "poi.json").readText())
    }

    @Test
    fun `обрив не видає себе за биті дані`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        host.truncate = "packs/PL/poi.json" to 5

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        val reason = ((outcome as PackDownload.Failed).reason as PackFailure.Source).reason
        assertTrue(reason is SourceUnavailable.Transport)
        // Хвіст лишається — саме він дає докачку, а не другий повний прохід.
        assertEquals(5L, File(gate.stagingDir("PL"), "poi.json.part").length())
        assertEquals(0, gate.promoted)
    }

    @Test
    fun `звірений файл переживає перезапуск і не їде вдруге`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        File(gate.stagingDir("PL"), "poi.json").writeText(poi)

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        assertTrue(outcome is PackDownload.Installed)
        assertEquals(listOf("packs/PL/metro.json"), host.assets())
    }

    @Test
    fun `недокачаний хвіст докачується, а не тягнеться з нуля`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        File(gate.stagingDir("PL"), "poi.json.part")
            .writeBytes(poi.toByteArray().copyOfRange(0, 6))

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        assertTrue(outcome is PackDownload.Installed)
        assertEquals(listOf("bytes=6-"), host.ranges)
        assertEquals(poi, File(gate.stagingDir("PL"), "poi.json").readText())
    }

    @Test
    fun `хвіст від іншої версії файлу ловиться хешем і перекачується з нуля`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        // Той самий розмір, інший вміст: докачка зійдеться по довжині й не
        // зійдеться по хешу — і саме тому докачка взагалі дозволена.
        File(gate.stagingDir("PL"), "poi.json.part").writeText("XXXXXX")

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        assertTrue(outcome is PackDownload.Installed)
        assertEquals(listOf("bytes=6-"), host.ranges)
        assertEquals(poi, File(gate.stagingDir("PL"), "poi.json").readText())
    }

    @Test
    fun `файл із виходом за каталог пака не качається взагалі`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        gate.want = gate.want!!.copy(files = listOf(wantFile("../../databases/vault", poi)))

        val outcome = fetcher(host, gate).install("PL", allowMetered = true)

        assertTrue((outcome as PackDownload.Failed).reason is PackFailure.Refused)
        assertTrue(host.assets().isEmpty())
    }

    @Test
    fun `уламок скасованої версії не їде у видимий каталог пасажиром`() = runTest {
        val host = Host()
        val gate = standing(host, gate())
        File(gate.stagingDir("PL"), "surface.json").writeText("уламок минулої спроби")

        assertTrue(fetcher(host, gate).install("PL", allowMetered = true) is PackDownload.Installed)

        assertFalse(File(gate.stagingDir("PL"), "surface.json").exists())
    }

    // ── вбивство ────────────────────────────────────────────────────────

    /**
     * Вирішальний тест хвилі: пак убивають посеред вистоювання. Видимого
     * каталогу фетчер не чіпає взагалі (ворота роблять це одним кроком), тож
     * доказ — ворота не покликані й недокачаний файл не став файлом пака.
     */
    @Test
    fun `вбивство посеред вистоювання не лишає рваного пака`() = runTest {
        val host = Host()
        val gate = gate()
        val big = ByteArray(256 * 1024) { (it % 251).toByte() }
        host.put("packs/PL/manifest.json", "{\"id\":\"PL\"}")
        host.put("packs/PL/poi.json", big)
        gate.want = PackWant(
            "PL", 2,
            listOf(PackWantFile("poi.json", bytesToHex(sha256(big)), big.size.toLong())),
        )
        val states = mutableListOf<PackDownload>()

        var job: Job? = null
        job = launch {
            fetcher(host, gate).install("PL", allowMetered = true) { state ->
                states += state
                if (state is PackDownload.Downloading) job?.cancel()
            }
        }
        job.join()

        assertEquals("ворота не бачили цього пака", 0, gate.promoted)
        assertFalse(File(gate.stagingDir("PL"), "poi.json").exists())
        assertTrue(
            "скасування називає себе скасуванням",
            states.any { it is PackDownload.Failed && it.reason is PackFailure.Cancelled },
        )
    }

    private companion object {
        const val BASE = "https://packs.example.org/packs"
    }
}
