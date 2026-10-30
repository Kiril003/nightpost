package local.phantom.companion.data

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PeerWhereNoPermissionTest {

    private class MemoryGrants : PeerWhereGrants {
        var saved: Map<String, PeerWhere.Precision> = emptyMap()
        override fun load() = saved
        override fun save(grants: Map<String, PeerWhere.Precision>) {
            saved = grants
        }
    }

    private val friend = PeerIdentity(
        id = "oksana",
        name = "Оксана",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pub-oksana",
        dhPubKey = "dh-oksana",
        verifiedAtMs = 1L,
        keySource = PeerIdentity.KeySource.CAMERA,
        introducedAtMs = 1L,
    )

    private fun hub(allowed: Boolean, sent: MutableList<String>) = PeerWhereHub(
        points = PeerPointsRepository { 1_790_000_000_000L },
        roster = { listOf(friend) },
        fix = { LocationFix(50.45, 30.52, accuracyM = 8f, grade = FixGrade.NAVIGATION, register = FixRegister.MEASURED) },
        send = { _, body -> sent += body },
        grantStore = MemoryGrants(),
        fixAllowed = { allowed },
        nowMs = { 1_790_000_000_000L },
    )

    @Test
    fun `дозвіл ОС відкликано - кадру немає навіть з відкритою смугою`() {
        val sent = mutableListOf<String>()
        val h = hub(allowed = false, sent)
        h.setPrecision("oksana", PeerWhere.Precision.EXACT)
        h.onFix()
        assertFalse(sent.any { body -> PeerWhere.decode(body) is PeerWhere.Point })
    }

    @Test
    fun `з дозволом кадр іде - перевірка не сліпа`() {
        val sent = mutableListOf<String>()
        hub(allowed = true, sent).setPrecision("oksana", PeerWhere.Precision.EXACT)
        assertTrue(sent.any { body -> PeerWhere.decode(body) is PeerWhere.Point })
    }

    @Test
    fun `дозвіл відкликали після кадру - ні запит, ні нова смуга не повторюють збережену точку`() {
        val sent = mutableListOf<String>()
        var allowed = true
        var now = 1_790_000_000_000L
        val h = PeerWhereHub(
            points = PeerPointsRepository { now },
            roster = { listOf(friend) },
            fix = { LocationFix(50.45, 30.52, accuracyM = 8f, grade = FixGrade.NAVIGATION, register = FixRegister.MEASURED) },
            send = { _, body -> sent += body },
            grantStore = MemoryGrants(),
            fixAllowed = { allowed },
            nowMs = { now },
        )
        h.setPrecision("oksana", PeerWhere.Precision.EXACT)
        assertTrue(sent.any { PeerWhere.decode(it) is PeerWhere.Position })
        sent.clear()
        allowed = false
        now += 3 * 60_000L
        PeerWhere.encode(PeerWhere.Ask(now))?.let { h.absorb(PeerMessage("m-ask", "oksana", "me", it, now)) }
        h.setPrecision("oksana", PeerWhere.Precision.QUARTER)
        assertFalse(sent.toString(), sent.any { PeerWhere.decode(it) is PeerWhere.Position })
    }

    @Test
    fun `контейнер дає хабу перевірку дозволу`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val container = File(root, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        val hubBlock = container.substringAfter("PeerWhereHub(").substringBefore(").also")
        assertTrue(Regex("""fixAllowed\s*=\s*\{[^}]*LocationGrants\.held""").containsMatchIn(hubBlock))
    }
}
