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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_790_000_000_000L

/**
 * Власник 26.09: «по геолокації іншим взагалі не працює». Два телефони в JVM:
 * «Показати» → маяк → конверт під спільним ключем → приймач → стан для веба.
 * Контакти звичайні: без звірки камерою.
 */
class PeerWhereTwoPhonesTest {

    private var now = T0

    private class Phone(val id: String) {
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
    }

    private val me = Phone("kyrylo")
    private val oksana = Phone("oksana")
    private val wire = mutableListOf<String>()

    private fun wireUp(a: Phone, b: Phone) {
        a.hub = PeerWhereHub(
            points = PeerPointsRepository { now },
            roster = { listOf(b.asContact()) },
            fix = { a.fix },
            send = { to, body ->
                val letter = PeerMessage("m-${wire.size}", a.id, to, body, now, signature = "s".repeat(86))
                val frame = PeerEnvelope.seal(letter, a.keyWith(b))!!.frame
                wire += frame
                val opened = PeerEnvelope.open(frame) { from -> if (from == a.id) b.keyWith(a) else null }
                b.hub.absorb(opened!!)
            },
            fixAllowed = { true },
            grantStore = object : PeerWhereGrants {
                override fun load() = emptyMap<String, PeerWhere.Precision>()
                override fun save(grants: Map<String, PeerWhere.Precision>) {}
            },
            nowMs = { now },
        )
    }

    init {
        wireUp(me, oksana)
        wireUp(oksana, me)
    }

    private fun navFix(satAheadMs: Long?) = LocationFix(
        50.4501, 30.5234,
        accuracyM = 8f,
        grade = FixGrade.NAVIGATION,
        gpsTimeMs = satAheadMs?.let { now + it },
        register = FixRegister.MEASURED,
    )

    private fun theirsAtOksana() = oksana.hub.view(me.id).theirs

    @Test
    fun `місце доходить до людини, хоч супутниковий годинник і випереджає телефонний`() {
        me.fix = navFix(satAheadMs = 300)
        assertTrue(me.hub.setPrecision(oksana.id, PeerWhere.Precision.DISTRICT, T0 + 60 * 60_000L))

        assertEquals("кадр не пішов у мережу", 1, wire.size)
        assertEquals(PeerWhere.Precision.DISTRICT, theirsAtOksana()?.precision)
        val web = WebMessengerWhere.fields(oksana.hub.view(me.id), now)
        assertEquals(true, web["eligible"])
        assertNotNull("у веба Оксани немає «kyrylo показує своє місце»", web["theirs"])
    }

    @Test
    fun `«Показати» без свіжого виміру доходить, щойно чип дасть вимір`() {
        assertTrue(me.hub.setPrecision(oksana.id, PeerWhere.Precision.QUARTER, T0 + 60 * 60_000L))
        assertTrue(wire.isEmpty())
        assertNull(theirsAtOksana())

        now += 20_000L
        me.fix = navFix(satAheadMs = null)
        me.hub.onFix()

        assertEquals(1, wire.size)
        assertEquals(PeerWhere.Precision.QUARTER, theirsAtOksana()?.precision)

        me.hub.onFix()
        assertEquals("кожен вимір будить дріт — це нагрів", 1, wire.size)
    }

    @Test
    fun `вимір без дозволу нікому нічого не шле`() {
        me.fix = navFix(satAheadMs = null)
        me.hub.onFix()
        assertTrue(wire.isEmpty())
    }

    // Збірка 18: після «Зупинити» новий показ не доходив до відкритої розмови.
    @Test
    fun `після «Зупинити» новий показ доходить і будить відкриту розмову`() {
        me.fix = navFix(satAheadMs = null)
        assertTrue(me.hub.setPrecision(oksana.id, PeerWhere.Precision.DISTRICT, T0 + 60 * 60_000L))
        assertNotNull(theirsAtOksana())

        now += 30_000L
        val beforeStop = oksana.hub.changes.value
        me.hub.setPrecision(oksana.id, null)
        assertNull("«Зупинити» не дійшло до Оксани", theirsAtOksana())
        assertTrue("зупинка не розбудила розмову Оксани", oksana.hub.changes.value > beforeStop)

        now += 30_000L
        val beforeAgain = oksana.hub.changes.value
        assertTrue(me.hub.setPrecision(oksana.id, PeerWhere.Precision.DISTRICT, now + 60 * 60_000L))
        assertNotNull("новий показ не дійшов до Оксани", theirsAtOksana())
        assertTrue("новий показ не розбудив відкриту розмову Оксани", oksana.hub.changes.value > beforeAgain)
    }
}
