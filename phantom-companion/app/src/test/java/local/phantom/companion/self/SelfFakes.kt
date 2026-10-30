package local.phantom.companion.self

import local.phantom.companion.core.data.memory.MemoryFactDao
import local.phantom.companion.core.data.memory.MemoryFactEntity

internal class FakeFactDao : MemoryFactDao {
    val rows = LinkedHashMap<String, MemoryFactEntity>()
    override suspend fun insert(fact: MemoryFactEntity) { rows[fact.id] = fact }
    override suspend fun update(fact: MemoryFactEntity) { rows[fact.id] = fact }
    override suspend fun getAll() = rows.values.toList()
    override suspend fun getByCategory(category: String) = rows.values.filter { it.category == category }
    override suspend fun byEntity(entityId: String) = rows.values.filter { it.entityId == entityId }
    override suspend fun recent(limit: Int) = rows.values.sortedByDescending { it.lastConfirmedMs }.take(limit)
    override suspend fun count() = rows.size
    override suspend fun setConfirmed(id: String, confirmed: Int) {
        rows[id]?.let { rows[id] = it.copy(confirmed = confirmed) }
    }
    override suspend fun setEntity(id: String, entityId: String?) {
        rows[id]?.let { rows[id] = it.copy(entityId = entityId) }
    }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun deleteByEntity(entityId: String) {
        rows.values.filter { it.entityId == entityId }.forEach { rows.remove(it.id) }
    }
    override suspend fun pruneBelow(floor: Float): Int {
        val gone = rows.values.filter { it.confidence < floor }.map { it.id }
        gone.forEach { rows.remove(it) }
        return gone.size
    }
    override suspend fun clear() { rows.clear() }
}

internal class FakeTutorState : CSharpTutor.TutorState {
    override var step = CSharpTutor.Step.IDLE
    override var diagIndex = 0
    override var topic: String? = null
    override var reminderId: String? = null
    override var diagnosed = false
    val marks = LinkedHashMap<String, Long>()
    override fun shaky(): Map<String, Long> = marks
    override fun mark(topic: String, shakyAtMs: Long?) { marks[topic] = shakyAtMs ?: 0L }
}

internal class FakeReminders : CSharpTutor.Reminders {
    val live = LinkedHashMap<String, Pair<Say, Long>>()
    val whys = LinkedHashMap<String, Say>()
    private var n = 0
    override suspend fun set(text: Say, why: Say, atMs: Long): String =
        "r${++n}".also { live[it] = text to atMs; whys[it] = why }
    override suspend fun cancel(id: String) { live.remove(id) }
}

internal fun plain(say: Say): String =
    if (say.line == Line.RAW) say.args.joinToString("") else say.line.name + say.args.joinToString(",", "(", ")")

internal class MemoryInitiativeStore : Initiative.Store {
    var s = Initiative.State()
    override fun load() = s
    override fun save(state: Initiative.State) { s = state }
}
