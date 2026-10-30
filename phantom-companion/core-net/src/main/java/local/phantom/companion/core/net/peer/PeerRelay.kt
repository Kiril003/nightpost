package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.AesGcm
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.randomBytes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Сховок: як лист доходить до телефона, який спав.
 *
 * Досі слово йшло тільки прямим сокетом по mDNS — тобто не йшло взагалі, поки
 * обидва телефони не опинялись у мережі одночасно. Тут сервер тримає байти, яких
 * не вміє прочитати, і віддає їх тому, хто знає, під яким іменем питати.
 *
 * Що сервер НЕ дізнається, і не тому що обіцяв: адреси поштових скриньок — це
 * HMAC під ключем пари, тож ані ідентифікатора людини, ані ідентифікатора листа
 * в запиті немає; кожна скринька живе одну добу й наступної незвʼязна з
 * попередньою; забір завжди питає рівно [FETCH_TAGS] імен, добитих вигаданими,
 * що живуть за тим самим розкладом, тож мовчазний друг не відрізняється від
 * вигадки ні в одному запиті, ні в порівнянні запитів між собою; довжина
 * листа зводиться до трьох, тож квитанція не відрізняється від слова.
 *
 * Що сервер ТАКИ бачить, сказано вголос, бо інакше це була б брехня: IP і час
 * кожного вкидання й кожного забору. Хто вкинув у скриньку X і хто того ж дня
 * її забрав — це одне ребро на добу. Жодна хитрість протоколу цього не знімає,
 * поки сховок один; знімає лише VPN/Tor або взагалі не користуватись сховком —
 * пряма сітка лишається першою, а це запасний шлях.
 *
 * Вміст — звичайний кадр [PeerEnvelope] v3 під довічним ключем пари. Не v4:
 * ключ зустрічі існує лише поки обидва на звʼязку, а весь сенс сховка в тому,
 * що адресата зараз немає. Тому доставлене сюди чесно записується як
 * [PeerMessage.Sealing.PAIR_KEY].
 */
object PeerRelay {

    /** Скільки сервер тримає лист, якщо його ніхто не забрав. */
    const val TTL_DAYS = 7

    const val EPOCH_MS = 86_400_000L

    const val TTL_MS: Long = TTL_DAYS * EPOCH_MS

    /** База64url без набивки від 32 байтів HMAC. */
    const val TAG_CHARS = 43

    /** Скільки імен несе КОЖЕН забір — незмінно, інакше число саме стає підписом. */
    const val FETCH_TAGS = 64

    /** Мінімум, який дає платформа періодичній роботі. */
    const val POLL_MINUTES = 15L

    /**
     * Розміри відкритого тексту перед шифруванням. Три — і не більше: кожен
     * зайвий робить менший гурт, у якому лист розчиняється.
     */
    val BUCKETS: List<Int> = listOf(4_096, 16_384, 69_632)

    private const val GCM_TAG = 16

    private const val OVERHEAD = AesGcm.IV_SIZE_BYTES + GCM_TAG

    /**
     * Єдині довжини, які сервер приймає. Виводяться, а не переписуються руками:
     * розбіжність між тим, що шле телефон, і тим, що пускає сервер, не має
     * жодного видимого симптому — лист просто не доходить.
     */
    val BLOB_SIZES: List<Int> = BUCKETS.map { it + OVERHEAD }

    /**
     * Кошик для короткого слова й квитанції: 4 КБ радіо за «ок» — це секунда
     * на поганому звʼязку. Лише коли сервер сам оголосив 540 у здоровʼї: інакше
     * він відкине довжину, якої не знає, і лист мовчки не дійде.
     */
    const val SMALL_BUCKET = 512

    const val SMALL_BLOB_SIZE: Int = SMALL_BUCKET + OVERHEAD

    fun isBlobSize(size: Int): Boolean = size == SMALL_BLOB_SIZE || size in BLOB_SIZES

    private const val SEP = ''
    private const val AAD = "PH5"
    private const val MBOX = "mbox"
    private const val ACK = "ack"
    private const val ALGORITHM = "HmacSHA256"
    private const val LENGTH_PREFIX = 4

