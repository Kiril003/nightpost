package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.AesGcm
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.randomBytes

/**
 * Знайомство у зворотний бік.
 *
 * Додати мусили обидва, інакше не приходило нічого: той, хто показав код,
 * лишався ні з ким, і кожен лист до нього падав без сліду. Одна дія мусить
 * наповнювати обидва реєстри — це те, що тут зʼявилось.
 *
 * Хто прийняв запрошення, той і винен знайомство: він шле свою картку назад
 * тим самим дротом. Право це зробити купує ЗНАННЯ ЧУЖОГО КОДУ і ніщо інше.
 * Статичний X25519 того боку не лежить більше ніде — маячок називає лише
 * peerId і порт, а вітання HLO несе ефемерний ключ, — тож кадр, який склався
 * під ключем з цього статичного, доводить рівно одне: відправник бачив код.
 * Саме таку владу код і мав завжди; досі вона просто не працювала в обидва
 * боки.
 *
 * Всередині — підпис Ed25519 під ідентифікатором, обома ключами й нонсом.
 * Без нього власник коду міг би привести чужий підписний ключ під своїм
 * ключем обміну й отруїти рядок, який ще ніхто не займав.
 *
 * Чисто й без сокетів: рішення, кого впускати, мусить доводитись без радіо.
 */
object PeerIntro {

    const val VERSION = 1

    /** Скільки не турбувати того, хто щойно відмовив. */
    const val RETRY_REFUSAL_MS = 15 * 60_000L

    /** Той самий, що й у решти протоколу: розділювач, якого немає в ключах. */
    private const val SEP = ''
    private const val AAD_TAG = "PH5"
    private const val TAG_OFFER = "PH5-INT-I"
    private const val TAG_BACK = "PH5-INT-R"
    private val INFO = "PHANTOM OS/peer-intro-v1".toByteArray(Charsets.UTF_8)

    /** Стільки байтів відбитка бере [local.phantom.companion.data.DeviceIdentityStore]. */
    private const val FINGERPRINT_BYTES = 8

    private val json = Json { ignoreUnknownKeys = true }

    /** `v` без типового значення з тієї ж причини, що й у [PeerEnvelope.Envelope]. */
    @Serializable
    data class Frame(
        val v: Int,
        val from: String,
        val to: String,
        /** Статичний X25519 відправника — під ним і складається ключ знайомства. */
        val dh: String,
        val n: String,
        val ct: String,
    )

    @Serializable
    private data class Inside(
        val name: String,
        val pub: String,
        val host: String = "",
        val port: Int = 0,
        val sig: String,
    )

    /** Хто представився. Все тут доведене підписом під відбитком. */
    data class Introduction(
        val peerId: String,
        val name: String,
        val pubKey: String,
        val dhPubKey: String,
        val host: String,
        val port: Int,
        /** Нонс цієї пропозиції: відповідь мусить назвати його всередині підпису. */
        val nonceB64: String,
        /** Назване імʼя було довше за [PeerName.MAX] — тут скорочене. Останнім
         *  з тієї ж причини, що й у [PeerIdentity]: позиційні виклики. */
        val nameShortened: Boolean = false,
    )

    /** Кадр і нонс, який доведеться впізнати у відповіді. */
    data class Offer(val frame: String, val nonceB64: String)

