package local.phantom.companion.data.linkpreview

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerPreview

/**
 * Картка посилання слідом за листом (див. [PeerPreview]).
 *
 * Не складається, коли людина вимкнула це в Приватності і коли дорога вузька
 * (`PeerMesh.linkNarrow`): на слабкому звʼязку кожен кілобайт — чиєсь слово,
 * що чекає. Звуження посеред читання сторінки теж зупиняє картку.
 */
class LinkPreviews(
    private val enabled: () -> Boolean,
    private val narrow: () -> Boolean,
    private val fetch: suspend (url: String) -> PeerPreview.Preview?,
    private val send: (toId: String, body: String) -> PeerMessage?,
) {

    /** true — картка поїхала. */
    suspend fun follow(toId: String, sent: PeerMessage): Boolean {
        if (!enabled() || narrow()) return false
        if (PeerFrames.kindOf(sent.body) != null) return false
        val url = PeerPreview.firstUrl(sent.body) ?: return false
        val preview = runCatching { fetch(url) }.getOrNull() ?: return false
        if (!enabled() || narrow()) return false
        val body = PeerPreview.encode(sent.id, preview) ?: return false
        return send(toId, body) != null
    }

    fun launch(scope: CoroutineScope, toId: String, sent: PeerMessage) {
        scope.launch(Dispatchers.IO) {
            runCatching { follow(toId, sent) }.onFailure { Log.w(TAG, "картку посилання не складено", it) }
        }
    }

    private companion object {
        const val TAG = "LinkPreviews"
    }
}
