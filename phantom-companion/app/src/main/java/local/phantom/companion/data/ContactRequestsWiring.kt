package local.phantom.companion.data

import java.util.UUID
import local.phantom.companion.core.net.peer.ContactRequestBook
import local.phantom.companion.core.net.peer.PeerContact
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.ui.messenger.WebMessengerRequests

/** Запити в контакти: книга в шифрованому сховку, маршрут для веба, прийом кадрів `contact:*`. */
class ContactRequestsWiring(private val container: AppContainer) {

    private val slot by lazy { EncryptedSlot(container.applicationContext, "phantom_contact_requests", durable = true) }

    val book: ContactRequestBook by lazy {
        ContactRequestBook(object : ContactRequestBook.Store {
            override fun read() = slot.read(KEY)
            override fun write(raw: String) = slot.write(KEY, raw)
        })
    }

    internal val route: WebMessengerRequests by lazy {
        WebMessengerRequests(book, object : WebMessengerRequests.Host {
            override fun owner() = container.mesh.selfIdFlow.value
            override fun person(id: String): WebMessengerRequests.Person? =
                container.peers.peers.value.firstOrNull { it.id == id }?.let {
                    WebMessengerRequests.Person(it.name, own = it.kind == PeerIdentity.Kind.DEVICE || it.id == owner())
                }
            override suspend fun myName() = container.profileStore.getActive()?.displayName.orEmpty()
            override fun newId() = UUID.randomUUID().toString()
            override fun send(toId: String, body: String) = container.mesh.send(toId, body) != null
        })
    }

    /** Кличе AppContainer.onPeerMessage. */
    fun absorb(message: PeerMessage) {
        val op = PeerContact.decode(message.body) ?: return
        val me = container.mesh.selfIdFlow.value
        if (me.isBlank() || message.fromId == me) return
        val name = container.peers.peers.value.firstOrNull { it.id == message.fromId }?.name.orEmpty()
        book.absorb(me, message.fromId, name, op)
    }

    private companion object {
        const val KEY = "rows"
    }
}
