package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Де ключ заходить у реєстр — і що при цьому мусить статись.
 *
 * Два входи з дроту: знайомство (PH5, [PeerIntro]) і запрошення (PH2,
 * [PeerInvite]); обидва меш зводить до [PeerRoster.upsert]. Тут ті самі
 * кадри складаються по-справжньому — підпис, шифр, відкриття — а тоді другий
 * раз під новим ключем обміну, як після перевстановлення телефона або підміни:
 * з дроту ці два випадки не відрізнити, і саме тому обидва мусять кричати.
 */
class PeerKeyIntakeTest {

    private class Person(
        name: String,
        val signing: Ed25519.Keypair = Ed25519.generateKeypair(),
        val dh: X25519.Keypair = dhKeypair(),
    ) {
        val pub = b64(signing.publicKey)
        val id = PeerIntro.fingerprint(pub)
        val code = PeerInvite.Invite(
            peerId = id, name = name, pubKey = pub,
            dhPubKey = b64(dh.publicKey), host = "127.0.0.1", port = 47_331,
        )
    }

    private fun introduce(from: Person, to: Person) = PeerIntro.offer(
        me = from.code,
        toId = to.id,
        theirDhPubB64 = to.code.dhPubKey,
        mySigningKey = from.signing.privateKey,
        myDhPrivate = from.dh.privateKey,
    )!!

    /** Рядок так, як його складає PeerMesh.introduced. */
    private fun row(card: PeerIntro.Introduction, at: Long) = PeerIdentity(
        id = card.peerId,
        name = card.name,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = card.pubKey,
        dhPubKey = card.dhPubKey,
        addedAtMs = at,
        keySource = PeerIdentity.KeySource.SHARED,
        introducedAtMs = at,
    )

    @Test
    fun `a reinstalled phone introducing itself under a new exchange key raises the alarm`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val first = PeerIntro.open(introduce(kyrylo, marta).frame, marta.id, marta.dh.privateKey)!!
        var roster = PeerRoster.upsert(emptyList(), row(first, 1_000L), nowMs = 1_000L)
        roster = PeerRoster.markVerified(roster, kyrylo.id, 2_000L)
        val before = PeerSafety.number(marta.pub, marta.code.dhPubKey, first.pubKey, first.dhPubKey)

        // Той самий підписний ключ, свіжий ключ обміну: кадр відкривається, імʼя те саме.
        val again = Person("Кирило", signing = kyrylo.signing)
        val second = PeerIntro.open(introduce(again, marta).frame, marta.id, marta.dh.privateKey)!!
        assertEquals(kyrylo.id, second.peerId)
        roster = PeerRoster.upsert(roster, row(second, 3_000L), nowMs = 3_000L)

        val held = roster.single()
        assertTrue("новий ключ під старим іменем мусить кричати", held.keyAlarm)
        assertEquals(0L, held.verifiedAtMs)
        assertEquals(3_000L, held.keyChangedAtMs)
        assertEquals(again.code.dhPubKey, held.dhPubKey)
        assertNotEquals(
            "число на картці мусить змінитись разом із ключем",
            before,
            PeerSafety.number(marta.pub, marta.code.dhPubKey, held.pubKey, held.dhPubKey),
        )
    }

    @Test
    fun `a pasted invite carrying a new key under a known name raises it too`() {
        val kyrylo = Person("Кирило")
        val v1 = inviteIn(PeerInvite.encode(kyrylo.code))!!
        var roster = PeerRoster.upsert(
            emptyList(),
            PeerIdentity(v1.peerId, v1.name, PeerIdentity.Kind.PERSON, v1.pubKey, v1.dhPubKey, addedAtMs = 1_000L),
            nowMs = 1_000L,
        )
        roster = PeerRoster.markVerified(roster, kyrylo.id, 2_000L)

        val v2 = inviteIn(PeerInvite.encode(kyrylo.code.copy(dhPubKey = b64(dhKeypair().publicKey))))!!
        roster = PeerRoster.upsert(
            roster,
            PeerIdentity(v2.peerId, v2.name, PeerIdentity.Kind.PERSON, v2.pubKey, v2.dhPubKey, addedAtMs = 3_000L),
            nowMs = 3_000L,
        )
        assertTrue(roster.single().keyAlarm)
        assertEquals(0L, roster.single().verifiedAtMs)
    }

    @Test
    fun `the same key arriving again by any door raises nothing and keeps the tick`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val card = PeerIntro.open(introduce(kyrylo, marta).frame, marta.id, marta.dh.privateKey)!!
        var roster = PeerRoster.upsert(emptyList(), row(card, 1_000L), nowMs = 1_000L)
        roster = PeerRoster.markVerified(roster, kyrylo.id, 2_000L)
        val again = PeerIntro.open(introduce(kyrylo, marta).frame, marta.id, marta.dh.privateKey)!!
        roster = PeerRoster.upsert(roster, row(again, 3_000L), nowMs = 3_000L)
        assertFalse(roster.single().keyAlarm)
        assertEquals(2_000L, roster.single().verifiedAtMs)
    }

    @Test
    fun `verifying off the screen raises the channel, verifying by voice does not`() {
        val kyrylo = Person("Кирило")
        val pasted = PeerIdentity(
            kyrylo.id, "Кирило", PeerIdentity.Kind.PERSON, kyrylo.pub, kyrylo.code.dhPubKey,
            keySource = PeerIdentity.KeySource.REMOTE,
        )
        val byVoice = PeerRoster.markVerified(listOf(pasted), kyrylo.id, 2_000L).single()
        assertTrue(byVoice.isVerified)
        assertFalse("голос не доводить шляху ключа", byVoice.isProven)
        assertEquals(PeerIdentity.KeySource.REMOTE, byVoice.keySource)

        val byCamera = PeerRoster.markVerified(
            listOf(pasted), kyrylo.id, 2_000L, PeerIdentity.KeySource.CAMERA,
        ).single()
        assertTrue("число, зняте камерою з її екрана, — це і є доведений ключ", byCamera.isProven)

        val notLowered = PeerRoster.markVerified(
            listOf(byCamera), kyrylo.id, 3_000L, PeerIdentity.KeySource.SHARED,
        ).single()
        assertEquals(PeerIdentity.KeySource.CAMERA, notLowered.keySource)
    }
}
