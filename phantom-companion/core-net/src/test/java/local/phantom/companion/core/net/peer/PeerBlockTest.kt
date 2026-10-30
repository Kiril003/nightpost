package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L

/**
 * Заблокований не доходить до журналу — і кадри тут СПРАВЖНІ.
 *
 * Жоден рядок нижче не складений руками під перевірку: лист пече
 * [PeerEnvelope.seal] на ключі справжньої пари, вітання — [PeerSession.offer],
 * знайомство — [PeerIntro.offer]. Тому це не дзеркало приймача, а той самий
 * дріт, яким воно летить між двома телефонами.
 */
class PeerBlockTest {

    /**
     * Імʼя — ВІДБИТОК ключа, а не літери: [PeerIntro.open] звіряє
     * `fingerprint(pub) == from` і вигаданий рядок відкидає. Це й зловило
     * першу версію цієї перевірки, де імʼя було набране пальцями.
     */
    private class Phone(val name: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val pubB64: String = b64(signing.publicKey)
        val dhPubB64: String = b64(dh.publicKey)
        val id: String = PeerIntro.fingerprint(pubB64)

        val invite = PeerInvite.Invite(
            peerId = id,
            name = name,
            pubKey = pubB64,
            dhPubKey = dhPubB64,
            host = "127.0.0.1",
            port = 4444,
        )
    }

    private val me = Phone("Кирило")
    private val them = Phone("Назойливий")

