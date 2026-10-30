package local.phantom.companion.core.net.peer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Хто носить листи до сховка й назад.
 *
 * Тримає рівно одну відповідальність: перекласти кадр у те, що сервер уміє
 * тримати, і назад. Ані реєстру, ані підписів, ані стану черги тут немає —
 * перевірити підпис і записати рядок може лише той, хто тримає реєстр, і
 * [PeerEnvelope.open] лишається єдиними дверима всередину.
 *
 * Порядок «забрати → зберегти → спалити» не можна міняти місцями: обірвана
 * відповідь на спаленні коштує повторний лист, обірвана відповідь на
 * збереженні після спалення коштує лист назавжди.
 */
class PeerRelayCourier(
    private val relay: PeerRelayTransport,
    private val live: () -> PeerRelayLive? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    sealed interface Handoff {
        /** Сервер узяв. [nonceB64] — те, до чого буде привʼязана квитанція. */
        data class Held(val nonceB64: String, val heldUntilMs: Long) : Handoff

        /** Сховок відповів, але не взяв. Коштує листу спробу, як і будь-яка інша. */
        data object Refused : Handoff

        /**
         * «Скринька повна» або «забагато запитів». Не провина листа: спроба не
         * витрачається, а сховок не турбують раніше за [retryAfterMs].
         */
        data class Wait(val retryAfterMs: Long) : Handoff

        /** Сховка немає на звʼязку — лист просто чекає далі, спроби не витрачено. */
        data object NoRelay : Handoff
    }

    class Letter(
        val blobId: String,
        val peerId: String,
        val kind: PeerRelay.Kind,
        /** Кадр [PeerEnvelope] для [PeerRelay.Kind.LETTER], сама квитанція для RECEIPT. */
        val payload: String,
        val outerNonceB64: String,
    )

    /**
     * Слово в сховок. Запечатується під довічним ключем пари, бо ключ зустрічі
     * живе лише поки обидва на звʼязку, а тут адресата за визначенням немає —
     * або конвертом v5 ([presealed]), якщо проводка прямої секретності ввімкнена.
     */
    suspend fun handOff(
        message: PeerMessage,
        pairKey: ByteArray,
        relayKey: ByteArray,
        /** Уже запечатаний кадр v5 (пряма секретність); null — v3 під ключем пари, як досі. */
        presealed: PeerEnvelope.Sealed? = null,
    ): Handoff {
        val at = nowMs()
        val sealed = presealed ?: PeerEnvelope.seal(message, pairKey) ?: return Handoff.Refused
        val tag = PeerRelay.msgTag(
            relayKey,
            message.fromId,
            message.toId,
            PeerRelay.epochOf(at),
        ) ?: return Handoff.Refused
        val wrapped = PeerRelay.wrap(sealed.frame, relayKey, tag, small = relay.smallBlobs)
            ?: return Handoff.Refused
        return handoffOf(dropOne(tag, wrapped.blob), wrapped.nonceB64, at)
    }

    /** Кадр у двері людини на сьогодні: знайомство або завеликий для живого каналу сигнал. */
    suspend fun dropDoor(theirDhPubB64: String, theirId: String, frame: String): Handoff {
        val at = nowMs()
        val tag = PeerRelay.doorTag(theirDhPubB64, theirId, PeerRelay.epochOf(at)) ?: return Handoff.Refused
        val blob = PeerRelay.sealDoor(frame, theirDhPubB64, tag, small = relay.smallBlobs) ?: return Handoff.Refused
        return handoffOf(dropOne(tag, blob), "", at)
    }

    /**
     * Що сталось із живим кадром. [NO_CHANNEL] — каналу зараз немає, і повтор
     * за хвилину може вдатись; [REFUSED] — з цього ключа дверей не скласти
     * ніколи, і повтор не допоможе. Злиті в одне, вони ховали зламаний рядок
     * реєстру за «немає звʼязку».
     */
    enum class Live { SENT, TOO_BIG, NO_CHANNEL, REFUSED }

    /** Сигнал у двері живим каналом. Сервер його не зберігає. */
    fun sendLive(theirDhPubB64: String, theirId: String, frame: String): Live {
        val tag = PeerRelay.doorTag(theirDhPubB64, theirId, PeerRelay.epochOf(nowMs())) ?: return Live.REFUSED
        val channel = live()?.takeIf { it.subscribed } ?: return Live.NO_CHANNEL
        val sealed = PeerRelay.sealLive(frame, theirDhPubB64, tag) ?: return Live.TOO_BIG
        return if (channel.sendLive(tag, sealed)) Live.SENT else Live.NO_CHANNEL
    }

    private fun handoffOf(kept: PeerRelayTransport.Dropped, nonceB64: String, at: Long): Handoff =
        when (kept.verdict) {
            // Строк — сервера, і лише у крайньому разі наш. Народження бази
            // сховка теж рахується його годинником, тож наш «зараз + TTL» тут
            // означав би порівняння двох різних годинників.
            PeerRelayTransport.Drop.HELD ->
                Handoff.Held(nonceB64, kept.heldUntilMs ?: (at + PeerRelay.TTL_MS))
            PeerRelayTransport.Drop.NO_CONTACT -> Handoff.NoRelay
            PeerRelayTransport.Drop.FULL,
            PeerRelayTransport.Drop.BUSY,
            -> Handoff.Wait(kept.retryAfterMs ?: DEFAULT_WAIT_MS)
            PeerRelayTransport.Drop.REFUSED -> Handoff.Refused
        }

    /** Що приніс один захід. Порожні [letters] — це відповідь, а не мовчання. */
    class Pickup(val letters: List<Letter>, val storeSinceMs: Long?)

    /**
     * Один захід по всі скриньки. Те, що не відчиняється, мовчки випадає: сервер
     * міг віддати що завгодно, і єдине, що це означає, — воно не наше.
     *
     * [openDoor] каже про лист у власних дверях одне з трьох: текст; порожній
     * рядок — нашим ключем не відчиняється, отже сміття, і його палять (двері
     * рахує кожен, хто бачив код, і лишене сміття їздило б у кожному заборі
     * тиждень, відсуваючи справжні листи); null — ключа ще немає чим спробувати,
     * і лист чекає на сервері.
     *
     * null — до сервера не достукались. Складати це з «сервер відповів, скриньки
     * порожні» в один порожній список і було коренем вади 07.09: насос не мав
     * чим відрізнити витирання від тиші й мусив мовчати сам сім діб.
     */
    suspend fun collect(
        plan: PeerRelay.Plan,
        openDoor: (blob: ByteArray, tag: String) -> String? = { _, _ -> null },
        relayKeyOf: (peerId: String) -> ByteArray?,
    ): Pickup? {
        val fetched = relay.fetch(plan.tags) ?: return null
        return Pickup(open(plan, fetched.blobs, openDoor, relayKeyOf), fetched.storeSinceMs)
    }

    /** Те саме розпізнавання для копій, які штовхнув живий канал. */
    fun open(
        plan: PeerRelay.Plan,
        blobs: List<PeerRelayTransport.Blob>,
        openDoor: (blob: ByteArray, tag: String) -> String? = { _, _ -> null },
        relayKeyOf: (peerId: String) -> ByteArray?,
    ): List<Letter> =
        blobs.mapNotNull { blob ->
            val slot = plan.slots[blob.tag] ?: return@mapNotNull null
            if (slot.kind == PeerRelay.Kind.INTRO) {
                val payload = openDoor(blob.bytes, blob.tag) ?: return@mapNotNull null
                return@mapNotNull Letter(blob.id, slot.peerId, slot.kind, payload, "")
            }
            val key = relayKeyOf(slot.peerId) ?: return@mapNotNull null
            val payload = PeerRelay.unwrap(blob.bytes, key, blob.tag) ?: return@mapNotNull null
            val nonce = PeerRelay.nonceOf(blob.bytes) ?: return@mapNotNull null
            Letter(blob.id, slot.peerId, slot.kind, payload, nonce)
        }

    /**
     * Квитанція за забраний лист — і викликати це можна ЛИШЕ після того, як лист
     * уже лежить на диску. Квитанція означає «воно в мене», і вона потрапляє в
     * єдине місце, де людина потім читає «ДОСТАВЛЕНО».
     *
     * [outerNonceB64] — nonce тієї копії, яку віддав сховок; автор перебирає
     * свої відправлені саме за ним.
     */
    suspend fun acknowledge(
        message: PeerMessage,
        pairKey: ByteArray,
        relayKey: ByteArray,
        outerNonceB64: String,
    ): Boolean {
        val drop = receipt(message, pairKey, relayKey, outerNonceB64) ?: return false
        return dropOne(drop.first, drop.second).verdict == PeerRelayTransport.Drop.HELD
    }

    /**
     * Квитанції всього заходу — одним рухом. Сховок v2 робить з цього один
     * запит; відповідь — по одній правді на кожну, у тому ж порядку.
     */
    suspend fun acknowledgeAll(receipts: List<Pair<String, ByteArray>>): List<Boolean> {
        val socket = live().takeIf { relay.liveDrops }
        val verdicts = MutableList<PeerRelayTransport.Dropped?>(receipts.size) { null }
        // Усі кадри — в сокет одразу, квитанції сервера чекаються разом: по
        // одній це коштувало б обертання на кожну, а на 2G обертання — 2 с.
        if (socket != null) {
            coroutineScope {
                receipts.map { (tag, blob) ->
                    async { if (blob.size <= SOCKET_MAX_BLOB) socket.drop(tag, blob) else null }
                }.awaitAll()
            }.forEachIndexed { i, v -> verdicts[i] = v }
            relay.mirror(receipts.filterIndexed { i, _ -> verdicts[i] != null })
        }
        val rest = verdicts.indices.filter { verdicts[it] == null }
        if (rest.isNotEmpty()) {
            relay.dropMany(rest.map { receipts[it] }).forEachIndexed { k, v -> verdicts[rest[k]] = v }
        }
        return verdicts.map { it?.verdict == PeerRelayTransport.Drop.HELD }
    }

    /**
     * Сокет, коли сервер його оголосив; інакше, або коли сокет упав до квитанції, — HTTPS.
     * Великий конверт (шматок файла) у сокет не йде ніколи: на 30 кбіт/с 70 КБ —
     * це 20 с, і все, що стало в сокет за ним, слово чи квитанція, чекало б їх.
     */
    private suspend fun dropOne(tag: String, blob: ByteArray): PeerRelayTransport.Dropped =
        live().takeIf { relay.liveDrops && blob.size <= SOCKET_MAX_BLOB }?.drop(tag, blob)
            ?.also { relay.mirror(listOf(tag to blob)) }
            ?: relay.drop(tag, blob)

    /** Квитанція, готова до вкидання: (скринька, конверт). */
    fun receipt(
        message: PeerMessage,
        pairKey: ByteArray,
        relayKey: ByteArray,
        outerNonceB64: String,
    ): Pair<String, ByteArray>? {
        val receipt = PeerReceipt.of(
            pairKey,
            message.id,
            message.fromId,
            message.toId,
            outerNonceB64,
        ) ?: return null
        val tag = PeerRelay.ackTag(
            relayKey,
            message.fromId,
            message.toId,
            PeerRelay.epochOf(nowMs()),
        ) ?: return null
        val wrapped = PeerRelay.wrap(receipt, relayKey, tag, small = relay.smallBlobs) ?: return null
        return tag to wrapped.blob
    }

    suspend fun burn(blobIds: List<String>) {
        if (blobIds.isEmpty()) return
        relay.burn(blobIds)
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 10_000L
        val SOCKET_MAX_BLOB = PeerRelay.BLOB_SIZES.first()
    }
}
