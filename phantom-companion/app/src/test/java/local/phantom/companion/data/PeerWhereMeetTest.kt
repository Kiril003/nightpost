package local.phantom.companion.data

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerEnvelope
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.ui.messenger.WebMessengerWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_790_000_000_000L

/** «До зустрічі»: точний показ гасне сам, щойно обоє ближче 50 м, і памʼятає, що ми зустрілись. */
class PeerWhereMeetTest {

    private var now = T0
    private val saved = mutableMapOf<String, Set<String>>()

    private inner class Phone(val id: String) {
        val dh = X25519.generateKeypair()
        val dhPub: String = java.util.Base64.getEncoder().encodeToString(dh.publicKey)
        var fix: LocationFix? = null
        lateinit var hub: PeerWhereHub
        fun keyWith(other: Phone) = PeerChannel.derive(dh.privateKey, other.dhPub, id, other.id)!!
        fun asContact() = PeerIdentity(
            id = id,
            name = id,
            kind = PeerIdentity.Kind.PERSON,
            pubKey = "pub-$id",
            dhPubKey = dhPub,
            keySource = PeerIdentity.KeySource.SHARED,
            introducedAtMs = 1L,
        )
        fun at(lat: Double, lon: Double) {
            fix = LocationFix(lat, lon, accuracyM = 6f, grade = FixGrade.NAVIGATION, register = FixRegister.MEASURED)
        }
    }

    private val me = Phone("me000000")
    private val olena = Phone("olena000")

    private fun wireUp(a: Phone, b: Phone) {
        a.hub = PeerWhereHub(
            points = PeerPointsRepository { now },
            roster = { listOf(b.asContact()) },
            fix = { a.fix },
            send = { to, body ->
                val letter = PeerMessage("m-$now-${a.id}", a.id, to, body, now, signature = "s".repeat(86))
                val frame = PeerEnvelope.seal(letter, a.keyWith(b))!!.frame
                b.hub.absorb(PeerEnvelope.open(frame) { from -> if (from == a.id) b.keyWith(a) else null }!!)
            },
            fixAllowed = { true },
            grantStore = object : PeerWhereGrants {
                override fun load() = emptyMap<String, PeerWhere.Precision>()
                override fun save(grants: Map<String, PeerWhere.Precision>) {}
                override fun saveMeet(ids: Set<String>) { saved[a.id] = ids }
            },
            nowMs = { now },
        )
    }

    init {
        wireUp(me, olena)
        wireUp(olena, me)
    }

    private val until = T0 + 2 * 60 * 60_000L

    @Test
    fun `обоє ближче 50 м — показ до зустрічі гасне сам в обидва боки`() {
        me.at(50.4501, 30.5234)
        olena.at(50.4601, 30.5234)
        assertTrue(me.hub.setPrecision(olena.id, PeerWhere.Precision.EXACT, until, meet = true))
        assertTrue(olena.hub.setPrecision(me.id, PeerWhere.Precision.EXACT, until, meet = true))
        assertEquals(true, me.hub.view(olena.id).mine?.meet)
        assertNotNull(olena.hub.view(me.id).theirs)
        assertEquals(setOf(olena.id), saved[me.id])

        now += 3 * 60_000L
        olena.at(50.45022, 30.5234)
        me.hub.ask(olena.id)

        assertNull("мій показ не згас після зустрічі", me.hub.view(olena.id).mine)
        assertNull("показ Олени не згас після зустрічі", olena.hub.view(me.id).mine)
        assertNull("моя точка лишилась в Олени", olena.hub.view(me.id).theirs)
        assertEquals(now, me.hub.view(olena.id).metAtMs)
        val web = WebMessengerWhere.fields(me.hub.view(olena.id), now)
        assertEquals(now, web["met_at_ms"])
        assertNull(web["mine"])
        assertEquals(emptySet<String>(), saved[me.id])
    }

    @Test
    fun `звичайний точний показ поруч не гасне, а новий показ забуває зустріч`() {
        me.at(50.4501, 30.5234)
        olena.at(50.4502, 30.5234)
        assertTrue(me.hub.setPrecision(olena.id, PeerWhere.Precision.EXACT, until))
        assertTrue(olena.hub.setPrecision(me.id, PeerWhere.Precision.EXACT, until))
        now += 3 * 60_000L
        me.hub.ask(olena.id)
        olena.hub.ask(me.id)
        assertNotNull(me.hub.view(olena.id).theirs)
        assertEquals(false, me.hub.view(olena.id).mine?.meet)
        assertNull(me.hub.view(olena.id).metAtMs)
    }

    @Test
    fun `до зустрічі — лише точно й зі строком`() {
        assertFalse(me.hub.setPrecision(olena.id, PeerWhere.Precision.QUARTER, until, meet = true))
        assertFalse(me.hub.setPrecision(olena.id, PeerWhere.Precision.EXACT, 0L, meet = true))
        assertNull(me.hub.view(olena.id).mine)
        assertEquals(until, WebMessengerWhere.meetUntil(PeerWhere.Precision.EXACT, until, T0))
        assertNull(WebMessengerWhere.meetUntil(PeerWhere.Precision.DISTRICT, until, T0))
        assertNull(WebMessengerWhere.meetUntil(PeerWhere.Precision.EXACT, T0 - 1, T0))
        assertNull(WebMessengerWhere.meetUntil(PeerWhere.Precision.EXACT, T0 + 7 * 60 * 60_000L, T0))
    }
}
