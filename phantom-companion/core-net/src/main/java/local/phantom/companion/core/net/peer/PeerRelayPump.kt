package local.phantom.companion.core.net.peer

/**
 * Один захід до сховка, від початку до кінця.
 *
 * [PeerRelayCourier] уміє окремі рухи — вкинути, забрати, спалити. Порядок між
 * ними тут, і він тут навмисне: правило «спалити копію лише після того, як лист
 * лежить на диску» не має жодного видимого симптому, коли його порушено. Лист
 * просто зникає, у тестах зелено, а людина дізнається про це через тиждень.
 * Тому [World.persist] повертає відповідь, і саме вона — умова спалення, а не
 * коментар над ним.
 *
 * Двері всередину ті самі, що й з дроту: [PeerEnvelope.open] і
 * [PeerSealing.verify]. Сховок не має власного входу — інакше він був би не
 * запасним шляхом, а дірою поруч із дверима.
 */
class PeerRelayPump(
    private val world: World,
    private val courier: PeerRelayCourier,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Все, що насосу треба знати про цей телефон. Інтерфейс, а не посилання на
     * реєстр і журнал, щоб увесь порядок перевірявся без сокета й без Android.
     */
    interface World {
        fun selfId(): String?

        /** Ідентифікатори з реєстру — лише ті, кому справді є що писати. */
        fun contacts(): List<String>

        /** Довічний ключ пари. Null — рядок без ключа, з ним не робиться нічого. */
        fun pairKey(peerId: String): ByteArray?

        /** Їхній Ed25519. Без нього підпис нема чим перевірити, тож лист не наш. */
        fun signingKey(peerId: String): ByteArray?

        fun outbox(): List<PeerMessage>

        fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>)

        /** true ЛИШЕ тоді, коли лист уже записано. Від цієї відповіді залежить копія. */
        fun persist(message: PeerMessage): Boolean

        /** [PeerRelay.decoySecret] цього телефона. null — сховок не питаємо зовсім. */
        fun decoySecret(): ByteArray?

        /**
         * Конверт v5 (docs/crypto/RELAY_FS.md): запечатати лист із прямою
         * секретністю. Null або відкат — лист іде v3, як досі. Типово — null:
         * без проводки нічого не міняється.
         */
        fun relayFsSeal(message: PeerMessage): RelayFsMail.Sealed? = null

        /** Відчинити лист v5 від [peerId] (зі стиранням ключів), або null. */
        fun relayFsOpen(raw: String, peerId: String): PeerMessage? = null

        /**
         * Копія v5, яку сховок віддав удруге (квитанція чи спалення не дійшли):
         * id листа, який вона вже принесла, або null. Ключа вже немає — тексту
         * не буде, але квитанцію автор мусить отримати.
         */
        fun relayFsLanded(raw: String, peerId: String): String? = null

        /** [PeerRelay.doorKey] цього телефона; null — дверей немає. */
        fun ownDoor(): ByteArray? = null

        /**
         * Лист із власних дверей: текст; "" — нашим ключем не відчиняється
         * (сміття, палиться); null — ключа ще немає, лист чекає на сервері.
         */
        fun openDoor(blob: ByteArray, tag: String): String? = null

        /** Лист із дверей. true — узято або відкинуто назавжди: копію можна палити. */
        suspend fun onDoor(raw: String): Boolean = false
    }

    /**
     * До цієї миті сховок просив не турбувати («повно» або «забагато запитів»).
     * Поки вона не минула, вкидання не йде в мережу взагалі: повторний стук
     * лише продовжив би ліміт.
     */
    @Volatile var waitUntilMs: Long = 0L
        private set

    /** План одного заходу разом із ключами, якими його читати. */
    class Prepared(
        val plan: PeerRelay.Plan,
        internal val keys: Map<String, ByteArray>,
        internal val relayKeys: Map<String, ByteArray>,
        /** Усі справжні адреси вмістились у план — живий канал бачить усе. */
        val complete: Boolean,
    )

    /** Скільки чого сталось цим заходом. Лише те, що справді полічено. */
    data class Report(
        val arrived: Int = 0,
        val delivered: Int = 0,
        val burned: Int = 0,
    )

    /**
     * Слово в сховок, коли поруч нікого. Викликається ЛИШЕ після того, як прямий
     * шлях відпав: [PeerRelay.first] і [PeerRelay.fallback] тримають цей порядок.
     *
     * Успіх — це «сервер тримає», і рівно так це й записується. З цієї миті лист
     * виходить із [PeerOutbox.due], тож драбина з восьми спроб проти сплячого
     * телефона перестає палити радіо.
     */
    suspend fun handOff(message: PeerMessage): Boolean {
        if (nowMs() < waitUntilMs) return false
        val me = world.selfId() ?: return false
        if (message.fromId != me) return false
        val pairKey = world.pairKey(message.toId) ?: return false
        val relayKey = PeerRelay.relayKey(pairKey, me, message.toId) ?: return false
        val fs = world.relayFsSeal(message) as? RelayFsMail.Sealed.V5
        val presealed = fs?.let { PeerEnvelope.Sealed(it.frame, it.nonceB64) }
        return when (val handoff = courier.handOff(message, pairKey, relayKey, presealed)) {
            is PeerRelayCourier.Handoff.Held -> {
                world.publish { current ->
                    // Прямий шлях доніс раніше: запечатування в рядку — його, не наше.
                    if (current.any { it.id == message.id && it.state == PeerMessage.State.DELIVERED }) {
                        return@publish PeerOutbox.markHeld(current, message.id, handoff.nonceB64, handoff.heldUntilMs)
                    }
                    PeerOutbox.noteSealing(
                        PeerOutbox.markHeld(
                            current,
                            message.id,
                            handoff.nonceB64,
                            handoff.heldUntilMs,
                        ),
                        message.id,
                        // Ключ зустрічі живе лише поки обидва на звʼязку, а тут
                        // адресата за визначенням немає. Запечатано довічним
                        // ключем пари — або конвертом v5 з одноразовим ключем,
                        // і так і записано.
                        when {
                            fs == null -> PeerMessage.Sealing.PAIR_KEY
                            fs.withOpk -> PeerMessage.Sealing.ONE_TIME_KEY
                            else -> PeerMessage.Sealing.SIGNED_PREKEY
                        },
                    )
                }
                true
            }

            PeerRelayCourier.Handoff.Refused -> {
                val at = nowMs()
                world.publish { PeerOutbox.markFailed(it, message.id, at) }
                false
            }

            // Сховка немає на звʼязку — це відсутність, не відмова. Спалити за
            // це спробу означало б вичерпати лист за те, що телефон у метро.
            PeerRelayCourier.Handoff.NoRelay -> false

            // «Повно» / «забагато запитів» — правило сервера, не вада листа.
            // Спроба ціла; сховок не турбуємо до названої миті.
            is PeerRelayCourier.Handoff.Wait -> {
                waitUntilMs = nowMs() + handoff.retryAfterMs
                false
            }
        }
    }

    /**
     * Забрати все своє. Порядок усередині: забрати → перевірити → записати →
     * квитанція → спалити, і жоден крок не переступається.
     */
    suspend fun poll(round: Long = 0L): Report {
        val prepared = prepare(round) ?: return Report()
        // null — сховок мовчить. Мовчання НЕ означає втрати: вертати за нього
        // все, що лежить у сховку, подвоїло б кожен лист на кожному провалі
        // звʼязку. Пропущений захід не коштує нічого, зайвий лист коштує.
        val pickup = courier.collect(prepared.plan, world::openDoor) { prepared.relayKeys[it] } ?: return Report()
        return settle(prepared, pickup.letters, pickup.storeSinceMs)
    }

    /** Що питати цим заходом (і на що підписувати живий канал). null — нема кого. */
    fun prepare(round: Long = 0L): Prepared? {
        val me = world.selfId() ?: return null
        val keys = LinkedHashMap<String, ByteArray>()
        // Порядок реєстру, а не хешу: вікно [PeerRelay.plan] для великого екіпажу
        // їде по цьому списку, і на перетасованому списку воно перестало б
        // обходити всіх.
        val relayKeys = LinkedHashMap<String, ByteArray>()
        for (peerId in world.contacts()) {
            val pairKey = world.pairKey(peerId) ?: continue
            val relayKey = PeerRelay.relayKey(pairKey, me, peerId) ?: continue
            keys[peerId] = pairKey
            relayKeys[peerId] = relayKey
        }
        val door = world.ownDoor()
        if (relayKeys.isEmpty() && door == null) return null
        val secret = world.decoySecret() ?: return null
        val plan = PeerRelay.plan(
            myId = me,
            mailboxes = relayKeys.map { PeerRelay.Mailbox(it.key, it.value) },
            nowMs = nowMs(),
            round = round,
            decoySecret = secret,
            door = door,
        )
        // Шість адрес на людину (дві дії × три доби).
        val doors = if (door != null) PeerRelay.DOOR_SLOTS else 0
        val complete = relayKeys.size * 6 + doors <= PeerRelay.FETCH_TAGS
        return Prepared(plan, keys, relayKeys, complete)
    }

    /** Копії, які штовхнув живий канал: той самий порядок, що й після забору. */
    suspend fun absorb(
        prepared: Prepared,
        blobs: List<PeerRelayTransport.Blob>,
        storeSinceMs: Long?,
    ): Report = settle(prepared, courier.open(prepared.plan, blobs, world::openDoor) { prepared.relayKeys[it] }, storeSinceMs)

    private suspend fun settle(
        prepared: Prepared,
        letters: List<PeerRelayCourier.Letter>,
        storeSinceMs: Long?,
    ): Report {
        val me = world.selfId() ?: return Report()
        val keys = prepared.keys
        val relayKeys = prepared.relayKeys
        sweepWiped(storeSinceMs)

        var arrived = 0
        var delivered = 0
        val spent = mutableListOf<String>()
        val receipts = mutableListOf<Pair<String, Pair<String, ByteArray>>>()

        for (letter in letters) {
            if (letter.kind == PeerRelay.Kind.INTRO) {
                // Не відчиняється нашим ключем — не наше, і чекати йому нема на
                // що: інакше той, хто бачив код, міг би завалити двері сміттям,
                // яке тиждень їздило б у кожному заборі поперед справжніх листів.
                if (letter.payload.isEmpty() || world.onDoor(letter.payload)) spent += letter.blobId
                continue
            }
            val pairKey = keys[letter.peerId] ?: continue
            val relayKey = relayKeys[letter.peerId] ?: continue
            when (letter.kind) {
                PeerRelay.Kind.LETTER -> {
                    val v5 = RelayFs.parse(letter.payload)
                    val message = open(letter, me, pairKey)
                    if (message == null) {
                        // v5, уже відчинений раніше: ключ стерто, тож тексту немає, але
                        // копію впізнано — квитанція йде ще раз, копія горить.
                        val seenId = if (v5 != null) world.relayFsLanded(letter.payload, letter.peerId) else null
                        if (seenId != null) {
                            val stub = PeerMessage(id = seenId, fromId = letter.peerId, toId = me, body = "", sentAtMs = 0L)
                            courier.receipt(stub, pairKey, relayKey, letter.outerNonceB64)
                                ?.let { receipts += letter.blobId to it }
                        }
                        continue
                    }
                    val landed = message.copy(
                        state = PeerMessage.State.DELIVERED,
                        sealing = when {
                            v5 == null -> PeerMessage.Sealing.PAIR_KEY
                            v5.opk >= 0 -> PeerMessage.Sealing.ONE_TIME_KEY
                            else -> PeerMessage.Sealing.SIGNED_PREKEY
                        },
                    )
                    if (!world.persist(landed)) continue
                    arrived++
                    // Квитанція — умова спалення нарівні із записом. Якщо вона
                    // не пішла, копія лишається: наступний захід забере її ще
                    // раз, запис відсіється за id, і квитанція піде тоді.
                    // Спалити тут означало б забрати в автора «ДОСТАВЛЕНО»
                    // назавжди, і він би цього не побачив.
                    courier.receipt(message, pairKey, relayKey, letter.outerNonceB64)
                        ?.let { receipts += letter.blobId to it }
                }

                PeerRelay.Kind.INTRO -> Unit

                PeerRelay.Kind.RECEIPT -> {
                    val candidates = PeerOutbox.receiptable(world.outbox(), letter.peerId)
                    val owner = PeerRelay.receiptOwner(candidates, letter.payload, pairKey) ?: continue
                    if (candidates.any { it.id == owner && it.state == PeerMessage.State.HELD }) {
                        world.publish { PeerOutbox.markDelivered(it, owner) }
                        delivered++
                    }
                    spent += letter.blobId
                }
            }
        }

        // Усі квитанції заходу — одним пакетом, а не запитом на кожну.
        if (receipts.isNotEmpty()) {
            val held = courier.acknowledgeAll(receipts.map { it.second })
            receipts.forEachIndexed { i, (blobId, _) -> if (held.getOrNull(i) == true) spent += blobId }
        }

        // Один захід може забрати більше копій, ніж сервер приймає в одному
        // спаленні: скринька тримає до 32 листів, а імен у запиті 64.
        spent.chunked(PeerRelay.FETCH_TAGS).forEach { courier.burn(it) }
        return Report(arrived = arrived, delivered = delivered, burned = spent.size)
    }

    /**
     * Сховок, який щойно відповів, народився ПІЗНІШЕ, ніж ми поклали в нього
     * лист. Отже нашої копії там уже немає: базу стерли й почали заново.
     *
     * Це ЄДИНА підстава вернути лист достроково, і вона — явне свідчення
     * сервера, а не здогад із порожньої видачі. Свого листа відправник у видачі
     * не побачить ніколи: він лежить у скриньці адресата, яку опитує адресат.
     * Тому «порожньо» тут не означає нічого й не сміє нічого вирішувати.
     *
     * [storeSinceMs] = null — сховок старий і про своє народження не каже.
     * Тоді нічого не змінюється: лист лишається HELD, і його поверне лише
     * семиденний [sweepExpired], як було до 07.09.
     */
    private fun sweepWiped(storeSinceMs: Long?) {
        val since = storeSinceMs ?: return
        val lost = PeerOutbox.heldBefore(world.outbox(), since)
        if (lost.isEmpty()) return
        world.publish { current ->
            lost.fold(current) { acc, id -> PeerOutbox.markExpiredHeld(acc, id) }
        }
    }

    /**
     * Тиждень минув, квитанції немає. Сервер уже стер тимчасову копію — сам лист
     * нікуди не дівався, тож він повертається в чергу з чистими спробами й піде
     * наступним заходом у скриньку цієї доби.
     */
    fun sweepExpired() {
        val stale = PeerOutbox.expiredHeld(world.outbox(), nowMs())
        if (stale.isEmpty()) return
        world.publish { current ->
            stale.fold(current) { acc, id -> PeerOutbox.markExpiredHeld(acc, id) }
        }
    }

    /**
     * Адресат питає лише вчора, сьогодні й завтра. Копія, покладена раніше, лежить
     * на сервері тиждень, але її вже ніхто не спитає — тож вона перекладається в
     * сьогоднішню скриньку. Новий строк зсуває доби, тож другий раз за день не
     * перекладається; адресат відсіє повтор за id.
     *
     * Лише поки лист живий ([PeerOutbox.staleHeld]): кожне перекладання ставить
     * новий строк, і без межі [sweepExpired] не настав би ніколи — лист до
     * того, хто стер застосунок, перекладався б щодва дні вічно.
     */
    suspend fun redropStale(): Int {
        val me = world.selfId() ?: return 0
        val now = nowMs()
        val stale = PeerOutbox.staleHeld(world.outbox(), me, PeerRelay.epochOf(now) - 1, now)
        return stale.count { handOff(it) }
    }

    /**
     * Ті самі перевірки, що й на дроті, і в тому самому порядку. v4 сюди не
     * потрапляє: [PeerEnvelope.open] без ключа зустрічі його не відчиняє, і це
     * правильно — кадр зустрічі в сховку означав би, що ключ пережив зустріч.
     */
    private fun open(
        letter: PeerRelayCourier.Letter,
        me: String,
        pairKey: ByteArray,
    ): PeerMessage? {
        // v5 — лише через ключі, що стираються; v3 — як досі. Кадр v5 ніколи не
        // пробується довічним ключем пари.
        val message = if (RelayFs.parse(letter.payload) != null) {
            world.relayFsOpen(letter.payload, letter.peerId) ?: return null
        } else {
            PeerEnvelope.open(letter.payload) { pairKey } ?: return null
        }
        if (message.fromId != letter.peerId || message.toId != me) return null
        val signing = world.signingKey(letter.peerId) ?: return null
        if (!PeerSealing.verify(message, signing, expectedFromId = letter.peerId)) return null
        return message
    }
}
