package local.phantom.companion.data.disappear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import local.phantom.companion.core.net.peer.PeerDisappear
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.EncryptedSlot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Зникаючі повідомлення на телефоні ([PeerDisappear]): строк розмови, мітки
 * прочитання і щохвилинне стирання того, чий строк минув.
 *
 * Мітки прочитання — годинник ЧИТАЧА: «межа прочитаного зрушила до X о Y».
 * Без них «від прочитання» не було б від чого рахувати. Лежать у
 * зашифрованому файлі, по [PeerDisappear.MAX_MARKS] на розмову.
 */
class DisappearWiring(private val container: AppContainer) {

    /** Стерти — і рядок, і байти вкладення на диску. Виноситься, щоб довести без Android. */
    interface Sink {
        fun log(): List<PeerMessage>
        fun me(): String
        fun peers(): List<String>
        fun delete(line: PeerMessage)
        fun dropBytes(peerId: String, blobId: String)
    }

    @Serializable
    private data class Mark(val upto: Long, val at: Long)

    private val slot by lazy { EncryptedSlot(container.applicationContext, FILE_NAME, durable = true) }
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), ListSerializer(Mark.serializer()))
    private val lock = Any()
    private var marks: Map<String, List<Mark>>? = null

    private fun all(): Map<String, List<Mark>> = synchronized(lock) {
        marks ?: (slot.read(KEY)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()).also { marks = it }
    }

    fun reads(peerId: String): List<PeerDisappear.ReadMark> = all()[peerId].orEmpty().map { PeerDisappear.ReadMark(it.upto, it.at) }

    /** Межа прочитаного розмови з [peerId] зрушила до [uptoMs] — запамʼятати, КОЛИ. */
    fun noteRead(peerId: String, uptoMs: Long, atMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val before = reads(peerId)
        val after = PeerDisappear.noteRead(before, uptoMs, atMs)
        if (after === before) return@synchronized
        val next = all() + (peerId to after.map { Mark(it.uptoMs, it.atMs) })
        marks = next
        slot.write(KEY, json.encodeToString(ser, next))
    }

    fun current(peerId: String): PeerDisappear.Setting? =
        PeerDisappear.current(container.mesh.outbox.value, container.mesh.selfIdFlow.value, peerId)

    /** Поставити строк розмови: кадр їде співрозмовнику й лягає в обидві стрічки. */
    fun setTimer(peerId: String, seconds: Long, nowMs: Long = System.currentTimeMillis()): PeerMessage? {
        val body = PeerDisappear.encode(seconds, nowMs) ?: return null
        return container.mesh.send(peerId, body)
    }

    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        container.peers.onRead = { id, upto -> noteRead(id, upto) }
        val sink = object : Sink {
            override fun log() = container.mesh.outbox.value
            override fun me() = container.mesh.selfIdFlow.value
            override fun peers() = container.peers.peers.value.map { it.id }
            override fun delete(line: PeerMessage) { container.mesh.deleteLine(line) }
            override fun dropBytes(peerId: String, blobId: String) { container.peerFiles.drop(peerId, blobId) }
        }
        scope.launch {
            while (isActive) {
                runCatching { sweep(sink, ::reads, System.currentTimeMillis()) }
                delay(SWEEP_MS)
            }
        }
    }

    companion object {
        private const val FILE_NAME = "phantom_disappear"
        private const val KEY = "reads"
        const val SWEEP_MS = 60_000L

        /** Один прохід: усе, чий строк минув, — геть, разом із байтами вкладень. Повертає скільки. */
        fun sweep(sink: Sink, reads: (String) -> List<PeerDisappear.ReadMark>, nowMs: Long): Int {
            val me = sink.me()
            if (me.isBlank()) return 0
            val log = sink.log()
            var gone = 0
            for (peer in sink.peers()) {
                for (line in PeerDisappear.expired(log, me, peer, reads(peer), nowMs)) {
                    PeerFile.describe(line.body)?.let { sink.dropBytes(peer, it.blobId) }
                    sink.delete(line)
                    gone++
                }
            }
            return gone
        }
    }
}
