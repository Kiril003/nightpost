package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кого впускає знайомство, і — головне — кого ні.
 *
 * Владу представитись купує знання чужого коду. Все тут крутиться навколо
 * одного питання: чи справді її не можна купити нічим іншим.
 */
class PeerIntroTest {

    private class Person(val name: String) {
        val signing = Ed25519.generateKeypair()
        val dh = dhKeypair()
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

    @Test
    fun `whoever read the code can introduce themselves`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val card = PeerIntro.open(
            introduce(kyrylo, marta).frame,
            myId = marta.id,
            myDhPrivate = marta.dh.privateKey,
        )
        assertEquals(kyrylo.id, card?.peerId)
        assertEquals("Кирило", card?.name)
        assertEquals(kyrylo.code.dhPubKey, card?.dhPubKey)
        assertEquals(kyrylo.pub, card?.pubKey)
    }

    @Test
    fun `somebody who never saw the code cannot introduce themselves`() {
        val marta = Person("Марта")
        val stranger = Person("Чужий")
        // Той самий кадр, але складений під чиїмось іншим ключем обміну: рівно
        // те, що може зробити будь-хто на тій самій мережі, знаючи лише peerId.
        val notHers = Person("Не Марта")
        val frame = PeerIntro.offer(
            me = stranger.code,
            toId = marta.id,
            theirDhPubB64 = notHers.code.dhPubKey,
            mySigningKey = stranger.signing.privateKey,
            myDhPrivate = stranger.dh.privateKey,
        )!!.frame
        assertNull(
            "знання peerId не мусить впускати нікого",
            PeerIntro.open(frame, marta.id, marta.dh.privateKey),
        )
    }

