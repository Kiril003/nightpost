package local.phantom.companion.data

import android.content.Context
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * «Збережене»: нотатки власника самому собі. Окремий зашифрований файл, а не
 * журнал сітки — у журналу є черга, межа [local.phantom.companion.core.net.peer.PeerOutbox.MAX_PER_PEER]
 * і «ще раз», і кожне з трьох тут було б способом загубити нотатку або
 * відправити її кудись.
 *
 * Кожна зміна лягає на диск до повернення: записів мало, а нотатка, яка є на
 * екрані й зникла після перезапуску, — саме та вада, заради якої це існує.
 */
class SavedNotes internal constructor(private val store: PeerOutboxStore) {

    constructor(context: Context) : this(PeerOutboxStore.savedNotes(context))

    private val lock = Any()
    private var notes: List<PeerMessage> = store.load()

    fun all(): List<PeerMessage> = synchronized(lock) { notes }

    fun find(id: String): PeerMessage? = all().firstOrNull { it.id == id }

    /** Той самий id удруге — той самий лист, а не другий: повтор після збою звичайний. */
    fun add(note: PeerMessage): PeerMessage = change { current ->
        current.firstOrNull { it.id == note.id }?.let { return@change current to it }
        val kept = note.copy(toId = ID, state = PeerMessage.State.DELIVERED)
        current + kept to kept
    }

    /**
     * Чужі за часом (імпорт із веба, старий журнал) стають на своє місце за
     * часом; порядок наявних між собою не міняється.
     */
    fun adopt(incoming: List<PeerMessage>): List<PeerMessage> = change { current ->
        val known = current.mapTo(HashSet()) { it.id }
        val fresh = incoming
            .filter { known.add(it.id) }
            .map { it.copy(toId = ID, state = PeerMessage.State.DELIVERED) }
        if (fresh.isEmpty()) return@change current to emptyList()
        var merged = current
        fresh.sortedBy { it.sentAtMs }.forEach { note ->
            val at = merged.indexOfFirst { it.sentAtMs > note.sentAtMs }.let { if (it < 0) merged.size else it }
            merged = merged.subList(0, at) + note + merged.subList(at, merged.size)
        }
        merged to fresh
    }

    fun edit(id: String, body: String, atMs: Long): PeerMessage? = change { current ->
        val old = current.firstOrNull { it.id == id } ?: return@change current to null
        val new = old.copy(body = body, editedAtMs = atMs)
        current.map { if (it.id == id) new else it } to new
    }

    fun delete(id: String): PeerMessage? = change { current ->
        val old = current.firstOrNull { it.id == id } ?: return@change current to null
        current.filterNot { it.id == id } to old
    }

    fun clear(): Int = change { current -> emptyList<PeerMessage>() to current.size }

    private inline fun <T> change(transform: (List<PeerMessage>) -> Pair<List<PeerMessage>, T>): T =
        synchronized(lock) {
            val (next, result) = transform(notes)
            if (next !== notes) {
                store.save(next)
                notes = next
            }
            result
        }

    companion object {
        /**
         * Той самий id, яким розмову вже називає веб (`openSavedNotes`, фільтр
         * кола, плашка «Сховок») і під яким лежать нотатки в IndexedDB. Новий id
         * осиротив би їх і зажадав би перейменування по обидва боки.
         */
        const val ID = "chat_saved_notes"
    }
}
