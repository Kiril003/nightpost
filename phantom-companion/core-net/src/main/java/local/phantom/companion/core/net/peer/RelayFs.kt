package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.AesGcm
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Конверт v5 «PHF» — пряма секретність для листа сплячому (docs/crypto/RELAY_FS.md).
 *
 * Кожен лист — окремий X3DH: свіжий EK відправника, підписаний SPK і
 * одноразовий OPK отримувача. Вивід секрету й підпис SPK — байт у байт як в
 * X3DH ПК (`messenger/crypto/x3dh.py`, `keys.py`); еталон — `node/relay_fs.py`,
 * звіряє спільний вектор `relay_fs_vector.json`.
 *
 * Тут лише криптографія і кадри. Що стирати і коли — [RelayFsKeys].
 * Надсилати v5 — лише з увімкненим [Flag] (типово вимкнено).
 */
object RelayFs {

    /** Прапорець надсилання v5. Читати v5 можна завжди — старі збірки його просто не шлють. */
    object Flag {
        @Volatile var enabled: Boolean = false
    }

    const val VERSION = 5
    const val TAG = "PHF"
    private const val SEP = '\u001f'
    const val NO_OPK: Long = 0xFFFF_FFFFL
    private const val KEY_LEN = 32
    private const val IV_LEN = 12

    val X3DH_INFO: ByteArray = "PHANTOM OS/messenger-x3dh-v1".toByteArray(Charsets.UTF_8)
    val LETTER_INFO: ByteArray = "PHANTOM OS/relay-fs-letter-v1".toByteArray(Charsets.UTF_8)
    val SIGNED_PREKEY_CONTEXT: ByteArray = "PHANTOM OS/messenger-signed-prekey-v1".toByteArray(Charsets.UTF_8)

    /** Службовий кадр із пачкою prekey; їде всередині звичайного конверта пари. */
    const val BUNDLE_TAG = "PH-PKB:"

    data class Identity(val ed: ByteArray, val dh: ByteArray)

    /** kotlinx не пише типових значень: `opk = -1` у кадрі відсутній, як у Python. */
    @Serializable
    data class Frame(
        val v: Int,
        val from: String,
        val to: String,
        val ek: String,
        val spk: Long,
        val opk: Long = -1,
        val n: String,
        val ct: String,
    )

    data class Opened(val plaintext: ByteArray, val opkId: Long?, val ek: ByteArray, val spkId: Long, val nonceB64: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val B64 = Base64.getEncoder().withoutPadding()

    private fun unb64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    // ── X3DH (як на ПК) ──────────────────────────────────────────────────────

    fun x3dhSecret(chunks: List<ByteArray>): ByteArray =
        Hkdf.derive(
            ikm = ByteArray(KEY_LEN) { 0xFF.toByte() } + chunks.reduce { acc, b -> acc + b },
            salt = ByteArray(32),
            info = X3DH_INFO,
            length = KEY_LEN,
        )

    fun letterMaterial(secret: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.derive(ikm = secret, salt = ByteArray(32), info = LETTER_INFO, length = KEY_LEN + IV_LEN)
        return out.copyOfRange(0, KEY_LEN) to out.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)
    }

    fun aad(from: String, to: String, sender: Identity, recipient: Identity, ek: ByteArray, spkId: Long, opkId: Long?): ByteArray {
        val ids = ByteBuffer.allocate(8)
            .putInt((spkId and 0xFFFF_FFFFL).toInt())
            .putInt(((opkId ?: NO_OPK) and 0xFFFF_FFFFL).toInt())
            .array()
        return "$TAG$SEP$from$SEP$to$SEP".toByteArray(Charsets.UTF_8) +
            sender.ed + sender.dh + recipient.ed + recipient.dh + ek + ids
    }

    fun signedPrekeyPayload(id: Long, pub: ByteArray): ByteArray =
        SIGNED_PREKEY_CONTEXT + ByteBuffer.allocate(4).putInt((id and 0xFFFF_FFFFL).toInt()).array() + pub

    fun signSpk(edPrivate: ByteArray, id: Long, pub: ByteArray): ByteArray = Ed25519.sign(edPrivate, signedPrekeyPayload(id, pub))

    fun verifySpk(edPub: ByteArray, id: Long, pub: ByteArray, sig: ByteArray): Boolean =
        runCatching { Ed25519.verify(edPub, signedPrekeyPayload(id, pub), sig) }.getOrDefault(false)

    // ── лист ────────────────────────────────────────────────────────────────

