package local.phantom.companion.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.BuildConfig
import local.phantom.companion.core.net.crypto.hexToBytes
import local.phantom.companion.core.net.peer.MomentBook
import local.phantom.companion.core.net.peer.MomentWire
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerMoment
import local.phantom.companion.ui.messenger.WebMessengerMoments

/** Моменти на телефоні: книга [MomentBook] у сховку, міст для веба, прийом кадрів і щохвилинне прибирання. */
class MomentsWiring(private val container: AppContainer) {

    interface Sink {
        fun log(): List<PeerMessage>
        fun me(): String
        fun delete(line: PeerMessage)
        fun dropBytes(conversationId: String, blobId: String)
    }

    private val slot by lazy { EncryptedSlot(container.applicationContext, FILE_NAME, durable = true) }
    private val seenSer = MapSerializer(String.serializer(), Long.serializer())
    private val idsSer = SetSerializer(String.serializer())
    private val lock = Any()
    private var seen: Map<String, Long>? = null
    private var book: MomentBook? = null

    private fun seenMap(): Map<String, Long> = synchronized(lock) {
        seen ?: (slot.read(KEY)?.let { runCatching { Json.decodeFromString(seenSer, it) }.getOrNull() } ?: emptyMap()).also { seen = it }
    }

    private fun markSeen(id: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val next = seenMap().filterValues { it > now } + (id to now + SEEN_KEEP_MS)
        seen = next
        slot.write(KEY, Json.encodeToString(seenSer, next))
    }

    private fun loadBook(): MomentBook = synchronized(lock) { book ?: MomentBook.decode(slot.read(BOOK)).also { book = it } }

    private fun saveBook(next: MomentBook) = synchronized(lock) {
        book = next
        slot.write(BOOK, next.encode())
    }

    val route: WebMessengerMoments by lazy {
        WebMessengerMoments(object : WebMessengerMoments.Host {
            override fun me() = container.mesh.selfIdFlow.value
            override fun people(): List<WebMessengerMoments.Person> {
                val blocked = container.peerBlocks.blocked.value
                return container.peers.peers.value
                    .filter { it.kind == PeerIdentity.Kind.PERSON && it.dhPubKey.isNotBlank() && it.id !in blocked }
                    .map { WebMessengerMoments.Person(it.id, it.name) }
            }
            override fun book() = loadBook()
            override fun save(book: MomentBook) = saveBook(book)
            override fun reaches(peerId: String) = container.profilesMirror.speaksAs(peerId)
            override fun send(toId: String, body: String) = container.mesh.send(toId, body)
            override fun sendBytes(toId: String, attachment: PeerFile.Attachment) {
                container.mesh.sendMany(toId, container.peerFiles.bodiesFor(attachment))
            }
            override fun assemble(fromId: String, media: JsonObject) {
                container.peerFiles.absorb(
                    PeerMessage(id = "", fromId = fromId, toId = me(), body = media.toString(), sentAtMs = 0L),
                    me(),
                )
            }
            override fun state(conversationId: String, attachment: PeerFile.Attachment) =
                container.peerFiles.fate(conversationId, attachment).name.lowercase()
            override fun dropBytes(conversationId: String, blobId: String) { container.peerFiles.drop(conversationId, blobId) }
            override fun log() = container.mesh.outbox.value
            override fun forget(ids: Set<String>) = container.mesh.forgetLive(ids)
            override fun seen(): Set<String> {
                val now = System.currentTimeMillis()
                return seenMap().filterValues { it > now }.keys
            }
            override fun markSeen(id: String) = this@MomentsWiring.markSeen(id)
            override fun prefs(): String? = slot.read(PREFS)
            override fun savePrefs(raw: String) = slot.write(PREFS, raw)
            override fun muted(): Set<String> =
                slot.read(MUTED)?.let { runCatching { Json.decodeFromString(idsSer, it) }.getOrNull() } ?: emptySet()
            override fun setMuted(ids: Set<String>) = slot.write(MUTED, Json.encodeToString(idsSer, ids))
            override fun trace(line: String) {
                if (BuildConfig.DEBUG) Log.i(TRACE_TAG, line)
            }
        })
    }

    /** Кадр приїхав — у книгу. Кличе AppContainer.onPeerMessage. */
    fun absorb(message: PeerMessage) {
        if (MomentWire.isMoment(message.body)) route.absorb(message)
    }

    fun manifest(blobId: String): PeerFile.Attachment? = route.manifest(blobId)

    /** Для профілю: «залишені» моменти автора, свіжі першими. */
    fun highlights(authorId: String): List<MomentWire.Moment> = route.highlights(authorId)

    fun liveMoments(authorId: String): List<MomentWire.Moment> = route.liveMoments(authorId)

    /** Розшифровані байти першого фото моменту, або null — тексту, відео чи ще не приїхало. */
    fun cover(m: MomentWire.Moment): ByteArray? {
        val media = m.media.firstOrNull { (it["mime"] as? JsonPrimitive)?.content?.startsWith("image/") == true } ?: return null
        return image(media)
    }

    /** Розшифровані байти фото за його описом, або null — ще не приїхало. */
    fun image(media: JsonObject): ByteArray? {
        val att = PeerFile.describe(media.toString()) ?: return null
        val sealed = container.peerFiles.bytes(att.blobId) ?: return null
        return open(sealed, media)
    }

    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val sink = object : Sink {
            override fun log() = container.mesh.outbox.value
            override fun me() = container.mesh.selfIdFlow.value
            override fun delete(line: PeerMessage) { container.mesh.deleteLine(line) }
            override fun dropBytes(conversationId: String, blobId: String) { container.peerFiles.drop(conversationId, blobId) }
        }
        scope.launch {
            while (isActive) {
                runCatching { sweep(sink, System.currentTimeMillis()) }
                runCatching { route.sweep() }
                delay(SWEEP_MS)
            }
        }
    }

    companion object {
        private const val FILE_NAME = "phantom_moments"
        private const val KEY = "seen"
        private const val BOOK = "book"
        private const val PREFS = "prefs"
        private const val MUTED = "muted"
        private const val SEEN_KEEP_MS = 8 * MomentWire.DAY_MS
        const val TRACE_TAG = "PhantomMoment"

        /** AES-GCM, як шифрує веб (`encryptForUpload`): тег у хвості шифротексту. */
        fun open(sealed: ByteArray, media: JsonObject): ByteArray? = runCatching {
            val key = hexToBytes((media["key_hex"] as JsonPrimitive).content)
            val nonce = hexToBytes((media["nonce_hex"] as JsonPrimitive).content)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            c.doFinal(sealed)
        }.getOrNull()
        const val SWEEP_MS = 60_000L

        fun sweep(sink: Sink, nowMs: Long): Int {
            val me = sink.me()
            if (me.isBlank()) return 0
            val gone = PeerMoment.expired(sink.log(), nowMs)
            for (line in gone) {
                PeerFile.describe(line.body)?.let { att ->
                    if (line.fromId == me) {
                        sink.dropBytes(line.toId, att.blobId)
                        sink.dropBytes(WebMessengerMoments.OWN_DIR, att.blobId)
                    } else {
                        sink.dropBytes(line.fromId, att.blobId)
                    }
                }
                sink.delete(line)
            }
            return gone.size
        }
    }
}
