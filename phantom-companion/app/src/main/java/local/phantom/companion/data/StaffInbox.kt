package local.phantom.companion.data

import local.phantom.companion.core.net.peer.StaffWire

/**
 * Доручені розмови на телефоні працівника. Лише в памʼяті: D7 не дозволяє
 * лишати їх на диску, тож перезапуск теж їх стирає до наступного пропуску.
 */
class StaffInbox(private val knows: (String) -> Boolean) {
    private val passes = LinkedHashMap<String, StaffWire.Pass>()
    private val revs = HashMap<String, Long>()

    /** true — кадр пропуску (і його треба прибрати з журналу), навіть відкинутий. */
    @Synchronized
    fun absorb(fromId: String, body: String): Boolean {
        if (!StaffWire.isStaff(body)) return false
        val pass = StaffWire.decode(body) ?: return true
        if (!knows(fromId)) return true
        // Відкликання теж тримає rev, інакше запізнілий старий пропуск повернув би розмови.
        if (pass.rev <= (revs[fromId] ?: 0L)) return true
        revs[fromId] = pass.rev
        if (pass.threads.isEmpty()) passes.remove(fromId) else passes[fromId] = pass
        return true
    }

    @Synchronized
    fun rows(): List<Pair<String, StaffWire.Pass>> = passes.map { it.key to it.value }
}