    /**
     * Бік відправника. [ephemeralPrivate] передають лише вектори — у житті EK свіжий
     * щоразу і після цього виклику не існує ніде.
     */
    fun seal(
        plaintext: ByteArray,
        from: String,
        to: String,
        sender: Identity,
        senderDhPrivate: ByteArray,
        recipient: Identity,
        spkId: Long,
        spkPub: ByteArray,
        opkId: Long?,
        opkPub: ByteArray?,
        ephemeralPrivate: ByteArray? = null,
    ): String? {
        if (SEP in from || SEP in to || from.isBlank() || to.isBlank()) return null
        if ((opkId == null) != (opkPub == null)) return null
        val ek = ephemeralPrivate ?: X25519.generateKeypair().privateKey
        try {
            val ekPub = X25519.publicKeyFromPrivate(ek)
            val chunks = mutableListOf(
                X25519.ecdh(senderDhPrivate, spkPub),
                X25519.ecdh(ek, recipient.dh),
                X25519.ecdh(ek, spkPub),
            )
            if (opkPub != null) chunks += X25519.ecdh(ek, opkPub)
            val (key, iv) = letterMaterial(x3dhSecret(chunks))
            val ct = AesGcm.encrypt(key, plaintext, iv, aad(from, to, sender, recipient, ekPub, spkId, opkId))
            key.fill(0)
            return json.encodeToString(
                Frame.serializer(),
                Frame(VERSION, from, to, B64.encodeToString(ekPub), spkId, opkId ?: -1, B64.encodeToString(iv), B64.encodeToString(ct)),
            )
        } catch (_: Exception) {
            return null
        } finally {
            if (ephemeralPrivate == null) ek.fill(0)
        }
    }

    fun parse(frame: String): Frame? {
        val f = runCatching { json.decodeFromString(Frame.serializer(), frame) }.getOrNull() ?: return null
        if (f.v != VERSION || f.from.isBlank() || f.to.isBlank() || SEP in f.from || SEP in f.to) return null
        if (f.spk !in 0..NO_OPK - 1 || f.opk < -1 || f.opk >= NO_OPK) return null
        return f
    }

    /**
     * Бік отримувача. Ключі приходять функціями, бо стирати їх — справа
     * [RelayFsKeys], і лише ПІСЛЯ того, як цей виклик повернув текст.
     */
    fun open(
        frame: String,
        me: Identity,
        myDhPrivate: ByteArray,
        sender: Identity,
        spkPrivate: (Long) -> ByteArray?,
        opkPrivate: (Long) -> ByteArray?,
    ): Opened? {
        val f = parse(frame) ?: return null
        val ek = unb64(f.ek)?.takeIf { it.size == KEY_LEN } ?: return null
        val iv = unb64(f.n)?.takeIf { it.size == IV_LEN } ?: return null
        val ct = unb64(f.ct) ?: return null
        val opkId = f.opk.takeIf { it >= 0 }
        val spk = spkPrivate(f.spk) ?: return null
        val opk = if (opkId != null) opkPrivate(opkId) ?: return null else null
        return try {
            val chunks = mutableListOf(X25519.ecdh(spk, sender.dh), X25519.ecdh(myDhPrivate, ek), X25519.ecdh(spk, ek))
            if (opk != null) chunks += X25519.ecdh(opk, ek)
            val (key, wantIv) = letterMaterial(x3dhSecret(chunks))
            if (!wantIv.contentEquals(iv)) return null
            val plain = AesGcm.decrypt(key, ct, iv, aad(f.from, f.to, sender, me, ek, f.spk, opkId))
            key.fill(0)
            Opened(plain, opkId, ek, f.spk, f.n)
        } catch (_: Exception) {
            null
        }
    }

    // ── пачка prekey ────────────────────────────────────────────────────────

    @Serializable
    data class SignedPrekey(val id: Long, val pub: String, val sig: String)

    @Serializable
    data class OneTime(val id: Long, val pub: String)

    @Serializable
    data class Bundle(val spk: SignedPrekey, val opks: List<OneTime> = emptyList(), val gen: Long = 0)

    fun isBundle(body: String): Boolean = body.startsWith(BUNDLE_TAG)

    fun encodeBundle(bundle: Bundle): String = BUNDLE_TAG + json.encodeToString(Bundle.serializer(), bundle)

    /** Пачка, підпис SPK якої зійшовся під Ed25519 відправника, або null. */
    fun decodeBundle(body: String, senderEd: ByteArray): Bundle? {
        if (!isBundle(body)) return null
        val b = runCatching { json.decodeFromString(Bundle.serializer(), body.removePrefix(BUNDLE_TAG)) }.getOrNull() ?: return null
        val pub = unb64(b.spk.pub)?.takeIf { it.size == KEY_LEN } ?: return null
        val sig = unb64(b.spk.sig) ?: return null
        if (!verifySpk(senderEd, b.spk.id, pub, sig)) return null
        if (b.opks.size > RelayFsKeys.MAX_OPKS_PER_PEER) return null
        if (b.opks.any { (unb64(it.pub)?.size ?: 0) != KEY_LEN }) return null
        return b
    }
}
