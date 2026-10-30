package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerPreview
import org.json.JSONObject

/**
 * Поле `link_preview` листа для веба: картка, яку склав відправник (PeerPreview).
 * Мініатюра — data-URI, щоб сторінка нічого не тягнула з мережі сама.
 */
internal object WebMessengerPreview {

    fun json(log: List<PeerMessage>, m: PeerMessage): JSONObject? {
        val p = PeerPreview.of(log, m) ?: return null
        return JSONObject()
            .put("url", p.url)
            .put("title", p.title)
            .put("description", p.description)
            .put("site", p.site)
            .put("image", p.image?.let { "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
    }
}