    private val pairKey: ByteArray =
        PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id)!!

    private fun letter(body: String, from: Phone = them, to: Phone = me) = PeerSealing.seal(
        PeerMessage(
            id = "m-" + body.hashCode(),
            fromId = from.id,
            toId = to.id,
            body = body,
            sentAtMs = NOW,
        ),
        from.signing.privateKey,
    )

    private fun envelope(body: String): String =
        PeerEnvelope.seal(letter(body), pairKey)!!.frame

    private val blocked = setOf(them.id)

    // ── реєстр ───────────────────────────────────────────────────────────

    @Test
    fun `порожній список не блокує нікого`() {
        assertFalse(PeerBlock.blocks(emptySet(), them.id))
        assertFalse(PeerBlock.refusesEnvelope(emptySet(), envelope("привіт")))
    }

    @Test
    fun `порожнє імʼя не блокує нікого`() {
        // Кадр без відправника — не заблокований кадр, а не кадр.
        assertFalse(PeerBlock.blocks(setOf(""), ""))
        assertEquals(emptySet<String>(), PeerBlock.block(emptySet(), ""))
        assertEquals(emptySet<String>(), PeerBlock.block(emptySet(), "   ".trim()))
    }

    @Test
    fun `двічі заблокований — один рядок`() {
        val once = PeerBlock.block(emptySet(), them.id)
        assertEquals(once, PeerBlock.block(once, them.id))
        assertEquals(1, PeerBlock.block(once, them.id).size)
    }

    @Test
    fun `стеля списку не дає йому рости без краю`() {
        val full = (1..PeerBlock.MAX_KEPT).map { "id-$it" }.toSet()
        assertEquals(PeerBlock.MAX_KEPT, full.size)
        assertEquals(full, PeerBlock.block(full, "ще-один"))
    }

    // ── лист ─────────────────────────────────────────────────────────────

    @Test
    fun `лист від заблокованого відкидається до розшифрування`() {
        val frame = envelope("я знаю, де ти")
        // Він справжній: без блокування він відчиняється й читається.
        val opened = PeerEnvelope.open(frame, sessionKey = { pairKey }) { pairKey }
        assertNotNull("кадр мусить бути справжнім, інакше перевірка порожня", opened)
        assertEquals("я знаю, де ти", opened!!.body)

        assertTrue(PeerBlock.refusesEnvelope(blocked, frame))
    }

    @Test
    fun `рід кадру не рятує — усе від цієї людини`() {
        // Слово, місце, шматок файла, картка композера, дзвінок, профіль,
        // група, службовий кадр: кожен рід, який продукт складає сам.
        val bodies = PeerFrames.Kind.entries.map { it.mark + "щось" } + "звичайне слово"
        for (body in bodies) {
            assertTrue(
                "рід $body проліз повз блокування",
                PeerBlock.refusesEnvelope(blocked, envelope(body)),
            )
        }
    }

    @Test
    fun `дзвінок від заблокованого не доходить нікуди`() {
        val frame = envelope(PeerCall.SIGNAL + """{"kind":"offer"}""")
        // Саме на цьому тілі апарат піднімається — рід упізнано.
        val opened = PeerEnvelope.open(frame, sessionKey = { pairKey }) { pairKey }!!
        assertTrue("тіло мусить бути кадром дзвінка", PeerCall.isCall(opened.body))
        assertEquals(PeerFrames.Kind.CALL_SIGNAL, PeerFrames.kindOf(opened.body))

        assertTrue(PeerBlock.refusesEnvelope(blocked, frame))
    }

    @Test
    fun `лист від незаблокованого проходить`() {
        val third = Phone("Третій")
        val key = PeerChannel.derive(me.dh.privateKey, third.dhPubB64, me.id, third.id)!!
        val frame = PeerEnvelope.seal(letter("це я", from = third), key)!!.frame
        assertFalse(PeerBlock.refusesEnvelope(blocked, frame))
    }

    @Test
    fun `сміття не називається блокуванням`() {
        // Кадр, який не розібрався, — не заблокований; з ним розмовляє той,
        // хто читає далі, інакше «відкинуто» перестало б щось означати.
        assertFalse(PeerBlock.refusesEnvelope(blocked, "не кадр"))
        assertFalse(PeerBlock.refusesEnvelope(blocked, ""))
        assertFalse(PeerBlock.refusesHello(blocked, "{}"))
        assertFalse(PeerBlock.refusesIntro(blocked, "{"))
    }

    // ── вітання ──────────────────────────────────────────────────────────

    @Test
    fun `вітання від заблокованого відкидається до арифметики`() {
        val offer = PeerSession.offer(them.id, me.id, them.signing.privateKey)!!
        // Справжнє: без блокування воно відповідається ключем зустрічі.
        assertNotNull(
            "вітання мусить бути справжнім",
            PeerSession.answer(
                theirHello = PeerSession.parse(offer.frame)!!,
                myId = me.id,
                theirSigningPubB64 = them.pubB64,
                mySigningKey = me.signing.privateKey,
                myStaticDh = me.dh.privateKey,
                theirStaticDhB64 = them.dhPubB64,
                nowMs = NOW,
            ),
        )

        assertTrue(PeerBlock.refusesHello(blocked, offer.frame))
    }

    @Test
    fun `без ключа зустрічі дальший лист заблокованого не відчиняється`() {
        // Чому відмова на вітанні коштує дорожче, ніж здається: кадр зустрічі
        // відчиняється ЛИШЕ ключем тієї зустрічі, і довічний ключ пари його не
        // бере — тож відмовлене вітання глушить і все, що поїхало б після.
        val offer = PeerSession.offer(them.id, me.id, them.signing.privateKey)!!
        val answer = PeerSession.answer(
            theirHello = PeerSession.parse(offer.frame)!!,
            myId = me.id,
            theirSigningPubB64 = them.pubB64,
            mySigningKey = me.signing.privateKey,
            myStaticDh = me.dh.privateKey,
            theirStaticDhB64 = them.dhPubB64,
            nowMs = NOW,
        )!!
        val theirs = PeerSession.finish(
            offer = offer,
            theirHello = answer.hello,
            theirSigningPubB64 = me.pubB64,
            myStaticDh = them.dh.privateKey,
            theirStaticDhB64 = me.dhPubB64,
            nowMs = NOW,
        )!!
        val sealed = PeerEnvelope.sealV4(letter("а тепер?"), theirs.sid, theirs.key)!!

        assertNull(
            "кадр зустрічі не має відчинятись довічним ключем пари",
            PeerEnvelope.open(sealed.frame, sessionKey = { null }) { pairKey },
        )
        assertTrue(PeerBlock.refusesEnvelope(blocked, sealed.frame))
    }

    // ── знайомство ───────────────────────────────────────────────────────

    @Test
    fun `заблокований не вписує себе назад знайомством`() {
        val offer = PeerIntro.offer(
            me = them.invite,
            toId = me.id,
            theirDhPubB64 = me.dhPubB64,
            mySigningKey = them.signing.privateKey,
            myDhPrivate = them.dh.privateKey,
        )!!
        // Справжнє: без блокування воно відчиняється й дає рядок у реєстр.
        val card = PeerIntro.open(offer.frame, myId = me.id, myDhPrivate = me.dh.privateKey)
        assertNotNull("знайомство мусить бути справжнім", card)
        assertEquals(them.id, card!!.peerId)

        assertTrue(PeerBlock.refusesIntro(blocked, offer.frame))
    }

    // ── розблокування ────────────────────────────────────────────────────

    @Test
    fun `розблокування повертає всі троє дверей`() {
        val letter = envelope("вибач")
        val hello = PeerSession.offer(them.id, me.id, them.signing.privateKey)!!.frame
        val intro = PeerIntro.offer(
            me = them.invite,
            toId = me.id,
            theirDhPubB64 = me.dhPubB64,
            mySigningKey = them.signing.privateKey,
            myDhPrivate = them.dh.privateKey,
        )!!.frame

        assertTrue(PeerBlock.refusesEnvelope(blocked, letter))
        assertTrue(PeerBlock.refusesHello(blocked, hello))
        assertTrue(PeerBlock.refusesIntro(blocked, intro))

        val after = PeerBlock.unblock(blocked, them.id)
        assertFalse(PeerBlock.refusesEnvelope(after, letter))
        assertFalse(PeerBlock.refusesHello(after, hello))
        assertFalse(PeerBlock.refusesIntro(after, intro))
    }

    @Test
    fun `минулі листи не воскресають — їх ніколи не приймали`() {
        // Заблокований пише тричі; журнал за цей час не змінюється жодного
        // разу, бо кожен кадр відкинуто на вході. Розблокування повертає
        // ДОРОГУ, а не написане: воскресати нема чого.
        var log = listOf(
            PeerMessage(
                id = "старий",
                fromId = them.id,
                toId = me.id,
                body = "до блокування",
                sentAtMs = NOW - 1,
            ),
        )
        var live = blocked
        for (n in 1..3) {
            val frame = envelope("поки заблоковано $n")
            if (PeerBlock.refusesEnvelope(live, frame)) continue
            log = PeerOutbox.enqueue(log, PeerEnvelope.open(frame, { pairKey }) { pairKey }!!)
        }
        assertEquals(listOf("старий"), log.map { it.id })

        live = PeerBlock.unblock(live, them.id)
        val frame = envelope("після")
        assertFalse(PeerBlock.refusesEnvelope(live, frame))
        log = PeerOutbox.enqueue(log, PeerEnvelope.open(frame, { pairKey }) { pairKey }!!)
        assertEquals(listOf("до блокування", "після"), log.map { it.body })
    }

    // ── таємниця ─────────────────────────────────────────────────────────

    @Test
    fun `на дроті немає роду, яким можна сказати «я тебе заблокував»`() {
        // Це і є вся гарантія другого рішення, і вона перевірна: якби
        // блокування щось слало, воно мусило б стояти в цьому переліку —
        // [PeerOutbox.isSpoken] має вичерпний when і не дав би промовчати.
        val marks = PeerFrames.Kind.entries.map { it.mark }
        assertFalse("рід про блокування не має існувати", marks.any { it.contains("BLK") })
        assertFalse(
            "PeerBlock не складає жодного кадру",
            PeerBlock::class.java.declaredMethods.any { it.returnType == String::class.java },
        )
    }

    @Test
    fun `відмова заблокованому виглядає як відмова кадру, що не відчинився`() {
        // З того боку різниці немає: у обох випадках квитанції немає, бо кадр
        // не прийняли. Тут це видно як однакове рішення на однаковому вході.
        val fromBlocked = envelope("ти мене заблокував?")
        val stale = PeerEnvelope.seal(letter("старий ключ"), ByteArray(32))!!.frame
        assertTrue(PeerBlock.refusesEnvelope(blocked, fromBlocked))
        assertNull(
            "кадр чужим ключем і так не відчиняється",
            PeerEnvelope.open(stale, sessionKey = { null }) { pairKey },
        )
    }
}
