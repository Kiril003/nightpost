package local.phantom.companion.data

import java.io.File
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerProfile

/**
 * Петля банера їде на прохання, а не всім наперед.
 *
 * Глядач відкрив профіль → [ask] шле власнику `PH-PBQ:<blob>`. Власник у [serve]
 * відповідає шматками [PeerFile] лише контакту і лише на свій поточний блоб;
 * глядач складає їх тим самим [PeerFileStore], звіряючи з SHA-256 з опису
 * банера ([manifest]).
 */
class BannerVideoPull(
    private val files: PeerFileStore,
    private val own: (peerId: String) -> Pair<PeerProfile.BannerVideo, File>?,
    private val specOf: (peerId: String) -> String?,
    private val specs: () -> Collection<String?>,
    private val mayServe: (peerId: String) -> Boolean,
    private val send: (peerId: String, body: String) -> Boolean,
    private val sendMany: (peerId: String, bodies: List<String>) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    enum class State { NONE, READY, ASKED }

    private val served = HashMap<String, Long>()
    private val asked = HashMap<String, Long>()

    fun videoOf(peerId: String): PeerProfile.BannerVideo? =
        specOf(peerId)?.let { PeerProfile.Banner.parse(it) }?.video

    fun isReady(video: PeerProfile.BannerVideo): Boolean = files.blob(video.blobId) != null

    @Synchronized
    fun ask(peerId: String): State {
        val v = videoOf(peerId) ?: return State.NONE
        if (isReady(v)) return State.READY
        val key = "$peerId/${v.blobId}"
        val at = asked[key]
        if (at != null && nowMs() - at < ASK_AGAIN_MS) return State.ASKED
        val frame = PeerBanner.pull(v.blobId) ?: return State.NONE
        if (send(peerId, frame)) asked[key] = nowMs()
        return State.ASKED
    }

    /** True — прохання наше і відповідь пішла. */
    @Synchronized
    fun serve(message: PeerMessage): Boolean {
        val blobId = PeerBanner.pulled(message.body) ?: return false
        if (!mayServe(message.fromId)) return false
        val (v, file) = own(message.fromId) ?: return false
        if (v.blobId != blobId) return false
        val key = "${message.fromId}/$blobId"
        val at = served[key]
        if (at != null && nowMs() - at < SERVE_AGAIN_MS) return false
        if (!files.shelve(blobId, file)) return false
        val bodies = files.bodiesFor(attachmentOf(v))
        if (bodies.isEmpty()) return false
        sendMany(message.fromId, bodies)
        served[key] = nowMs()
        return true
    }

    /** Опис для складання: петля з банера будь-кого з контактів. */
    fun manifest(blobId: String): PeerFile.Attachment? =
        specs().asSequence()
            .mapNotNull { s -> s?.let { PeerProfile.Banner.parse(it) }?.video }
            .firstOrNull { it.blobId == blobId }
            ?.let(::attachmentOf)

    private fun attachmentOf(v: PeerProfile.BannerVideo) =
        PeerFile.Attachment(v.blobId, "banner", "video/mp4", v.size, v.sha256, "")

    private companion object {
        const val ASK_AGAIN_MS = 2 * 60_000L
        const val SERVE_AGAIN_MS = 10 * 60_000L
    }
}
