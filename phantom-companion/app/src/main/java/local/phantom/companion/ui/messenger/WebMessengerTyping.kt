package local.phantom.companion.ui.messenger

import kotlinx.coroutines.launch
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerTyping

/**
 * «Друкує…» між вебом і сіткою. Живе за тим самим перемикачем, що й звіти
 * про прочитання: вимкнув — не шлеш і не бачиш.
 */
internal class WebMessengerTyping(
    private val me: () -> String,
    private val enabled: () -> Boolean,
    private val group: (conversationId: String) -> PeerGroup.Group?,
    /** Не заблокований і має ключ. */
    private val canSend: (peerId: String) -> Boolean,
    private val nameOf: (peerId: String) -> String?,
    private val send: (toId: String, body: String) -> Unit,
) {

    data class Seen(val chatId: String, val name: String, val on: Boolean)

    /** Тіло мосту `{on}`; зіпсоване — «перестав». */
    fun signal(conversationId: String, body: String?): Int =
        signal(conversationId, body?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }?.optBoolean("on", false) ?: false)

    /** Скільком пішов кадр. */
    fun signal(conversationId: String, on: Boolean): Int {
        if (!enabled()) return 0
        val g = group(conversationId)
        val body = PeerTyping.encode(PeerTyping.Typing(on, g?.id.orEmpty())) ?: return 0
        val to = g?.memberIds?.filter { it != me() } ?: listOf(conversationId)
        return to.filter(canSend).onEach { send(it, body) }.size
    }

    fun seen(message: PeerMessage): Seen? {
        if (!enabled()) return null
        val t = PeerTyping.decode(message.body) ?: return null
        val name = nameOf(message.fromId) ?: return null
        if (t.groupId.isEmpty()) return Seen(message.fromId, name, t.on)
        val g = group(t.groupId)?.takeIf { message.fromId in it.memberIds } ?: return null
        return Seen(g.id, name, t.on)
    }
}

internal fun webTyping(container: local.phantom.companion.data.AppContainer) = WebMessengerTyping(
    me = { container.mesh.selfIdFlow.value },
    enabled = { container.readReceipts.enabled.value },
    group = { WebMessengerGroups.find(container.mesh.outbox.value, it) },
    canSend = { id ->
        !container.peerBlocks.blocks(id) &&
            container.peers.peers.value.any { it.id == id && it.dhPubKey.isNotBlank() }
    },
    nameOf = { id -> container.peers.peers.value.firstOrNull { it.id == id }?.name },
    send = { to, body ->
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) { container.mesh.sendTyping(to, body) }
    },
)