    /**
     * Відбиток ключа — те саме, що телефон рахує собі при першому запуску.
     * Тут воно живе, щоб приймач міг звірити назване імʼя з принесеним ключем,
     * не питаючи нікого.
     */
    fun fingerprint(pubKeyB64: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(pubKeyB64.toByteArray(Charsets.UTF_8))
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02x".format(it) }

    /**
     * Ключ знайомства. Окремий [INFO] від ключа листів навмисно: однакові байти
     * злили б два різні простори, і перехоплене знайомство можна було б колись
     * предʼявити як конверт.
     */
    fun key(
        myDhPrivate: ByteArray,
        theirDhPubB64: String,
        myId: String,
        theirId: String,
    ): ByteArray? {
        if (theirDhPubB64.isBlank() || myId.isBlank() || theirId.isBlank()) return null
        val theirs = runCatching {
            java.util.Base64.getDecoder().decode(theirDhPubB64.trim())
        }.getOrNull() ?: return null
        if (theirs.size != X25519.PUBLIC_KEY_SIZE) return null
        if (myDhPrivate.size != X25519.PRIVATE_KEY_SIZE) return null
        val shared = runCatching { X25519.ecdh(myDhPrivate, theirs) }.getOrNull() ?: return null
        try {
            val low = if (myId <= theirId) myId else theirId
            val high = if (myId <= theirId) theirId else myId
            return Hkdf.derive(
                ikm = shared,
                salt = "$low$SEP$high".toByteArray(Charsets.UTF_8),
                info = INFO,
                length = 32,
            )
        } finally {
            shared.fill(0)
        }
    }

    /** «Ось я» — тому, чий код щойно прочитали. */
    fun offer(
        me: PeerInvite.Invite,
        toId: String,
        theirDhPubB64: String,
        mySigningKey: ByteArray,
        myDhPrivate: ByteArray,
        nonce: ByteArray = randomBytes(AesGcm.IV_SIZE_BYTES),
    ): Offer? = build(me, toId, theirDhPubB64, mySigningKey, myDhPrivate, nonce, echoOf = null)

    /** Відповідь на прийняте знайомство: той самий ключ, інший бік транскрипту. */
    fun answer(
        me: PeerInvite.Invite,
        to: Introduction,
        mySigningKey: ByteArray,
        myDhPrivate: ByteArray,
        nonce: ByteArray = randomBytes(AesGcm.IV_SIZE_BYTES),
    ): String? =
        build(me, to.peerId, to.dhPubKey, mySigningKey, myDhPrivate, nonce, echoOf = to.nonceB64)
            ?.frame

    private fun build(
        me: PeerInvite.Invite,
        toId: String,
        theirDhPubB64: String,
        mySigningKey: ByteArray,
        myDhPrivate: ByteArray,
        nonce: ByteArray,
        echoOf: String?,
    ): Offer? {
        if (me.peerId.isBlank() || toId.isBlank() || me.peerId == toId) return null
        if (SEP in me.peerId || SEP in toId) return null
        if (me.pubKey.isBlank() || me.dhPubKey.isBlank()) return null
        if (mySigningKey.size != Ed25519.PRIVATE_KEY_SIZE) return null
        if (nonce.size != AesGcm.IV_SIZE_BYTES) return null
        val nonceB64 = b64(nonce)
        val transcript = transcript(
            echoOf = echoOf,
            from = me.peerId,
            to = toId,
            dh = me.dhPubKey,
            pub = me.pubKey,
            n = nonceB64,
        )
        val sig = runCatching { Ed25519.sign(mySigningKey, transcript) }.getOrNull() ?: return null
        val inside = json.encodeToString(
            Inside.serializer(),
            Inside(
                name = PeerName.cap(me.name),
                pub = me.pubKey,
                host = me.host,
                port = me.port,
                sig = b64(sig),
            ),
        ).toByteArray(Charsets.UTF_8)
        val k = key(myDhPrivate, theirDhPubB64, me.peerId, toId) ?: return null
        val ct = try {
            runCatching {
                AesGcm.encrypt(k, inside, nonce, aad(me.peerId, toId, me.dhPubKey))
            }.getOrNull() ?: return null
        } finally {
            k.fill(0)
        }
        val frame = json.encodeToString(
            Frame.serializer(),
            Frame(
                v = VERSION,
                from = me.peerId,
                to = toId,
                dh = me.dhPubKey,
                n = nonceB64,
                ct = b64(ct),
            ),
        )
        if (frame.toByteArray(Charsets.UTF_8).size > PeerWire.MAX_BODY_BYTES) return null
        return Offer(frame, nonceB64)
    }

    /** Адресування, яке спостерігач на мережі бачить і так; більше нічого. */
    fun parse(raw: String): Frame? {
        val frame = runCatching { json.decodeFromString(Frame.serializer(), raw) }.getOrNull()
            ?: return null
        if (frame.v != VERSION) return null
        val fields = listOf(frame.from, frame.to, frame.dh, frame.n, frame.ct)
        if (fields.any { it.isBlank() || SEP in it }) return null
        if (frame.from == frame.to) return null
        return frame
    }

    /**
     * Картка всередині, або нічого — і «нічого» ніколи не каже, чому.
     *
     * [echoOf] порожній для вхідної пропозиції і несе НАШ нонс, коли ми читаємо
     * відповідь: без цього чужу відповідь з іншої зустрічі можна було б підсунути
     * як цю.
     */
    fun open(
        raw: String,
        myId: String,
        myDhPrivate: ByteArray,
        echoOf: String? = null,
    ): Introduction? {
        val frame = parse(raw) ?: return null
        if (frame.to != myId) return null
        val nonce = runCatching { java.util.Base64.getDecoder().decode(frame.n) }.getOrNull()
            ?: return null
        if (nonce.size != AesGcm.IV_SIZE_BYTES) return null
        val ct = runCatching { java.util.Base64.getDecoder().decode(frame.ct) }.getOrNull()
            ?: return null
        val k = key(myDhPrivate, frame.dh, myId, frame.from) ?: return null
        val plain = try {
            runCatching {
                AesGcm.decrypt(k, ct, nonce, aad(frame.from, frame.to, frame.dh))
            }.getOrNull() ?: return null
        } finally {
            k.fill(0)
        }
        val inside = runCatching {
            json.decodeFromString(Inside.serializer(), String(plain, Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (inside.pub.isBlank() || SEP in inside.pub) return null
        // Імʼя під ключем, а не ключ під іменем: інакше власник коду привів би
        // чужий підписний ключ під своїм ключем обміну і зайняв би чужий рядок.
        if (fingerprint(inside.pub) != frame.from) return null
        val pub = runCatching { java.util.Base64.getDecoder().decode(inside.pub) }.getOrNull()
            ?.takeIf { it.size == Ed25519.PUBLIC_KEY_SIZE } ?: return null
        val sig = runCatching { java.util.Base64.getDecoder().decode(inside.sig) }.getOrNull()
            ?: return null
        val ok = runCatching {
            Ed25519.verify(
                pub,
                transcript(echoOf, frame.from, frame.to, frame.dh, inside.pub, frame.n),
                sig,
            )
        }.getOrDefault(false)
        if (!ok) return null
        // Імʼя під підписом — усе одно чуже імʼя: підпис доводить, ХТО його
        // назвав, і нічого не каже про те, що воно з себе являє.
        val name = PeerName.accept(inside.name)
        return Introduction(
            peerId = frame.from,
            name = name.name,
            nameShortened = name.shortened,
            pubKey = inside.pub,
            dhPubKey = frame.dh,
            host = inside.host,
            port = inside.port,
            nonceB64 = frame.n,
        )
    }

    private fun transcript(
        echoOf: String?,
        from: String,
        to: String,
        dh: String,
        pub: String,
        n: String,
    ): ByteArray {
        val head = if (echoOf == null) TAG_OFFER else TAG_BACK
        val parts = listOfNotNull(head, from, to, dh, pub, n, echoOf)
        return parts.joinToString(SEP.toString()).toByteArray(Charsets.UTF_8)
    }

    private fun aad(from: String, to: String, dh: String): ByteArray =
        "$AAD_TAG$SEP$VERSION$SEP$from$SEP$to$SEP$dh".toByteArray(Charsets.UTF_8)

    /**
     * Що ми знаємо про спроби представитись. Тільки швидкоплинне: єдиний
     * засвідчений факт — прийняте знайомство — живе в реєстрі, бо інакше досить
     * було б дочекатись перезапуску, щоб пара знову замовкла.
     */
    data class Known(
        val triedAtMs: Long = 0L,
        val refusedAtMs: Long = 0L,
        val silentInARow: Int = 0,
    )

    enum class Answer { INTRODUCED, REFUSED, SILENT }

    /**
     * Чи винен цей рядок знайомство.
     *
     * [hasDelivered] — чи бодай один наш лист до них уже дійшов. Квитанцію
     * пише лише ключ, якого без нашого рядка в їхньому реєстрі не скласти, тож
     * доставка доводить те саме, що й знайомство. Разом із [PeerIdentity.metAtMs]
     * це й уся міграція: пари, що працювали до цієї збірки, читаються з нулем,
     * але мовчки звільняються — у день оновлення ніхто нікого не засипає.
     *
     * Пристрій пропущено: спарований ПК не має ключа обміну і не співрозмовник,
     * тож знайомство коштувало б кадр щопʼятнадцять хвилин довіку.
     */
    fun owes(peer: PeerIdentity, hasDelivered: Boolean): Boolean =
        peer.kind == PeerIdentity.Kind.PERSON &&
            peer.introducedAtMs == 0L &&
            peer.metAtMs == 0L &&
            peer.dhPubKey.isNotBlank() &&
            !hasDelivered

    /**
     * Драбина мовчання, не годинник: мовчазний телефон коштує таймаут, і пʼять
     * таких на такт — це заряд, витрачений ні на що.
     */
    private val SILENT_BACKOFF_MS = longArrayOf(10_000L, 60_000L, 5 * 60_000L, 30 * 60_000L)

    // «Ще не пробували» читається з лічильника, а не з годинника: нуль — це
    // законна мить, і сентинел на ній зробив би першу тишу невидимою.
    fun shouldOffer(known: Known, nowMs: Long): Boolean = when {
        known.refusedAtMs > 0L -> nowMs - known.refusedAtMs >= RETRY_REFUSAL_MS
        known.silentInARow <= 0 -> true
        else -> {
            // Перша тиша вже порахована, тож сходинку беремо на одну нижче:
            // інакше найкоротша, заради якої драбина й існує, ніколи не грає.
            val step = SILENT_BACKOFF_MS[
                (known.silentInARow - 1).coerceIn(0, SILENT_BACKOFF_MS.lastIndex),
            ]
            nowMs - known.triedAtMs >= step
        }
    }

    fun noted(known: Known, answer: Answer, nowMs: Long): Known = when (answer) {
        Answer.INTRODUCED -> Known()
        Answer.REFUSED -> known.copy(
            triedAtMs = nowMs,
            refusedAtMs = nowMs,
            silentInARow = 0,
        )
        Answer.SILENT -> known.copy(
            triedAtMs = nowMs,
            silentInARow = known.silentInARow + 1,
        )
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
}
