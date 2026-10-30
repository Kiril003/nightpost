package local.phantom.companion.core.net.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Смайл ДОЇЖДЖАЄ до другого апарата — і стає там числом під рядком.
 *
 * Вада, заради якої цей сторож існує, та сама, що й у дзвінка, і в тому самому
 * місці: між намальованою спроможністю й дротом не було дороги. У вебі
 * `messengerStore.toggleReaction` міняв масив у памʼяті вкладки й кликав
 * `messengerNetworkEngine.sendReaction`, а той має рівно дві гілки, і ЖОДНА не
 * веде в меш: `p2p` кидає кадр у браузерну WebRTC-сітку, якої на телефоні
 * немає, друга — у `this.ws`, сокет до чужого брокера. Тобто співрозмовник не
 * бачив смайла НІКОЛИ, а в того, хто його поставив, той жив до першого ж
 * `__phantomMeshChanged`: стрічка перечитується з мосту цілком і заступає
 * місцевий масив.
 *
 * Тому тут ДВА апарати, кожен зі справжнім сокетом на локальній петлі, і жодної
 * підміни: справжні ключі Ed25519 та X25519, справжній конверт, справжній
 * підпис, справжня квитанція. Судиться не «кадр розібрався», а те, заради чого
 * все це: у ТОГО, кому поставили смайл, [PeerMark.foldAll] — той самий згортач,
 * яким міст малює поле `reactions`, — дає під його рядком автора й число.
 *
 * Останній випадок бʼє в той самий сокет СТАРИМ кадром — тим, що його шле
 * `broadcastP2PControl`, — щоб «не працювало» лишалось виміряним, а не згаданим.
 */
class PeerMarkReachTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = mutableListOf<PeerLink>()

    @After
    fun tearDown() {
        links.forEach { it.stop() }
        scope.cancel()
    }

    /** Марта ставить смайл, Кирило його бачить. Ключі справжні, реєстри взаємні. */
    private class Pairing {
        val martaSign: Ed25519.Keypair = Ed25519.generateKeypair()
        val kyryloSign: Ed25519.Keypair = Ed25519.generateKeypair()
        val martaDh: X25519.Keypair = dhKeypair()
        val kyryloDh: X25519.Keypair = dhKeypair()
        val martaId = "marta-fp"
        val kyryloId = "kyrylo-fp"

        val kyryloRoster = listOf(
            PeerIdentity(
                id = martaId,
                name = "Марта",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = b64(martaSign.publicKey),
                dhPubKey = b64(martaDh.publicKey),
            ),
        )

        val martaRoster = listOf(
            PeerIdentity(
                id = kyryloId,
                name = "Кирило",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = b64(kyryloSign.publicKey),
                dhPubKey = b64(kyryloDh.publicKey),
            ),
        )

        val channel: ByteArray = PeerChannel.derive(
            martaDh.privateKey, b64(kyryloDh.publicKey), martaId, kyryloId,
        )!!
    }

    /**
     * Один апарат: слухач на петлі, свій реєстр, свій журнал.
     *
     * [receive] — точний ланцюг прийому з `PeerMesh.accept`, до останнього
     * кроку: відчинити ключем пари, звірити підпис, звірити адресата. Квитанція
     * пишеться лише тоді, коли всі три пройшли, — саме вона й доводить, що кадр
     * справді на тому телефоні.
     *
     * Позначці окремого слухача тут немає й не треба: вона просто лягає в
     * журнал, як усе інше, а [reactions] згортає його на вимогу. Саме цим вона
     * дешевша за дзвінок — нової дороги вона не додає жодної.
     */
    private class Phone(
        val id: String,
        val sign: Ed25519.Keypair,
        val dh: X25519.Keypair,
        val roster: List<PeerIdentity>,
    ) {
        var port = 0

        /** Журнал апарата — усе, що відчинилось, у тому порядку, як приїхало. */
        val log = mutableListOf<PeerMessage>()

        fun receive(raw: String): String? {
            val env = PeerEnvelope.parse(raw) ?: return null
            val message = PeerEnvelope.open(raw) { fromId ->
                val peer = roster.firstOrNull { it.id == fromId } ?: return@open null
                PeerChannel.derive(dh.privateKey, peer.dhPubKey, id, peer.id)
            } ?: return null
            val sender = roster.firstOrNull { it.id == message.fromId } ?: return null
            val pub = runCatching { Base64.getDecoder().decode(sender.pubKey) }.getOrNull()
                ?: return null
            if (!PeerSealing.verify(message, pub, expectedFromId = sender.id)) return null
            if (message.toId != id) return null
            val key = PeerChannel.derive(dh.privateKey, sender.dhPubKey, id, sender.id)
                ?: return null
            log += message
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }

        /**
         * Рівно те, що міст кладе в поле `reactions`: той самий згортач по
         * тому самому журналу. Другого читача позначок немає й бути не може.
         */
        fun reactions(targetId: String): Map<String, List<String>> =
            PeerMark.foldAll(log, PeerMark.What.REACTION)[targetId].orEmpty()
    }

    private fun listen(phone: Phone): Phone = runBlocking {
        val link = PeerLink(
            port = 0,
            onMessage = { raw -> phone.receive(raw) },
            onBound = { phone.port = it },
        )
        links += link
        link.start(scope)
        repeat(50) { if (phone.port != 0) return@repeat else delay(20) }
        assertTrue("слухач ${phone.id} не піднявся", phone.port != 0)
        phone
    }

    /** Кадр від [from] до [to] тим самим запечатаним конвертом, що й слово. */
    private fun deliver(
        p: Pairing,
        from: Phone,
        to: Phone,
        id: String,
        body: String,
        atMs: Long = NOW,
    ): PeerLink.Outcome {
        val message = PeerSealing.seal(
            PeerMessage(id = id, fromId = from.id, toId = to.id, body = body, sentAtMs = atMs),
            from.sign.privateKey,
        )
        val sealed = PeerEnvelope.seal(message, p.channel)!!
        val receipt = PeerReceipt.of(
            p.channel, message.id, message.fromId, message.toId, sealed.nonceB64,
        )!!
        return runBlocking { PeerLink().send("127.0.0.1", to.port, receipt, sealed.frame) }
    }

    private fun marta(p: Pairing) = Phone(p.martaId, p.martaSign, p.martaDh, p.martaRoster)

    private fun kyrylo(p: Pairing) = Phone(p.kyryloId, p.kyryloSign, p.kyryloDh, p.kyryloRoster)

    /** Кадр, який склав би міст на дотик людини. */
    private fun mark(targetId: String, on: Boolean, value: String): String =
        PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, targetId, on, value))!!

    /**
     * НАСКРІЗЬ: смайл доїхав — під рядком зʼявилось число — знятий зник.
     *
     * Кожен крок тут відбувається на справжньому сокеті, і жоден не підмінений.
     * Саме ця послідовність не працювала ЖОДНОГО разу.
     */
    @Test
    fun `смайл доїжджає, стає числом під рядком, і знімається назад`() {
        val p = Pairing()
        val marta = listen(marta(p))
        val kyrylo = listen(kyrylo(p))

        // 0. Кирило сказав слово. Саме на нього Марта покладе позначку.
        val said = "line-7f31ac2b"
        assertTrue(
            "саме слово не доїхало — далі міряти нічого",
            deliver(p, kyrylo, marta, said, "де ти?") is PeerLink.Outcome.Acked,
        )
        assertEquals("під новим рядком уже щось намальовано", emptyMap<String, List<String>>(), marta.reactions(said))

        // 1. Марта ставить ❤️: кадр їде тим самим конвертом, що й слово.
        assertTrue(
            "смайл не доїхав — співрозмовник не побачив би його ніколи",
            deliver(p, marta, kyrylo, "mark-1", mark(said, on = true, value = "❤️")) is PeerLink.Outcome.Acked,
        )

        // 2. І ось воно — те, заради чого все: під СВОЇМ рядком Кирило бачить
        //    смайл, і бачить, ХТО його поставив.
        assertEquals(
            "кадр доїхав, але під рядком порожньо",
            mapOf("❤️" to listOf(p.martaId)),
            kyrylo.reactions(said),
        )

        // 3. Друга людина на той самий рядок — число росте, автори не зливаються.
        assertTrue(
            deliver(p, marta, kyrylo, "mark-2", mark(said, on = true, value = "🔥")) is PeerLink.Outcome.Acked,
        )
        assertEquals(
            mapOf("❤️" to listOf(p.martaId), "🔥" to listOf(p.martaId)),
            kyrylo.reactions(said),
        )

        // 4. Марта знімає ❤️ — і воно зникає в НЬОГО, а не лише в неї.
        assertTrue(
            "зняття не доїхало — смайл лишився б у співрозмовника назавжди",
            deliver(p, marta, kyrylo, "mark-3", mark(said, on = false, value = "❤️")) is PeerLink.Outcome.Acked,
        )
        assertEquals(
            "знятий смайл лишився під рядком",
            mapOf("🔥" to listOf(p.martaId)),
            kyrylo.reactions(said),
        )
    }

    /**
     * Черга переставила кадри — обидва апарати однаково.
     *
     * Позначку можна поставити, зняти й поставити знову за секунду, а кадри
     * їдуть чергою й приїжджають не обовʼязково в тому порядку, у якому їх
     * поклали. Якби згортач брав порядок ПРИЙОМУ, той самий журнал давав би два
     * різні відповіді на двох апаратах — і смайл то зʼявлявся б, то зникав.
     *
     * Тут кадр «зняв» доїжджає ПЕРШИМ, а годинник автора каже, що він пізніший.
     */
    @Test
    fun `порядок бере годинник автора, а не черга`() {
        val p = Pairing()
        val kyrylo = listen(kyrylo(p))
        val from = marta(p)
        val said = "line-out-of-order"

        // Пізніший за годинником — але приїхав першим.
        assertTrue(
            deliver(p, from, kyrylo, "late", mark(said, on = false, value = "👍"), atMs = NOW + 500) is PeerLink.Outcome.Acked,
        )
        assertTrue(
            deliver(p, from, kyrylo, "early", mark(said, on = true, value = "👍"), atMs = NOW) is PeerLink.Outcome.Acked,
        )

        assertEquals("обидва кадри не доїхали", 2, kyrylo.log.size)
        assertEquals(
            "згортач узяв порядок черги — смайл воскрес зі знятого",
            emptyMap<String, List<String>>(),
            kyrylo.reactions(said),
        )
    }

    /**
     * Смайл не стає рядком розмови, не гримить у шторці й не обіцяє листа.
     *
     * Три різні неправди про одну дію: окремий рядок «❤️» у стрічці — це лист,
     * якого ніхто не писав; плашка в шторці — «Марта: PH-REA:line…» сирим
     * дротом; «1 чекає» у смузі — обіцянка ненадісланого речення. Судиться на
     * тому, що СПРАВДІ приїхало, а не на літералі.
     */
    @Test
    fun `те, що приїхало смайлом, не стає рядком розмови`() {
        val p = Pairing()
        val kyrylo = listen(kyrylo(p))
        val from = marta(p)
        val said = "line-quiet"

        listOf(true, false).forEachIndexed { i, on ->
            assertTrue(
                deliver(p, from, kyrylo, "q-$i", mark(said, on, "❤️")) is PeerLink.Outcome.Acked,
            )
        }

        assertEquals("не всі кадри позначки доїхали", 2, kyrylo.log.size)
        kyrylo.log.forEach {
            assertEquals(
                "кадр доїхав, але не розібрався позначкою",
                PeerFrames.Kind.REACTION,
                PeerFrames.kindOf(it.body),
            )
            assertFalse("смайл став рядком розмови", PeerOutbox.isSpoken(it))
            assertNull(
                "смайл загримів у шторці сирим дротом",
                PeerAnnounce.notice(it, visibleChat = null, visibleGroup = null, selfId = p.kyryloId),
            )
        }
        assertEquals("смайл порахувався листом, який чекає", 0, PeerOutbox.waiting(kyrylo.log))
    }

    /**
     * Стара дорога, заміряна.
     *
     * Це не історична довідка: доки цей випадок зелений, «смайл не доходив»
     * лишається ВИМІРЯНИМ фактом про транспорт, а не здогадом. Саме таке тіло
     * кладе `messengerNetworkEngine.broadcastP2PControl` у браузерну сітку —
     * і рівно воно ніколи не мало куди приїхати на телефоні: [PeerLink] читає
     * рядок `PHANTOM/1 <verb> <len>`, а все інше мовчки закриває.
     */
    @Test
    fun `кадр p2p-reaction не отримує від слухача телефона відповіді`() = runBlocking {
        var port = 0
        val phone = PeerLink(port = 0, onMessage = { null }, onBound = { port = it })
        links += phone
        phone.start(scope)
        repeat(50) { if (port != 0) return@repeat else delay(20) }

        val control = """{"type":"p2p:reaction","chatId":"c1","messageId":"m1",""" +
            """"emoji":"❤️","action":"add","userId":"user_me"}"""
        val answer = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            out.write((control + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching {
                BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            }.getOrNull()
        }
        assertNull(
            "якби слухач на це відповідав, стара дорога не була б зламана — " +
                "і цей сторож перевіряв би не те",
            answer,
        )
    }

    /**
     * Стан на картці зустрічі (`PH-CST:`, збірка 18) їде ТІЄЮ САМОЮ дорогою:
     * Марта кладе «іду» і «я їв борщ» на картку, яку надіслав Кирило, — і в
     * НЬОГО той самий згортач дає їх під рядком картки. Сокет, конверт, підпис
     * і квитанція справжні; рядком розмови кадр не стає.
     */
    @Test
    fun `стан на картці зустрічі доїжджає до другого апарата й згортається`() {
        val p = Pairing()
        val marta = listen(marta(p))
        val kyrylo = listen(kyrylo(p))
        val cardState = PeerMark.What.entries.single { it.mark == "PH-CST:" }
        fun state(value: String, on: Boolean = true) =
            PeerMark.encode(PeerMark.Mark(cardState, "card-meetup-1", on, value))!!

        assertTrue(deliver(p, kyrylo, marta, "card-meetup-1", "{}") is PeerLink.Outcome.Acked)
        listOf(
            Triple("cst-1", state("rsvp:yes"), NOW + 1),
            Triple("cst-2", state("dish:borshch"), NOW + 2),
            Triple("cst-3", state("eta:29808628"), NOW + 3),
        ).forEach { (id, body, at) ->
            assertTrue(
                "стан картки не доїхав — Кирило не побачив би, що Марта йде",
                deliver(p, marta, kyrylo, id, body, atMs = at) is PeerLink.Outcome.Acked,
            )
        }
        assertEquals(
            mapOf(
                "rsvp:yes" to listOf(p.martaId),
                "dish:borshch" to listOf(p.martaId),
                "eta:29808628" to listOf(p.martaId),
            ),
            PeerMark.foldAll(kyrylo.log, cardState)["card-meetup-1"],
        )
        kyrylo.log.forEach {
            assertFalse("стан картки став рядком розмови", PeerOutbox.isSpoken(it))
            assertNull(
                "стан картки загримів у шторці",
                PeerAnnounce.notice(it, visibleChat = null, visibleGroup = null, selfId = p.kyryloId),
            )
        }
    }

    /**
     * Кадр НОВІШОЇ збірки, якого цей апарат не знає, — тією самою дорогою.
     *
     * Так збірка 15 отримує `PH-CST:` від збірки 18: сокет, конверт, підпис і
     * квитанція справжні, а тег незнайомий. Старий реєстр клав його бульбашкою
     * з сирим дротом, рахував непрочитаним і дзвонив у шторці. Тут — від
     * збірки 18 і далі — незнайомий тег мовчить і в стрічку не йде.
     */
    @Test
    fun `кадр незнайомої новішої збірки доїжджає, але мовчить`() {
        val p = Pairing()
        val marta = listen(marta(p))
        val kyrylo = listen(kyrylo(p))
        val tomorrow = "PH-QQQ:card-meetup-1;+;" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("rsvp:yes".toByteArray())

        assertTrue(
            "кадр не доїхав — тоді й мовчання нічого не доводить",
            deliver(p, marta, kyrylo, "future-1", tomorrow) is PeerLink.Outcome.Acked,
        )
        val arrived = kyrylo.log.single { it.id == "future-1" }
        assertEquals(tomorrow, arrived.body)
        assertFalse("незнайомий тег став рядком розмови", PeerOutbox.isSpoken(arrived))
        assertEquals(
            "незнайомий тег рахується непрочитаним",
            0,
            PeerOutbox.unread(kyrylo.log, peerId = p.martaId, readUpToMs = 0L),
        )
        assertNull(
            "незнайомий тег загримів у шторці",
            PeerAnnounce.notice(arrived, visibleChat = null, visibleGroup = null, selfId = p.kyryloId),
        )
    }

    private companion object {
        const val NOW = 1_788_517_702_898L
    }
}