    private val INFO = "PHANTOM OS/peer-relay-v1".toByteArray(Charsets.UTF_8)
    private val DECOY_INFO = "PHANTOM OS/peer-relay-decoys-v3".toByteArray(Charsets.UTF_8)
    private val DOOR_INFO = "PHANTOM OS/peer-door-v1".toByteArray(Charsets.UTF_8)
    private val DOOR_SEAL_INFO = "PHANTOM OS/peer-door-seal-v1".toByteArray(Charsets.UTF_8)
    private val KNOCK_INFO = "PHANTOM OS/peer-door-knock-v1".toByteArray(Charsets.UTF_8)
    private const val KNOCK = "knock"
    private const val DOOR = "door"
    private const val DOOR_AAD = "PH5D"
    private val TAG_SHAPE = Regex("[A-Za-z0-9_-]{$TAG_CHARS}")

    /** Двері вчора, сьогодні й завтра — у кожному заборі. */
    const val DOOR_SLOTS = 3

    /** Живий кадр у байтах: base64 від стількох — рівно `LIVE_MAX_B64` сервера (16 КіБ). */
    const val LIVE_MAX_BYTES = 12_288

    private const val LIVE_PAD = 512

    // Одноразовий ключ стоїть на місці nonce й забирає ще 20 байтів, тож
    // запечатаний лист у дверях має ту саму довжину, що й лист пари.
    private val DOOR_EXTRA = X25519.PUBLIC_KEY_SIZE - AesGcm.IV_SIZE_BYTES

    private val ZERO_NONCE = ByteArray(AesGcm.IV_SIZE_BYTES)

    /** Що лежить у скриньці: слово, підтвердження про слово, чи стук у двері. */
    enum class Kind { LETTER, RECEIPT, INTRO }

    /** Куди можна віддати цей лист просто зараз. */
    enum class Way { DIRECT, RELAY, WAIT }

    class Mailbox(val peerId: String, val relayKey: ByteArray)

    data class Slot(val peerId: String, val kind: Kind, val epoch: Long)

    /** Що спитати в сервера, і як прочитати те, що він віддасть. */
    data class Plan(val tags: List<String>, val slots: Map<String, Slot>)

    class Wrapped(val blob: ByteArray, val nonceB64: String)

    fun epochOf(nowMs: Long): Long = Math.floorDiv(nowMs, EPOCH_MS)

    /**
     * Ключ сховка. Окремий від ключа пари, хоч і виводиться з нього: під ним
     * складаються адреси скриньок, і ключ, яким ще й шифрують листи, не має
     * підписувати те, що бачить чужий.
     */
    fun relayKey(pairKey: ByteArray, myId: String, theirId: String): ByteArray? {
        if (pairKey.isEmpty() || myId.isBlank() || theirId.isBlank()) return null
        if (SEP in myId || SEP in theirId) return null
        val low = if (myId <= theirId) myId else theirId
        val high = if (myId <= theirId) theirId else myId
        return runCatching {
            Hkdf.derive(
                ikm = pairKey,
                salt = "$low$SEP$high".toByteArray(Charsets.UTF_8),
                info = INFO,
                length = AesGcm.KEY_SIZE_BYTES,
            )
        }.getOrNull()
    }

    /** Скринька, куди [senderId] кладе слово для [recipientId] у цю добу. */
    fun msgTag(relayKey: ByteArray, senderId: String, recipientId: String, epoch: Long): String? =
        tag(relayKey, MBOX, senderId, recipientId, epoch)

    /**
     * Скринька для квитанції. Порядок сторін ТОЙ САМИЙ, що в [msgTag], —
     * квитанцію кладе адресат, а забирає автор, і обидва мусять назвати ту саму
     * адресу, не домовляючись про напрямок.
     */
    fun ackTag(relayKey: ByteArray, senderId: String, recipientId: String, epoch: Long): String? =
        tag(relayKey, ACK, senderId, recipientId, epoch)

