package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerIdentity

/**
 * Чиї @ніки ми бачили підтвердженими довідником.
 *
 * Заява з кадру профілю — лише слово людини; нік показується тоді, коли запис
 * у довіднику під цим ніком підписаний тим самим ключем. Інакше будь-хто
 * назвався б чужим ніком.
 */
class PeerHandleBook(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {

    sealed interface Owner {
        data class Key(val peerId: String) : Owner
        data object Nobody : Owner
        /** Мережі чи довідника нема — нічого не міняємо. */
        data object Unknown : Owner
    }

    private data class Entry(val handle: String, val checkedAtMs: Long)

    private val entries: MutableMap<String, Entry> = parse(read()).toMutableMap()

    @Synchronized
    fun shown(peer: PeerIdentity): String? {
        // @нік звірено для старого ключа; після зміни ключа він нічого не доводить.
        if (peer.keyAlarm) return null
        val entry = entries[peer.id] ?: return null
        val claim = peer.claimedHandle ?: return entry.handle
        return entry.handle.takeIf { it == claim }
    }

    @Synchronized
    fun due(peer: PeerIdentity, nowMs: Long): String? {
        val claim = peer.claimedHandle?.takeIf { it.isNotEmpty() }
        if (claim == null) {
            if (peer.claimedHandle == "" && entries.remove(peer.id) != null) save()
            return null
        }
        val entry = entries[peer.id]
        return claim.takeIf { entry == null || entry.handle != claim || nowMs - entry.checkedAtMs > RECHECK_MS }
    }

    @Synchronized
    fun settle(peer: PeerIdentity, handle: String, owner: Owner, nowMs: Long) {
        when {
            owner is Owner.Key && owner.peerId == peer.id -> remember(peer.id, handle, nowMs)
            owner == Owner.Unknown -> Unit
            entries[peer.id]?.handle == handle -> {
                entries.remove(peer.id)
                save()
            }
        }
    }

    @Synchronized
    fun all(): Map<String, String> = entries.mapValues { it.value.handle }

    @Synchronized
    fun remember(peerId: String, handle: String, nowMs: Long) {
        // Один нік — один ключ: довідник не віддає його двом.
        entries.entries.removeAll { it.key != peerId && it.value.handle == handle }
        entries[peerId] = Entry(handle, nowMs)
        save()
    }

    private fun save() = write(entries.entries.joinToString("\n") { "${it.key}\t${it.value.handle}\t${it.value.checkedAtMs}" })

    companion object {
        const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000

        fun open(context: android.content.Context): PeerHandleBook {
            val prefs = context.getSharedPreferences("phantom_peer_handles_v1", android.content.Context.MODE_PRIVATE)
            return PeerHandleBook({ prefs.getString("book", null) }, { prefs.edit().putString("book", it).apply() })
        }

        private fun parse(raw: String?): Map<String, Entry> =
            raw.orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
                parts[0] to Entry(parts[1], parts[2].toLongOrNull() ?: 0L)
            }.toMap()
    }
}
