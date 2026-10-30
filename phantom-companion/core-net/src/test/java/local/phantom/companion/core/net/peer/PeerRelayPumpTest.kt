package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
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
 * Порядок, а не окремі рухи.
 *
 * Кожен клас нижче вміє свою дію й перевіряється сам. Втрачається лист не в
 * діях, а між ними: коли копію на сервері спалили раніше, ніж лист ліг на диск.
 * Це не падає, не логується й не має жодного симптому — тиждень по тому людина
 * просто не отримала слова. Тут перевіряється саме шов.
 */
class PeerRelayPumpTest {

    /** Сервер, який уміє рівно те, що вміє справжній, і записує, коли його чіпали. */
    private class FakeRelay(val log: MutableList<String>, val clock: () -> Long = { NOW }) : PeerRelayTransport {
        val store = LinkedHashMap<String, Pair<String, ByteArray>>()
        var reachable = true

        /**
         * Коли народилась ЦЯ база сховка. Справжній сервер тримає це число В
         * САМІЙ базі й віддає його на кожен забір (`store_since_ms`), а строк
         * на вкиданні називає своїм годинником (`held_until_ms`) — щоб те й те
         * порівнювалось однією стрілкою. Мовчати про це тут означало б перевіряти
         * телефон проти сервера, якого немає.
         */
        var sinceMs = NOW - 86_400_000L
            private set