    /**
     * Найдорожча тут атака: власник коду будує знайомство ЦІЛКОМ від чужого
     * імені — своїми ключами, але з чужим ідентифікатором. Шифр, AAD і підпис
     * при цьому сходяться, бо він складає весь кадр сам. Ловить лише те, що
     * імʼя мусить бути відбитком принесеного ключа.
     */
    @Test
    fun `a code holder cannot take a name whose key they do not hold`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val absent = Person("Оксана")
        val masked = kyrylo.code.copy(peerId = absent.id)
        val frame = PeerIntro.offer(
            me = masked,
            toId = marta.id,
            theirDhPubB64 = marta.code.dhPubKey,
            mySigningKey = kyrylo.signing.privateKey,
            myDhPrivate = kyrylo.dh.privateKey,
        )!!.frame
        assertNull(
            "інакше власник коду займає рядок людини, якої ще ніхто не додав",
            PeerIntro.open(frame, marta.id, marta.dh.privateKey),
        )
    }

    @Test
    fun `the sender's name is bound into the frame, not merely written on it`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val absent = Person("Оксана")
        val frame = introduce(kyrylo, marta).frame
        val forged = frame.replace("\"from\":\"${kyrylo.id}\"", "\"from\":\"${absent.id}\"")
        assertNotEquals(frame, forged)
        assertNull(
            "переписане поле мусить розсипати сам шифр",
            PeerIntro.open(forged, marta.id, marta.dh.privateKey),
        )
    }

    @Test
    fun `an introduction meant for somebody else is refused`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val oksana = Person("Оксана")
        assertNull(
            PeerIntro.open(introduce(kyrylo, marta).frame, oksana.id, oksana.dh.privateKey),
        )
    }

    @Test
    fun `an answer from another meeting cannot be passed off as this one`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val first = introduce(kyrylo, marta)
        val second = introduce(kyrylo, marta)
        val card = PeerIntro.open(first.frame, marta.id, marta.dh.privateKey)!!
        val answer = PeerIntro.answer(
            me = marta.code, to = card,
            mySigningKey = marta.signing.privateKey, myDhPrivate = marta.dh.privateKey,
        )!!

        assertEquals(
            marta.id,
            PeerIntro.open(answer, kyrylo.id, kyrylo.dh.privateKey, echoOf = first.nonceB64)
                ?.peerId,
        )
        assertNull(
            "відповідь на іншу пропозицію не є відповіддю на цю",
            PeerIntro.open(answer, kyrylo.id, kyrylo.dh.privateKey, echoOf = second.nonceB64),
        )
        assertNull(
            "і відповідь не є пропозицією",
            PeerIntro.open(answer, kyrylo.id, kyrylo.dh.privateKey),
        )
    }

    @Test
    fun `the words are not in the bytes`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val frame = introduce(kyrylo, marta).frame
        assertFalse("імʼя їде під ключем", frame.contains("Кирило"))
        assertFalse("підписний ключ теж", frame.contains(kyrylo.pub))
    }

    @Test
    fun `an introduction key is not the key letters travel under`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        val intro = PeerIntro.key(
            kyrylo.dh.privateKey, marta.code.dhPubKey, kyrylo.id, marta.id,
        )!!
        val letters = PeerChannel.derive(
            kyrylo.dh.privateKey, marta.code.dhPubKey, kyrylo.id, marta.id,
        )!!
        assertFalse(
            "спільні байти злили б два різні простори протоколу",
            intro.contentEquals(letters),
        )
    }

    @Test
    fun `both sides derive the same introduction key`() {
        val marta = Person("Марта")
        val kyrylo = Person("Кирило")
        assertTrue(
            PeerIntro.key(kyrylo.dh.privateKey, marta.code.dhPubKey, kyrylo.id, marta.id)!!
                .contentEquals(
                    PeerIntro.key(marta.dh.privateKey, kyrylo.code.dhPubKey, marta.id, kyrylo.id)!!,
                ),
        )
    }

    @Test
    fun `a row already proved by a delivery or a meeting owes nothing`() {
        val row = PeerIdentity(
            id = "a", name = "Марта", kind = PeerIdentity.Kind.PERSON,
            pubKey = "p", dhPubKey = "d",
        )
        assertTrue("свіжий рядок винен знайомство", PeerIntro.owes(row, hasDelivered = false))
        assertFalse(
            "дійшлий лист доводить те саме, що й знайомство",
            PeerIntro.owes(row, hasDelivered = true),
        )
        assertFalse(
            "як і склад ключа зустрічі — це і є вся міграція старих пар",
            PeerIntro.owes(row.copy(metAtMs = 5L), hasDelivered = false),
        )
        assertFalse(PeerIntro.owes(row.copy(introducedAtMs = 5L), hasDelivered = false))
        assertFalse(
            "рядок без ключа обміну не має чим знайомитись",
            PeerIntro.owes(row.copy(dhPubKey = ""), hasDelivered = false),
        )
        assertFalse(
            "спарований ПК — не співрозмовник",
            PeerIntro.owes(row.copy(kind = PeerIdentity.Kind.DEVICE), hasDelivered = false),
        )
    }

    @Test
    fun `a refusal buys quiet, and silence backs off instead of hammering`() {
        val fresh = PeerIntro.Known()
        assertTrue(PeerIntro.shouldOffer(fresh, 1_000L))

        val refused = PeerIntro.noted(fresh, PeerIntro.Answer.REFUSED, 1_000L)
        assertFalse(PeerIntro.shouldOffer(refused, 1_000L + PeerIntro.RETRY_REFUSAL_MS - 1))
        assertTrue(PeerIntro.shouldOffer(refused, 1_000L + PeerIntro.RETRY_REFUSAL_MS))

        var quiet = PeerIntro.noted(fresh, PeerIntro.Answer.SILENT, 0L)
        assertFalse("одна тиша не мусить коштувати таймаут щопʼять секунд", PeerIntro.shouldOffer(quiet, 5_000L))
        assertTrue(PeerIntro.shouldOffer(quiet, 10_000L))
        repeat(6) { quiet = PeerIntro.noted(quiet, PeerIntro.Answer.SILENT, 0L) }
        assertFalse("драбина мусить упиратись у стелю, а не рости", PeerIntro.shouldOffer(quiet, 29 * 60_000L))
        assertTrue(PeerIntro.shouldOffer(quiet, 30 * 60_000L))

        assertEquals(PeerIntro.Known(), PeerIntro.noted(refused, PeerIntro.Answer.INTRODUCED, 9L))
    }

    @Test
    fun `a changed key forgets that they ever held us`() {
        val was = PeerIdentity(
            id = "a", name = "Марта", kind = PeerIdentity.Kind.PERSON,
            pubKey = "p", dhPubKey = "d", introducedAtMs = 5L,
        )
        val same = PeerRoster.upsert(listOf(was), was.copy(name = "М"), nowMs = 10L)
        assertEquals(5L, same.single().introducedAtMs)

        val moved = PeerRoster.upsert(listOf(was), was.copy(dhPubKey = "d2"), nowMs = 10L)
        assertEquals(
            "доказ належав тим ключам; перевстановлений телефон загубив і реєстр",
            0L,
            moved.single().introducedAtMs,
        )
        assertTrue(PeerIntro.owes(moved.single(), hasDelivered = false))
    }
}
