package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключ, який живе рівно одну зустріч.
 *
 * Кожен випадок тут — про одну поразку, після якої «свіжий ключ» був би лише
 * словом: обидва боки вивели РІЗНЕ; підміна ефемерного ключа пройшла; підпис
 * перевірили не тим ключем; дві половини з різних зустрічей склались в одну;
 * повторений привіт дав той самий ключ; ключ зустрічі виявився старим ключем
 * пари.
 *
 * І головне, заради чого все: «a recorded frame stays ciphertext once the
 * meeting is over» — той самий захват, що в [PeerForwardSecrecyTest], тільки
 * тут він мусить не дати нічого.
 */
class PeerSessionTest {

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val pubB64: String = b64(signing.publicKey)
        val dhPubB64: String = b64(dh.publicKey)
    }

    private val marta = Phone("marta-fp")
    private val kyrylo = Phone("kyrylo-fp")

    /** Повний обмін: Марта починає, Кирило відповідає. */
    private fun meet(
        initiator: Phone = marta,
        responder: Phone = kyrylo,
        nowMs: Long = 1_000L,
    ): Pair<PeerSession.Session, PeerSession.Session> {
        val offer = PeerSession.offer(initiator.id, responder.id, initiator.signing.privateKey)!!
        val hello = PeerSession.parse(offer.frame)!!
        val answer = PeerSession.answer(
            theirHello = hello,
            myId = responder.id,
            theirSigningPubB64 = initiator.pubB64,
            mySigningKey = responder.signing.privateKey,
            myStaticDh = responder.dh.privateKey,
            theirStaticDhB64 = initiator.dhPubB64,
            nowMs = nowMs,
        )!!
        val mine = PeerSession.finish(
            offer = offer,
            theirHello = PeerSession.parse(answer.frame)!!,
            theirSigningPubB64 = responder.pubB64,
            myStaticDh = initiator.dh.privateKey,
            theirStaticDhB64 = responder.dhPubB64,
            nowMs = nowMs,
        )!!
        return mine to answer.session
    }

    private fun message(body: String = "виходь") = PeerMessage(
        id = "m-1",
        fromId = marta.id,
        toId = kyrylo.id,
        body = body,
        sentAtMs = 1_000L,
        signature = "cGlkcGlz",
    )

    @Test
    fun `both sides of one meeting reach the same key and the same name`() {
        val (hers, his) = meet()
        assertEquals(hers.sid, his.sid)
        assertTrue("розійшлись ключі — жоден лист не відкриється", hers.key.contentEquals(his.key))
        assertEquals(32, hers.key.size)
        assertEquals(kyrylo.id, hers.peerId)
        assertEquals(marta.id, his.peerId)
    }

    @Test
    fun `every meeting gets its own key`() {
        val (first, _) = meet()
        val (second, _) = meet()
        assertNotEquals("дві зустрічі з тим самим ключем — це один ключ", first.sid, second.sid)
        assertFalse(first.key.contentEquals(second.key))
    }

    @Test
    fun `the meeting key is not the pair key`() {
        // Інакше «ключ зустрічі» був би новою назвою старого ключа, і захоплений
        // телефон відкривав би все так само.
        val (hers, _) = meet()
        val pair = PeerChannel.derive(
            marta.dh.privateKey, kyrylo.dhPubB64, marta.id, kyrylo.id,
        )!!
        assertFalse(hers.key.contentEquals(pair))
    }

    @Test
    fun `a recorded frame stays ciphertext once the meeting is over`() {
        // Слабша форма: тут атакуючому дано лише довгі ключі, БЕЗ записаного
        // рукостискання. Повну модель — з обома привітаннями, які видно в
        // мережі, — тримає PeerForwardSecrecyTest; саме вона ловить прибирання
        // ефемерного секрету, а ця — ні.
        val (hers, his) = meet()
        val recorded = PeerEnvelope.sealV4(message("PH4-RECORDED"), hers.sid, hers.key)!!.frame

        val cache = PeerSession.Cache()
        cache.put(his, "binding", 1_000L)
        assertEquals(
            "поки зустріч жива — лист читається",
            "PH4-RECORDED",
            PeerEnvelope.open(
                recorded,
                sessionKey = { sid -> cache.bySid(sid, "binding", 1_000L)?.key },
            ) { null }?.body,
        )

        // Зустріч скінчилась: процес помер, кеш очищено. Ніде на диску нічого
        // не лишилось, бо записувати його немає чим.
        cache.clear()

        val stolen = PeerChannel.derive(
            kyrylo.dh.privateKey, marta.dhPubB64, kyrylo.id, marta.id,
        )!!
        assertNull(
            "довгий ключ пари не відкриває кадр зустрічі",
            PeerEnvelope.open(recorded) { stolen },
        )
        assertNull(
            "і як «ключ сесії» він теж нічого не відкриває",
            PeerEnvelope.open(recorded, sessionKey = { stolen }) { null },
        )
        // Навіть маючи ОБИДВА приватні ключі й повний реєстр, вивести ключ
        // зустрічі нема з чого: ефемерну половину ніхто не зберігав.
        assertNull(PeerEnvelope.open(recorded, sessionKey = { marta.dh.privateKey }) { null })
        assertNull(PeerEnvelope.open(recorded, sessionKey = { kyrylo.dh.privateKey }) { null })
    }

    @Test
    fun `a substituted ephemeral key gets no session`() {
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val hello = PeerSession.parse(offer.frame)!!
        val theirs = X25519.generateKeypair()
        val swapped = hello.copy(eph = b64(theirs.publicKey))

        assertNull(
            "підмінений ефемерний ключ без підпису Марти не проходить",
            PeerSession.answer(
                swapped, kyrylo.id, marta.pubB64, kyrylo.signing.privateKey,
                kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
            ),
        )
    }

    @Test
    fun `a hello signed by somebody else is refused`() {
        val impostor = Phone("impostor-fp")
        val offer = PeerSession.offer(marta.id, kyrylo.id, impostor.signing.privateKey)!!

        assertNull(
            "привіт від імені Марти, підписаний чужим ключем",
            PeerSession.answer(
                PeerSession.parse(offer.frame)!!, kyrylo.id, marta.pubB64,
                kyrylo.signing.privateKey, kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
            ),
        )
    }

    @Test
    fun `two halves of two different meetings never make one`() {
        // Відповідь підписує ВЕСЬ обмін, тож відповідь із однієї зустрічі,
        // підсунута ініціатору іншої, не сходиться.
        val offerOne = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val offerTwo = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val answerToOne = PeerSession.answer(
            PeerSession.parse(offerOne.frame)!!, kyrylo.id, marta.pubB64,
            kyrylo.signing.privateKey, kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
        )!!

        assertNull(
            PeerSession.finish(
                offerTwo, PeerSession.parse(answerToOne.frame)!!, kyrylo.pubB64,
                marta.dh.privateKey, kyrylo.dhPubB64, 1_000L,
            ),
        )
        offerOne.discard()
    }

    @Test
    fun `an initiator hello replayed at a fresh responder buys nothing`() {
        // Записаний привіт можна повторити — і саме тому свіжість дає ОБИДВА
        // боки: відповідач щоразу кидає свою половину, і ключ виходить інший.
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val hello = PeerSession.parse(offer.frame)!!
        val first = PeerSession.answer(
            hello, kyrylo.id, marta.pubB64, kyrylo.signing.privateKey,
            kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
        )!!
        val second = PeerSession.answer(
            hello, kyrylo.id, marta.pubB64, kyrylo.signing.privateKey,
            kyrylo.dh.privateKey, marta.dhPubB64, 2_000L,
        )!!

        assertNotEquals(first.session.sid, second.session.sid)
        assertFalse(first.session.key.contentEquals(second.session.key))
        offer.discard()
    }

    @Test
    fun `a hello echoed straight back agrees nothing`() {
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val mirrored = PeerSession.parse(offer.frame)!!
            .copy(from = kyrylo.id, to = marta.id)

        assertNull(
            PeerSession.finish(
                offer, mirrored, kyrylo.pubB64, marta.dh.privateKey, kyrylo.dhPubB64, 1_000L,
            ),
        )
    }

    @Test
    fun `an offer answers exactly once`() {
        // Обнулені байти теж «затискаються» у придатний скаляр, тож друге
        // використання дало б інший ключ мовчки, замість відмови.
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val answer = PeerSession.answer(
            PeerSession.parse(offer.frame)!!, kyrylo.id, marta.pubB64,
            kyrylo.signing.privateKey, kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
        )!!
        val reply = PeerSession.parse(answer.frame)!!

        assertNotNull(
            PeerSession.finish(
                offer, reply, kyrylo.pubB64, marta.dh.privateKey, kyrylo.dhPubB64, 1_000L,
            ),
        )
        assertNull(
            "друга спроба мусить ВІДМОВИТИ, а не вивести інший ключ",
            PeerSession.finish(
                offer, reply, kyrylo.pubB64, marta.dh.privateKey, kyrylo.dhPubB64, 1_000L,
            ),
        )
    }

    @Test
    fun `the long-term key is a floor the ephemerals cannot lower`() {
        // Статичну половину домішано навмисно: без правильного ДОВГОГО ключа
        // шифрування ключ зустрічі не виходить, хоч би яким бездоганним був
        // обмін ефемерними. Тому PH4 ніколи не слабший за те, що вже їздило.
        val stranger = Phone("stranger-fp")
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val answer = PeerSession.answer(
            PeerSession.parse(offer.frame)!!, kyrylo.id, marta.pubB64,
            kyrylo.signing.privateKey, kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
        )!!
        val wrongStatic = PeerSession.finish(
            offer, PeerSession.parse(answer.frame)!!, kyrylo.pubB64,
            marta.dh.privateKey, stranger.dhPubB64, 1_000L,
        )!!

        assertEquals("імена зустрічі збігаються — а ключі ні", answer.session.sid, wrongStatic.sid)
        assertFalse(wrongStatic.key.contentEquals(answer.session.key))
        val frame = PeerEnvelope.sealV4(message(), wrongStatic.sid, wrongStatic.key)!!.frame
        assertNull(PeerEnvelope.open(frame, sessionKey = { answer.session.key }) { null })
    }

    @Test
    fun `a peer with no encryption key gets no session`() {
        // Рядок реєстру зі старої збірки не має X25519 узагалі. Вивести з
        // порожнього рядка «ключ» означало б шифрувати на значення, яке знають усі.
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        assertNull(
            PeerSession.answer(
                PeerSession.parse(offer.frame)!!, kyrylo.id, marta.pubB64,
                kyrylo.signing.privateKey, kyrylo.dh.privateKey, "", 1_000L,
            ),
        )
        offer.discard()
    }

    @Test
    fun `a hello addressed to somebody else is not answered`() {
        val offer = PeerSession.offer(marta.id, "хтось-інший", marta.signing.privateKey)!!
        assertNull(
            PeerSession.answer(
                PeerSession.parse(offer.frame)!!, kyrylo.id, marta.pubB64,
                kyrylo.signing.privateKey, kyrylo.dh.privateKey, marta.dhPubB64, 1_000L,
            ),
        )
        offer.discard()
    }

    @Test
    fun `nonsense on the wire is refused before any key is touched`() {
        assertNull(PeerSession.parse(""))
        assertNull(PeerSession.parse("{"))
        assertNull(PeerSession.parse("""{"v":2,"from":"a","to":"b","eph":"x","n":"y","sig":"z"}"""))

        val good = PeerSession.parse(
            PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!.frame,
        )!!
        // Довжини ключів і підпису перевіряються ДО арифметики.
        assertNull(PeerSession.parse(PeerSession.encode(good.copy(eph = "AAAA"))))
        assertNull(PeerSession.parse(PeerSession.encode(good.copy(n = "AAAA"))))
        assertNull(PeerSession.parse(PeerSession.encode(good.copy(sig = "AAAA"))))
        assertNull(PeerSession.parse(PeerSession.encode(good.copy(from = ""))))
        // Роздільник у полі — те саме, що в квитанції: відмова, а не екранування.
        assertNull(PeerSession.parse(PeerSession.encode(good.copy(from = "aaaabbbb"))))
    }

    @Test
    fun `the version field really travels`() {
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        assertTrue(offer.frame.contains("\"v\":1"))
        offer.discard()
    }

    @Test
    fun `nothing secret is on the wire`() {
        val offer = PeerSession.offer(marta.id, kyrylo.id, marta.signing.privateKey)!!
        val (hers, _) = meet()
        val frame = offer.frame
        assertFalse(frame.contains(b64(marta.dh.privateKey)))
        assertFalse(frame.contains(b64(marta.signing.privateKey)))
        assertFalse(frame.contains(b64(hers.key)))
        offer.discard()
    }

    // — Кеш ————————————————————————————————————————————————————————————————

    @Test
    fun `a session is found by the name its frames carry`() {
        val cache = PeerSession.Cache()
        val (_, his) = meet()
        cache.put(his, "bind-1", 1_000L)

        assertEquals(his.sid, cache.bySid(his.sid, "bind-1", 1_000L)?.sid)
        assertNull("невідоме імʼя — не ключ", cache.bySid("0000000000000000", "bind-1", 1_000L))
    }

    @Test
    fun `a changed roster key is never served an old session`() {
        // Та сама конструкція, що в PeerChannel.Cache: запис привʼязаний до
        // ДОВГИХ ключів, тож після заміни ключа шукається інший запис.
        val cache = PeerSession.Cache()
        val (_, his) = meet()
        cache.put(his, PeerSession.binding(peer("KEY-1", "DH-1")), 1_000L)

        assertNull(cache.bySid(his.sid, PeerSession.binding(peer("KEY-2", "DH-1")), 1_000L))
        assertNull(cache.bySid(his.sid, PeerSession.binding(peer("KEY-1", "DH-2")), 1_000L))
        assertNotNull(cache.bySid(his.sid, PeerSession.binding(peer("KEY-1", "DH-1")), 1_000L))
    }

    @Test
    fun `the newest meeting with a peer is the one used to send`() {
        val cache = PeerSession.Cache()
        val (older, _) = meet(nowMs = 1_000L)
        val (newer, _) = meet(nowMs = 2_000L)
        cache.put(older, "bind", 1_000L)
        cache.put(newer, "bind", 2_000L)

        assertEquals(newer.sid, cache.newestFor(kyrylo.id, "bind", 2_000L)?.sid)
        // Старіша не викидається: кадр, що вже поїхав під нею, ще може прийти.
        assertNotNull(cache.bySid(older.sid, "bind", 2_000L))
        assertNull(cache.newestFor("хтось-інший", "bind", 2_000L))
    }

    @Test
    fun `an idle meeting and an old meeting both end`() {
        val cache = PeerSession.Cache(idleMs = 100L, lifetimeMs = 1_000L)
        val (idle, _) = meet(nowMs = 0L)
        cache.put(idle, "bind", 0L)
        assertNull("забута на годину зустріч закінчилась", cache.bySid(idle.sid, "bind", 500L))

        val (aged, _) = meet(nowMs = 0L)
        cache.put(aged, "bind", 0L)
        // Постійно вживана, але надто стара: перезаключається за строком життя.
        var at = 0L
        while (at < 1_100L) {
            at += 50L
            cache.bySid(aged.sid, "bind", at)
        }
        assertNull("зустріч не триває довше за свій строк", cache.bySid(aged.sid, "bind", 1_200L))
        assertEquals(0, cache.size())
    }

    @Test
    fun `only the newest few meetings per peer are kept`() {
        val cache = PeerSession.Cache(maxPerPeer = 2)
        val kept = mutableListOf<PeerSession.Session>()
        repeat(4) { n ->
            val (mine, _) = meet(nowMs = 1_000L + n)
            cache.put(mine, "bind", 1_000L + n)
            kept += mine
        }
        assertEquals(2, cache.size())
        assertNull(cache.bySid(kept[0].sid, "bind", 1_010L))
        assertNotNull(cache.bySid(kept[3].sid, "bind", 1_010L))
    }

    @Test
    fun `forgetting a peer and clearing both really take the key away`() {
        val cache = PeerSession.Cache()
        val (mine, _) = meet()
        cache.put(mine, "bind", 1_000L)
        cache.forget(kyrylo.id)
        assertEquals(0, cache.size())
        assertNull(cache.bySid(mine.sid, "bind", 1_000L))
        assertTrue(
            "забути ключ — це стерти байти, а не викинути посилання",
            mine.key.all { it == 0.toByte() },
        )

        val (other, _) = meet()
        cache.put(other, "bind", 1_000L)
        cache.clear()
        assertEquals(0, cache.size())
        assertTrue(other.key.all { it == 0.toByte() })
    }

    @Test
    fun `one refused session does not take the others down`() {
        val cache = PeerSession.Cache()
        val (withKyrylo, _) = meet()
        val bohdan = Phone("bohdan-fp")
        val (withBohdan, _) = meet(responder = bohdan)
        cache.put(withKyrylo, "bind-k", 1_000L)
        cache.put(withBohdan, "bind-b", 1_000L)

        cache.forgetSid(withKyrylo.sid)
        assertNull(cache.bySid(withKyrylo.sid, "bind-k", 1_000L))
        assertNotNull(cache.bySid(withBohdan.sid, "bind-b", 1_000L))
    }

    private fun peer(key: String, dhKey: String) = PeerIdentity(
        id = kyrylo.id,
        name = "Кирило",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = key,
        dhPubKey = dhKey,
    )
}