        /** Старий сховок, який ще не вміє казати, коли народився. */
        var speaksSince = true
        private var seq = 0

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            if (!reachable) return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.NO_CONTACT)
            if (blob.size !in PeerRelay.BLOB_SIZES) {
                return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
            }
            log += "drop"
            store["blob-${seq++}"] = tag to blob
            // Строк сервера від СЕРВЕРНОГО «зараз», яке ніколи не раніше за
            // народження його ж бази — інакше фейк дозволяв би вкинути лист у
            // сховок, якого ще не існувало.
            return PeerRelayTransport.Dropped(
                PeerRelayTransport.Drop.HELD,
                if (speaksSince) maxOf(clock(), sinceMs) + PeerRelay.TTL_MS else null,
            )
        }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
            if (!reachable) return null
            if (tags.size != PeerRelay.FETCH_TAGS) return null
            log += "fetch"
            val asked = tags.toSet()
            return PeerRelayTransport.Pickup(
                store.filter { it.value.first in asked }
                    .map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) },
                if (speaksSince) sinceMs else null,
            )
        }

        override suspend fun burn(ids: List<String>): Boolean {
            log += "burn"
            ids.forEach { store.remove(it) }
            return true
        }

        /**
         * Викладення. До 07.09 база сховку лежала в `/tmp`, тобто в шарі
         * образу, і кожен `fly deploy` робив рівно це: конверти зникали, а
         * сервер після цього виглядав як цілком справний порожній сховок.
         */
        fun wipe() {
            store.clear()
            // База народилась заново — і саме це сервер каже вголос наступному,
            // хто прийде по свої листи.
            sinceMs = NOW + 1L
        }
    }

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    /** Реєстр і журнал цього телефона, без диска й без Android. */
    private class Phone2World(
        val me: Phone,
        val them: Phone,
        val log: MutableList<String>,
    ) : PeerRelayPump.World {
        var messages: List<PeerMessage> = emptyList()

        /** Що робить [persist], коли диск відмовив. */
        var writable = true

        override fun selfId(): String = me.id

        override fun decoySecret(): ByteArray = PeerRelay.decoySecret(me.dh.privateKey)!!

        override fun contacts(): List<String> = listOf(them.id)

        override fun pairKey(peerId: String): ByteArray? =
            if (peerId == them.id) {
                PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id)
            } else {
                null
            }

        override fun signingKey(peerId: String): ByteArray? =
            if (peerId == them.id) them.signing.publicKey else null

        override fun outbox(): List<PeerMessage> = messages

        override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) {
            messages = transform(messages)
        }

        override fun persist(message: PeerMessage): Boolean {
            if (!writable) return false
            log += "persist:${message.id}"
            messages = PeerOutbox.enqueue(messages, message)
            return true
        }
    }

    private fun letter(from: Phone, to: Phone, id: String, body: String): PeerMessage =
        PeerSealing.seal(
            PeerMessage(id = id, fromId = from.id, toId = to.id, body = body, sentAtMs = NOW),
            from.signing.privateKey,
        )

    /** Два телефони й один сервер між ними. */
    private class Pair2(val log: MutableList<String> = mutableListOf()) {
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val worldA = Phone2World(a, b, log)
        val worldB = Phone2World(b, a, log)
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(relay) { NOW }) { NOW }
        val pumpB = PeerRelayPump(worldB, PeerRelayCourier(relay) { NOW }) { NOW }
    }

    /**
     * Двигун, який ніколи не відповідає. Потрібен лише там, де ворота ТАКИ
     * складають клієнта: у решті випадків вони повертають null раніше, і
     * справжній двигун там не має зʼявитись навіть у тесті.
     */
    private fun stubHttp() = HttpClient(
        MockEngine { respond("", HttpStatusCode.ServiceUnavailable) },
    )

    // ── прапорець ───────────────────────────────────────────────────────────

    @Test
    fun `out of the box this phone makes no relay traffic at all`() {
        assertFalse(PeerRelayGate.DEFAULT_ENABLED)
        val settings = PeerRelayGate.Settings()
        assertEquals(PeerRelayGate.Verdict.OFF, PeerRelayGate.verdict(settings))
        assertNull(PeerRelayGate.open(settings, contacts = 5))
    }

    @Test
    fun `switched on with nowhere to go is a state with a name, not a crash`() {
        val settings = PeerRelayGate.Settings(enabled = true)
        assertEquals(PeerRelayGate.Verdict.NO_ADDRESS, PeerRelayGate.verdict(settings))
        assertNull(PeerRelayGate.open(settings, contacts = 5))
    }

    @Test
    fun `a relay reachable in cleartext is refused, mailbox names ride in the request`() {
        val settings = PeerRelayGate.Settings(enabled = true, baseUrl = "http://relay.example")
        assertEquals(PeerRelayGate.Verdict.PLAINTEXT, PeerRelayGate.verdict(settings))
        assertNull(PeerRelayGate.open(settings, contacts = 5))
    }

    @Test
    fun `a phone with an empty roster never opens the relay, however it is configured`() {
        val settings = PeerRelayGate.Settings(enabled = true, baseUrl = "https://relay.example")
        assertEquals(PeerRelayGate.Verdict.READY, PeerRelayGate.verdict(settings))
        assertNull(PeerRelayGate.open(settings, contacts = 0))
        assertNotNull(PeerRelayGate.open(settings, contacts = 1, http = stubHttp()))
    }

    // ── порядок ─────────────────────────────────────────────────────────────

    @Test
    fun `a letter that could not be written down is never burned off the server`() = runBlocking {
        val pair = Pair2()
        pair.worldA.messages = PeerOutbox.enqueue(
            emptyList(),
            letter(pair.a, pair.b, "m-1", "виходь на звʼязок"),
        )
        assertTrue(pair.pumpA.handOff(pair.worldA.messages.first()))

        // Диск на телефоні Б відмовив.
        pair.worldB.writable = false
        val report = pair.pumpB.poll()

        assertEquals(0, report.arrived)
        assertEquals(0, report.burned)
        assertEquals(1, pair.relay.store.size)
        assertFalse(pair.log.contains("burn"))
    }

    @Test
    fun `the copy dies only after the letter is on disk, never before`() = runBlocking {
        val pair = Pair2()
        pair.worldA.messages = PeerOutbox.enqueue(
            emptyList(),
            letter(pair.a, pair.b, "m-1", "координати"),
        )
        pair.pumpA.handOff(pair.worldA.messages.first())
        pair.log.clear()

        pair.pumpB.poll()

        val persisted = pair.log.indexOf("persist:m-1")
        val burned = pair.log.indexOf("burn")
        assertTrue(persisted >= 0)
        assertTrue(burned >= 0)
        assertTrue(persisted < burned)
    }

    @Test
    fun `a forged letter is not written, not acknowledged and not burned`() = runBlocking {
        val pair = Pair2()
        // Підпис чужого телефона під тими самими словами.
        val impostor = Phone("phone-c")
        val forged = PeerSealing.seal(
            PeerMessage(
                id = "m-1",
                fromId = pair.a.id,
                toId = pair.b.id,
                body = "рушаємо",
                sentAtMs = NOW,
            ),
            impostor.signing.privateKey,
        )
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), forged)
        assertTrue(pair.pumpA.handOff(forged))
        pair.log.clear()

        val report = pair.pumpB.poll()

        assertEquals(0, report.arrived)
        assertTrue(pair.worldB.messages.isEmpty())
        assertFalse(pair.log.any { it.startsWith("persist") })
        assertFalse(pair.log.contains("burn"))
        assertEquals(1, pair.relay.store.size)
    }

    // ── повний оберт ────────────────────────────────────────────────────────

    @Test
    fun `a sleeping phone gets the letter and the sender learns it landed`() = runBlocking {
        val pair = Pair2()
        pair.worldA.messages = PeerOutbox.enqueue(
            emptyList(),
            letter(pair.a, pair.b, "m-1", "виходь на звʼязок"),
        )

        assertTrue(pair.pumpA.handOff(pair.worldA.messages.first()))
        val sent = pair.worldA.messages.first()
        assertEquals(PeerMessage.State.HELD, sent.state)
        assertEquals(PeerMessage.Sealing.PAIR_KEY, sent.sealing)
        // З цієї миті лист не коштує радіо: він вийшов із черги на спроби.
        assertTrue(PeerOutbox.due(pair.worldA.messages, NOW + 10_000_000L).isEmpty())
        assertEquals(1, PeerOutbox.waiting(pair.worldA.messages))

        val toB = pair.pumpB.poll()
        assertEquals(1, toB.arrived)
        assertEquals("виходь на звʼязок", pair.worldB.messages.first().body)

        val toA = pair.pumpA.poll()
        assertEquals(1, toA.delivered)
        assertEquals(PeerMessage.State.DELIVERED, pair.worldA.messages.first().state)
        assertEquals(0, PeerOutbox.waiting(pair.worldA.messages))
        // Нічого не лишилось: ані листа, ані квитанції.
        assertTrue(pair.relay.store.isEmpty())
    }

    @Test
    fun `a receipt written for another copy of the same letter is not believed`() = runBlocking {
        val pair = Pair2()
        pair.worldA.messages = PeerOutbox.enqueue(
            emptyList(),
            letter(pair.a, pair.b, "m-1", "слово"),
        )
        pair.pumpA.handOff(pair.worldA.messages.first())
        pair.pumpB.poll()

        // Автор перезаписав свій облік іншим nonce — тим, якого сервер не ніс.
        pair.worldA.messages = pair.worldA.messages.map { it.copy(heldNonce = "aW5zaGUtbm9uY2U") }
        val report = pair.pumpA.poll()

        assertEquals(0, report.delivered)
        assertEquals(PeerMessage.State.HELD, pair.worldA.messages.first().state)
    }

    @Test
    fun `a relay that is simply not there costs the letter nothing`() = runBlocking {
        val pair = Pair2()
        pair.relay.reachable = false
        val message = letter(pair.a, pair.b, "m-1", "слово")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), message)

        assertFalse(pair.pumpA.handOff(message))

        val back = pair.worldA.messages.first()
        assertEquals(PeerMessage.State.QUEUED, back.state)
        assertEquals(0, back.attempts)
        assertEquals(1, PeerOutbox.due(pair.worldA.messages, NOW).size)
    }

    @Test
    fun `after seven days without a receipt the letter returns to the queue`() = runBlocking {
        val pair = Pair2()
        val message = letter(pair.a, pair.b, "m-1", "слово")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), message)
        pair.pumpA.handOff(message)
        assertEquals(PeerMessage.State.HELD, pair.worldA.messages.first().state)

        val late = PeerRelayPump(
            pair.worldA,
            PeerRelayCourier(pair.relay) { NOW + PeerRelay.TTL_MS + 1L },
        ) { NOW + PeerRelay.TTL_MS + 1L }
        late.sweepExpired()

        val back = pair.worldA.messages.first()
        assertEquals(PeerMessage.State.QUEUED, back.state)
        assertEquals(0, back.attempts)
        assertEquals("", back.heldNonce)
    }

    /**
     * Те, що прийшло, — не борг цього телефона. [PeerOutbox.enqueue] раніше
     * перетирало стан на QUEUED, тож кожне прочитане слово рахувалось у
     * «чекає» на постійному сповіщенні, а [PeerOutbox.prune] не мав права
     * забути його ніколи — журнал ріс без стелі.
     */
    @Test
    fun `a letter that arrived is never counted as one this phone still owes`() = runBlocking {
        val pair = Pair2()
        pair.worldA.messages = PeerOutbox.enqueue(
            emptyList(),
            letter(pair.a, pair.b, "m-1", "слово"),
        )
        pair.pumpA.handOff(pair.worldA.messages.first())
        pair.pumpB.poll()

        assertEquals(PeerMessage.State.DELIVERED, pair.worldB.messages.single().state)
        assertEquals(0, PeerOutbox.waiting(pair.worldB.messages))
    }

    // ── сховок утратив копію ────────────────────────────────────────────────

    /**
     * ВАДА 07.09. Сховок тримав базу в шарі образу, тож кожне викладення
     * витирало ще не забрані конверти. Відправник цього не бачив: лист лишався
     * HELD, з черги він уже вийшов, і повертав його в неї лише
     * [PeerRelayPump.sweepExpired] — за `heldUntilMs`, тобто через СІМ ДІБ.
     * Усі сім діб людина бачила «У СХОВКУ».
     *
     * Корінь був не в сервері, а в шві: [PeerRelayCourier.collect] складав
     * «сервер відповів, скриньки порожні» і «до сервера не достукались» в один
     * порожній список, тож насос не мав чим відрізнити витирання від мовчання
     * і мусив мовчати сам.
     */
    @Test
    fun `a relay that lost the letter does not keep the sender waiting a week`() = runBlocking {
        val pair = Pair2()
        val message = letter(pair.a, pair.b, "m-1", "слово")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), message)
        pair.pumpA.handOff(message)
        assertEquals(PeerMessage.State.HELD, pair.worldA.messages.first().state)

        // Викладення: конвертів немає, база народилась заново — і сервер це
        // каже вголос, інакше порожня видача означала б обидва випадки одразу.
        pair.relay.wipe()

        pair.pumpA.poll()

        val back = pair.worldA.messages.first()
        assertEquals(PeerMessage.State.QUEUED, back.state)
        assertEquals(0, back.attempts)
        assertEquals("", back.heldNonce)
        // І він справді знову в черзі, а не просто перефарбований.
        assertEquals(1, PeerOutbox.due(pair.worldA.messages, NOW).size)
    }

    /**
     * Дзеркало до попереднього: мовчання сховка НЕ означає, що він щось
     * утратив. Телефон у метро не має вертати в чергу все, що лежить у сховку,
     * — це подвоїло б кожен лист на кожному провалі звʼязку.
     */
    @Test
    fun `a relay that simply does not answer never re-queues what it holds`() = runBlocking {
        val pair = Pair2()
        val message = letter(pair.a, pair.b, "m-1", "слово")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), message)
        pair.pumpA.handOff(message)

        pair.relay.reachable = false
        pair.pumpA.poll()

        assertEquals(PeerMessage.State.HELD, pair.worldA.messages.first().state)
    }

    /**
     * Найдорожча помилка на цьому шві — вернути лист за ПОРОЖНЮ видачу. Свого
     * листа відправник у своїй видачі не бачить НІКОЛИ: той лежить у скриньці
     * адресата, яку опитує адресат. Тобто «порожньо» — нормальний стан кожного
     * заходу, доки адресат спить, і вертати за нього означало б слати кожен лист
     * двічі на кожному такті.
     */
    @Test
    fun `an empty answer from a stash older than the letter changes nothing`() = runBlocking {
        val pair = Pair2()
        val message = letter(pair.a, pair.b, "m-1", "слово")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), message)
        pair.pumpA.handOff(message)

        // Сховок живий і відповідає; народився він ДО того, як лист у нього ліг.
        pair.pumpA.poll()

        assertEquals(PeerMessage.State.HELD, pair.worldA.messages.first().state)
        assertTrue(PeerOutbox.due(pair.worldA.messages, NOW).isEmpty())
    }

    /**
     * Сховок, який про своє народження не каже, — це кожен сервер до 07.09.
     * Телефон від цього не ламається й нічого не вигадує: лист лишається HELD, і
     * повертає його лише семиденний строк, тобто рівно те, що було раніше.
     */
    @Test
    fun `a stash that never states its birth leaves the seven-day rule alone`() = runBlocking {
        val pair = Pair2()
        pair.relay.speaksSince = false
        val message = letter(pair.a, pair.b, "m-1", "слово")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), message)
        pair.pumpA.handOff(message)

        pair.relay.wipe()
        pair.pumpA.poll()

        assertEquals(PeerMessage.State.HELD, pair.worldA.messages.first().state)
    }

    /** Витирання забирає те, що лежало ДО нього, і рівно його. */
    @Test
    fun `a letter dropped into the new stash is not swept out with the old one`() = runBlocking {
        val pair = Pair2()
        val lost = letter(pair.a, pair.b, "m-1", "перше")
        pair.worldA.messages = PeerOutbox.enqueue(emptyList(), lost)
        pair.pumpA.handOff(lost)
        pair.relay.wipe()

        val fresh = letter(pair.a, pair.b, "m-2", "друге")
        pair.worldA.messages = PeerOutbox.enqueue(pair.worldA.messages, fresh)
        pair.pumpA.handOff(fresh)

        pair.pumpA.poll()

        val byId = pair.worldA.messages.associateBy { it.id }
        assertEquals(PeerMessage.State.QUEUED, byId.getValue("m-1").state)
        assertEquals(PeerMessage.State.HELD, byId.getValue("m-2").state)
    }

    @Test
    fun `a letter the direct wire accepts always fits the relay's largest bucket`() {
        // Інакше існував би лист, який телефон уміє віддати з рук у руки й не
        // вміє покласти в сховок — і симптомом було б лише «не дійшло».
        assertTrue(PeerRelay.BUCKETS.last() >= PeerWire.MAX_BODY_BYTES + 4)
    }

    // ── двері ───────────────────────────────────────────────────────────────

    /** Телефон, у якого є лише власні двері: реєстр може бути порожнім. */
    private class DoorWorld(
        val me: Phone,
        val log: MutableList<String>,
        var friends: List<Phone> = emptyList(),
    ) : PeerRelayPump.World {
        var messages: List<PeerMessage> = emptyList()
        val doors = mutableListOf<String>()

        override fun selfId(): String = me.id
        override fun decoySecret(): ByteArray = PeerRelay.decoySecret(me.dh.privateKey)!!
        override fun contacts(): List<String> = friends.map { it.id }
        override fun pairKey(peerId: String): ByteArray? =
            friends.firstOrNull { it.id == peerId }?.let { PeerChannel.derive(me.dh.privateKey, it.dhPubB64, me.id, it.id) }
        override fun signingKey(peerId: String): ByteArray? = friends.firstOrNull { it.id == peerId }?.signing?.publicKey
        override fun outbox(): List<PeerMessage> = messages
        override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) {
            messages = transform(messages)
        }
        override fun persist(message: PeerMessage): Boolean {
            log += "persist:${message.id}"
            messages = PeerOutbox.enqueue(messages, message)
            return true
        }
        override fun ownDoor(): ByteArray? = PeerRelay.doorKey(me.dh.publicKey, me.id)
        var doorKeyLoaded = true
        override fun openDoor(blob: ByteArray, tag: String): String? =
            if (doorKeyLoaded) PeerRelay.openDoor(blob, me.dh.privateKey, tag).orEmpty() else null
        override suspend fun onDoor(raw: String): Boolean {
            log += "door"
            doors += raw
            return true
        }
    }

    @Test
    fun `zero contacts still fetches own 3 door tags, total == FETCH_TAGS`() = runBlocking {
        val log = mutableListOf<String>()
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val pump = PeerRelayPump(DoorWorld(b, log), PeerRelayCourier(relay) { NOW }) { NOW }

        val prepared = pump.prepare()

        assertNotNull("порожній реєстр, але двері свої", prepared)
        val tags = prepared!!.plan.tags
        assertEquals(PeerRelay.FETCH_TAGS, tags.size)
        assertEquals(PeerRelay.FETCH_TAGS, tags.toSet().size)
        val today = PeerRelay.epochOf(NOW)
        (today - 1..today + 1).forEach { e ->
            assertTrue(PeerRelay.doorTag(b.dhPubB64, b.id, e) in tags)
        }
        assertEquals(3, prepared.plan.slots.values.count { it.kind == PeerRelay.Kind.INTRO })
        assertTrue(prepared.complete)
        pump.poll()
        assertTrue("забір пішов", "fetch" in log)
    }

    @Test
    fun `ten contacts and the door still fit one fetch, eleven do not`() {
        val log = mutableListOf<String>()
        val b = Phone("phone-b")
        val world = DoorWorld(b, log, friends = List(10) { Phone("f-$it") })
        val pump = PeerRelayPump(world, PeerRelayCourier(FakeRelay(log)) { NOW }) { NOW }
        val ten = pump.prepare()!!
        assertTrue(ten.complete)
        assertEquals(PeerRelay.FETCH_TAGS, ten.plan.tags.toSet().size)

        world.friends = List(11) { Phone("f-$it") }
        val eleven = pump.prepare()!!
        assertFalse(eleven.complete)
        assertEquals(PeerRelay.FETCH_TAGS, eleven.plan.tags.toSet().size)
        val today = PeerRelay.epochOf(NOW)
        (today - 1..today + 1).forEach { e ->
            assertTrue("двері не випадають із вікна", PeerRelay.doorTag(b.dhPubB64, b.id, e) in eleven.plan.tags)
        }
    }

    @Test
    fun `INTRO letter goes to onDoor, not to persist`() = runBlocking {
        val log = mutableListOf<String>()
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val courierA = PeerRelayCourier(relay) { NOW }
        val worldB = DoorWorld(b, log)
        val pumpB = PeerRelayPump(worldB, PeerRelayCourier(relay) { NOW }) { NOW }

        assertTrue(courierA.dropDoor(b.dhPubB64, b.id, "INTRO-FRAME") is PeerRelayCourier.Handoff.Held)
        log.clear()
        pumpB.poll()

        assertEquals(listOf("INTRO-FRAME"), worldB.doors)
        assertFalse(log.any { it.startsWith("persist") })
        assertTrue("копію спалено після дверей", relay.store.isEmpty())
        assertTrue(log.indexOf("door") < log.indexOf("burn"))
    }

    @Test
    fun `a door letter the phone could not take yet stays on the server`() = runBlocking {
        val log = mutableListOf<String>()
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val worldB = object : PeerRelayPump.World by DoorWorld(b, log) {
            override suspend fun onDoor(raw: String): Boolean = false
        }
        PeerRelayCourier(relay) { NOW }.dropDoor(b.dhPubB64, b.id, "INTRO-FRAME")
        PeerRelayPump(worldB, PeerRelayCourier(relay) { NOW }) { NOW }.poll()
        assertEquals(1, relay.store.size)
    }

    @Test
    fun `HELD from D-2 is re-dropped into D once, not twice the same day`() = runBlocking {
        val log = mutableListOf<String>()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val day = PeerRelay.EPOCH_MS
        var clock = NOW - 2 * day
        val worldA = DoorWorld(a, log, friends = listOf(b))
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(relay) { clock }) { clock }
        val note = letter(a, b, "m-1", "ти де?")
        worldA.messages = PeerOutbox.enqueue(emptyList(), note)
        assertTrue(pumpA.handOff(note))
        // Сервер назвав строк від дня вкидання (D−2).
        worldA.messages = worldA.messages.map { it.copy(heldUntilMs = clock + PeerRelay.TTL_MS) }

        clock = NOW
        log.clear()
        assertEquals(1, pumpA.redropStale())
        assertEquals(0, pumpA.redropStale())
        assertEquals(1, log.count { it == "drop" })
        val held = worldA.messages.single()
        assertEquals(PeerMessage.State.HELD, held.state)
        assertEquals(PeerRelay.epochOf(NOW), PeerRelay.epochOf(held.heldUntilMs - PeerRelay.TTL_MS))

        val worldB = DoorWorld(b, log, friends = listOf(a))
        val pumpB = PeerRelayPump(worldB, PeerRelayCourier(relay) { NOW }) { NOW }
        assertEquals(1, pumpB.poll().arrived)
        assertEquals("ти де?", worldB.messages.single().body)
    }

    @Test
    fun `yesterday's copy is still asked for and is not re-dropped`() = runBlocking {
        val log = mutableListOf<String>()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val worldA = DoorWorld(a, log, friends = listOf(b))
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(FakeRelay(log)) { NOW }) { NOW }
        val note = letter(a, b, "m-1", "ти де?").copy(
            state = PeerMessage.State.HELD,
            heldNonce = "n",
            heldUntilMs = NOW - PeerRelay.EPOCH_MS + PeerRelay.TTL_MS,
        )
        worldA.messages = listOf(note)
        assertEquals(0, pumpA.redropStale())
    }

    @Test
    fun `a door blob that does not open is burned at once and never reaches the door`() = runBlocking {
        val log = mutableListOf<String>()
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val worldB = DoorWorld(b, log)
        val tag = PeerRelay.doorTag(b.dhPubB64, b.id, PeerRelay.epochOf(NOW))!!
        relay.drop(tag, ByteArray(PeerRelay.BUCKETS.last() + 28) { it.toByte() })
        PeerRelayCourier(relay) { NOW }.dropDoor(b.dhPubB64, b.id, "INTRO-FRAME")

        PeerRelayPump(worldB, PeerRelayCourier(relay) { NOW }) { NOW }.poll()

        assertEquals("сміття не важить нічого в наступних заборах", 0, relay.store.size)
        assertEquals(listOf("INTRO-FRAME"), worldB.doors)
    }

    @Test
    fun `a door blob waits on the server while the phone has no key to try it with`() = runBlocking {
        val log = mutableListOf<String>()
        val b = Phone("phone-b")
        val relay = FakeRelay(log)
        val worldB = DoorWorld(b, log).apply { doorKeyLoaded = false }
        PeerRelayCourier(relay) { NOW }.dropDoor(b.dhPubB64, b.id, "INTRO-FRAME")

        PeerRelayPump(worldB, PeerRelayCourier(relay) { NOW }) { NOW }.poll()

        assertEquals(1, relay.store.size)
        assertTrue(worldB.doors.isEmpty())
    }

    @Test
    fun `re-dropping stops with the letter's life, so the week still runs out`() = runBlocking {
        val log = mutableListOf<String>()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val day = PeerRelay.EPOCH_MS
        var clock = NOW
        val relay = FakeRelay(log) { clock }
        val worldA = DoorWorld(a, log, friends = listOf(b))
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(relay) { clock }) { clock }
        val note = PeerSealing.seal(
            PeerMessage(id = "m-1", fromId = a.id, toId = b.id, body = "ти де?", sentAtMs = clock),
            a.signing.privateKey,
        )
        worldA.messages = PeerOutbox.enqueue(emptyList(), note)
        assertTrue(pumpA.handOff(note))

        repeat(16) { d ->
            clock = NOW + (d + 1) * day
            pumpA.sweepExpired()
            pumpA.redropStale()
        }

        assertTrue("копій не більше, ніж днів життя листа", log.count { it == "drop" } <= 4)
        assertTrue(
            "лист, якого так і не забрали, покидає «у сховку»",
            worldA.messages.single().state != PeerMessage.State.HELD,
        )
    }
}