    private fun tag(
        relayKey: ByteArray,
        verb: String,
        senderId: String,
        recipientId: String,
        epoch: Long,
    ): String? {
        if (relayKey.isEmpty() || senderId.isBlank() || recipientId.isBlank()) return null
        if (SEP in senderId || SEP in recipientId) return null
        val canonical = "$verb$SEP$senderId$SEP$recipientId$SEP$epoch"
        val mac = runCatching {
            Mac.getInstance(ALGORITHM)
                .apply { init(SecretKeySpec(relayKey, ALGORITHM)) }
                .doFinal(canonical.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return b64url(mac)
    }

    fun isTag(text: String): Boolean = TAG_SHAPE.matches(text)

    /**
     * Ключ дверей людини — з того, що несе її код запрошення, і ні з чого
     * більше: хто бачив код, той може постукати, а інших дверей не знайде.
     */
    fun doorKey(dhPub: ByteArray, peerId: String): ByteArray? {
        if (dhPub.size != X25519.PUBLIC_KEY_SIZE || peerId.isBlank() || SEP in peerId) return null
        return runCatching {
            Hkdf.derive(
                ikm = dhPub + peerId.toByteArray(Charsets.UTF_8),
                salt = DOOR_INFO,
                info = DOOR_INFO,
                length = AesGcm.KEY_SIZE_BYTES,
            )
        }.getOrNull()
    }

    fun doorTagOf(doorKey: ByteArray, epoch: Long): String? {
        if (doorKey.isEmpty()) return null
        val mac = runCatching {
            Mac.getInstance(ALGORITHM)
                .apply { init(SecretKeySpec(doorKey, ALGORITHM)) }
                .doFinal("$DOOR$SEP$epoch".toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return b64url(mac)
    }

    fun doorTag(dhPubB64: String, peerId: String, epoch: Long): String? {
        val pub = dhPubOf(dhPubB64) ?: return null
        return doorKey(pub, peerId)?.let { doorTagOf(it, epoch) }
    }

    /**
     * Нонс стуку в двері [theirId] на добу [epoch] — з власного приватного
     * X25519, тож після перезапуску телефон складає його наново й упізнає
     * відповідь, яка прийшла на стук, зроблений до перезапуску.
     *
     * Однаковий нонс під тим самим ключем знайомства мусить запечатувати той
     * самий текст до байта, інакше AES-GCM віддає обидва. Тому в стуку немає
     * адреси в мережі (вона міняється), а [myName] — єдине, що в ньому може
     * змінитись, — стоїть тут: нове імʼя дає новий нонс.
     */
    fun knockNonce(myDhPrivate: ByteArray, theirId: String, epoch: Long, myName: String): ByteArray? {
        if (myDhPrivate.size != X25519.PRIVATE_KEY_SIZE || theirId.isBlank() || SEP in theirId) return null
        val key = runCatching {
            Hkdf.derive(ikm = myDhPrivate, salt = KNOCK_INFO, info = KNOCK_INFO, length = AesGcm.KEY_SIZE_BYTES)
        }.getOrNull() ?: return null
        val mac = try {
            runCatching {
                Mac.getInstance(ALGORITHM)
                    .apply { init(SecretKeySpec(key, ALGORITHM)) }
                    .doFinal("$KNOCK$SEP$theirId$SEP$epoch$SEP$myName".toByteArray(Charsets.UTF_8))
            }.getOrNull()
        } finally {
            key.fill(0)
        } ?: return null
        return mac.copyOf(AesGcm.IV_SIZE_BYTES)
    }

    /** Лист у двері, тієї ж довжини, що й лист пари: сервер їх не розрізнить. */
    fun sealDoor(payload: String, dhPubB64: String, tag: String, small: Boolean = false): ByteArray? {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val buckets = if (small) listOf(SMALL_BUCKET) + BUCKETS else BUCKETS
        val bucket = buckets.firstOrNull { it - DOOR_EXTRA >= LENGTH_PREFIX + bytes.size } ?: return null
        return seal(bytes, bucket - DOOR_EXTRA, dhPubB64, tag)
    }

    /** Живий кадр у двері. Null — завеликий для живого каналу; тоді він іде листом. */
    fun sealLive(payload: String, dhPubB64: String, tag: String): ByteArray? {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val room = LIVE_MAX_BYTES - X25519.PUBLIC_KEY_SIZE - GCM_TAG
        val need = LENGTH_PREFIX + bytes.size
        if (need > room) return null
        val padded = minOf(room, (need + LIVE_PAD - 1) / LIVE_PAD * LIVE_PAD)
        return seal(bytes, padded, dhPubB64, tag)
    }

    fun openDoor(blob: ByteArray, myDhPrivate: ByteArray, tag: String): String? {
        if (!isTag(tag) || blob.size < X25519.PUBLIC_KEY_SIZE + GCM_TAG + LENGTH_PREFIX) return null
        if (myDhPrivate.size != X25519.PRIVATE_KEY_SIZE) return null
        val eph = blob.copyOfRange(0, X25519.PUBLIC_KEY_SIZE)
        val ct = blob.copyOfRange(X25519.PUBLIC_KEY_SIZE, blob.size)
        val key = doorSealKey(myDhPrivate, eph, eph) ?: return null
        val plain = try {
            runCatching { AesGcm.decrypt(key, ct, ZERO_NONCE, doorAad(tag)) }.getOrNull() ?: return null
        } finally {
            key.fill(0)
        }
        return unpad(plain)
    }

    private fun seal(bytes: ByteArray, plainSize: Int, dhPubB64: String, tag: String): ByteArray? {
        if (!isTag(tag)) return null
        val theirs = dhPubOf(dhPubB64) ?: return null
        val eph = X25519.generateKeypair()
        val key = doorSealKey(eph.privateKey, theirs, eph.publicKey) ?: return null
        val plain = ByteArray(plainSize)
        writeLength(plain, bytes.size)
        System.arraycopy(bytes, 0, plain, LENGTH_PREFIX, bytes.size)
        // Ключ одноразовий, тож нульовий nonce під ним не повторюється ніколи.
        val ct = try {
            runCatching { AesGcm.encrypt(key, plain, ZERO_NONCE, doorAad(tag)) }.getOrNull() ?: return null
        } finally {
            key.fill(0)
            eph.privateKey.fill(0)
        }
        return eph.publicKey + ct
    }

    private fun doorSealKey(myPrivate: ByteArray, theirPub: ByteArray, ephPub: ByteArray): ByteArray? {
        val shared = runCatching { X25519.ecdh(myPrivate, theirPub) }.getOrNull() ?: return null
        return try {
            if (shared.all { it == 0.toByte() }) return null
            runCatching {
                Hkdf.derive(ikm = shared, salt = ephPub, info = DOOR_SEAL_INFO, length = AesGcm.KEY_SIZE_BYTES)
            }.getOrNull()
        } finally {
            shared.fill(0)
        }
    }

    private fun dhPubOf(b64: String): ByteArray? =
        runCatching { java.util.Base64.getDecoder().decode(b64.trim()) }.getOrNull()
            ?.takeIf { it.size == X25519.PUBLIC_KEY_SIZE }

    private fun doorAad(tag: String): ByteArray = "$DOOR_AAD$SEP$tag".toByteArray(Charsets.UTF_8)

    /**
     * Вигадані адреси доби [epoch]. Живуть так само, як справжні: та сама доба
     * стоїть у запиті три дні поспіль (завтра, сьогодні, вчора), тож за
     * повтором у часі їх не відсіяти — ні між заборами, ні через північ.
     */
    fun decoyTags(count: Int, secret: ByteArray, epoch: Long): List<String> {
        if (count <= 0 || secret.isEmpty()) return emptyList()
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
        return List(count) { i ->
            b64url(mac.doFinal("decoy$SEP$epoch$SEP$i".toByteArray(Charsets.UTF_8)))
        }
    }

    /**
     * Таємниця вигаданих адрес — з власного приватного X25519 цього телефона.
     * Не з ключів пари: з одним другом такий ключ знав би й друг, а зі зміною
     * реєстру всі вигадані перевернулись би разом, лишивши справжні голими.
     */
    fun decoySecret(ownDhPrivateKey: ByteArray): ByteArray? {
        if (ownDhPrivateKey.isEmpty()) return null
        return runCatching {
            Hkdf.derive(
                ikm = ownDhPrivateKey,
                salt = DECOY_INFO,
                info = DECOY_INFO,
                length = AesGcm.KEY_SIZE_BYTES,
            )
        }.getOrNull()
    }

    /**
     * Частка запиту, яку займає доба [epoch]: 22 або 21. Залежить від самої
     * доби, а не від її місця у вікні, — інакше на північ частка мінялась би
     * і вигадані хвости зникали б, видаючи себе.
     */
    fun shareOf(epoch: Long): Int =
        FETCH_TAGS / 3 + if (Math.floorMod(epoch, 3L) < FETCH_TAGS % 3) 1 else 0

    /**
     * Кадр у вигляді, який сервер тримає: свіжий nonce, набивка до одного з
     * трьох розмірів, імʼя скриньки в AAD — кадр, перекладений в іншу скриньку,
     * не відчиниться.
     */
    fun wrap(
        payload: String,
        relayKey: ByteArray,
        tag: String,
        nonce: ByteArray = randomBytes(AesGcm.IV_SIZE_BYTES),
        small: Boolean = false,
    ): Wrapped? {
        if (!isTag(tag)) return null
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val buckets = if (small) listOf(SMALL_BUCKET) + BUCKETS else BUCKETS
        val bucket = buckets.firstOrNull { it >= LENGTH_PREFIX + bytes.size } ?: return null
        val plain = ByteArray(bucket)
        writeLength(plain, bytes.size)
        System.arraycopy(bytes, 0, plain, LENGTH_PREFIX, bytes.size)
        val ct = runCatching { AesGcm.encrypt(relayKey, plain, nonce, aad(tag)) }.getOrNull()
            ?: return null
        return Wrapped(nonce + ct, b64(nonce))
    }

    fun unwrap(blob: ByteArray, relayKey: ByteArray, tag: String): String? {
        if (!isTag(tag)) return null
        if (!isBlobSize(blob.size)) return null
        val nonce = blob.copyOfRange(0, AesGcm.IV_SIZE_BYTES)
        val ct = blob.copyOfRange(AesGcm.IV_SIZE_BYTES, blob.size)
        val plain = runCatching { AesGcm.decrypt(relayKey, ct, nonce, aad(tag)) }.getOrNull()
            ?: return null
        return unpad(plain)
    }

    private fun writeLength(plain: ByteArray, size: Int) {
        plain[0] = (size ushr 24).toByte()
        plain[1] = (size ushr 16).toByte()
        plain[2] = (size ushr 8).toByte()
        plain[3] = size.toByte()
    }

    private fun unpad(plain: ByteArray): String? {
        if (plain.size < LENGTH_PREFIX) return null
        val length = ((plain[0].toInt() and 0xFF) shl 24) or
            ((plain[1].toInt() and 0xFF) shl 16) or
            ((plain[2].toInt() and 0xFF) shl 8) or
            (plain[3].toInt() and 0xFF)
        if (length < 0 || length > plain.size - LENGTH_PREFIX) return null
        return String(plain, LENGTH_PREFIX, length, Charsets.UTF_8)
    }

    /** Nonce тієї копії, яку сховок віддав — квитанція привʼязується саме до неї. */
    fun nonceOf(blob: ByteArray): String? =
        if (isBlobSize(blob.size)) b64(blob.copyOfRange(0, AesGcm.IV_SIZE_BYTES)) else null

    /**
     * Що спитати цим заходом.
     *
     * Три доби — сьогодні, завтра, вчора — бо годинники розходяться, і лист,
     * покладений о 23:59 їхнього часу, інакше загубився б до ранку.
     *
     * Екіпаж більший за десятеро не вміщається в [FETCH_TAGS] імен за раз
     * (шість імен на людину). Тоді вікно їде далі з кожним [round]: жодна
     * скринька не голодує, але лист від найдальшого в черзі може почекати ще
     * один захід. Зменшити число імен було б дешевше — і саме воно тримає
     * анонімність, тож зменшувати його не можна.
     */
    fun plan(
        myId: String,
        mailboxes: List<Mailbox>,
        nowMs: Long,
        round: Long = 0L,
        rng: java.util.Random = java.security.SecureRandom(),
        decoySecret: ByteArray,
        door: ByteArray? = null,
    ): Plan {
        val today = epochOf(nowMs)
        val window = listOf(today, today + 1, today - 1)
        // Двері не їдуть вікном великого екіпажу: стук незнайомця не чекає черги.
        val doors = LinkedHashMap<String, Slot>()
        if (door != null) {
            for (epoch in window) doorTagOf(door, epoch)?.let { doors[it] = Slot(myId, Kind.INTRO, epoch) }
        }
        val all = LinkedHashMap<String, Slot>()
        for (epoch in window) {
            for (box in mailboxes) {
                msgTag(box.relayKey, box.peerId, myId, epoch)?.let {
                    all.putIfAbsent(it, Slot(box.peerId, Kind.LETTER, epoch))
                }
                ackTag(box.relayKey, myId, box.peerId, epoch)?.let {
                    all.putIfAbsent(it, Slot(box.peerId, Kind.RECEIPT, epoch))
                }
            }
        }
        doors.keys.forEach { all.remove(it) }
        val ordered = all.keys.toList()
        val room = FETCH_TAGS - doors.size
        val real = if (ordered.size <= room) {
            ordered
        } else {
            val start = Math.floorMod(round * room, ordered.size.toLong()).toInt()
            List(room) { ordered[(start + it) % ordered.size] }
        }
        val slots = LinkedHashMap<String, Slot>()
        real.forEach { slots[it] = all.getValue(it) }
        slots.putAll(doors)
        val decoys = window.sorted().flatMap { epoch ->
            val taken = slots.values.count { it.epoch == epoch }
            decoyTags(shareOf(epoch) - taken, decoySecret, epoch)
        }.filterNot { it in slots }.take(FETCH_TAGS - slots.size)
        val tags = (slots.keys + decoys).toMutableList()
        // Справжні імена не мають стояти першими: порядок у запиті — теж підпис.
        java.util.Collections.shuffle(tags, rng)
        return Plan(tags, slots)
    }

    /**
     * Перший вибір для одного листа. Мережа поруч завжди попереду: вона швидша,
     * дає ключ зустрічі й нікуди не виходить. Сховок — коли поруч нікого.
     */
    fun first(hasLanAddress: Boolean, online: Boolean): Way = when {
        hasLanAddress -> Way.DIRECT
        online -> Way.RELAY
        else -> Way.WAIT
    }

    /** Пряма спроба не вдалась. Далі — сховок, або чекаємо далі на телефоні. */
    fun fallback(online: Boolean): Way = if (online) Way.RELAY else Way.WAIT

    /**
     * Чию квитанцію щойно забрали. Квитанція не називає листа — вона привʼязана
     * до nonce тієї копії, тож автор перебирає свої, а чужий не перебере нічого.
     */
    fun receiptOwner(held: List<PeerMessage>, receipt: String, pairKey: ByteArray): String? =
        held.firstOrNull { message ->
            message.heldNonce.isNotBlank() && PeerReceipt.matches(
                PeerReceipt.of(
                    pairKey,
                    message.id,
                    message.fromId,
                    message.toId,
                    message.heldNonce,
                ),
                receipt,
            )
        }?.id

    /**
     * [watched] — скриньки, які телефон стереже: контакти плюс власні двері,
     * щойно є особистість. Нуль — жодного запиту в бік сховка.
     */
    fun pollingWanted(watched: Int): Boolean = watched > 0

    private fun aad(tag: String): ByteArray = "$AAD$SEP$tag".toByteArray(Charsets.UTF_8)

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun b64url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
