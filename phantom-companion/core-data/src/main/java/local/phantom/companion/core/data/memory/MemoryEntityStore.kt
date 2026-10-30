package local.phantom.companion.core.data.memory

import java.util.UUID

/**
 * Люди й речі памʼяті. Заводить сутність під наявним іменем, а не другу копію,
 * і вміє сказати, кого саме згадали у фразі — разом із тим, як цю людину
 * називають інакше, бо «сестра дзвонила» мусить дістати факти про Марту.
 */
class MemoryEntityStore(private val dao: MemoryEntityDao) {

    data class Mention(val entity: MemoryEntityEntity, val matchedName: String)

    /**
     * Заводить або оновлює сутність. Збіг шукається за основою імені та за
     * всіма псевдонімами — інакше «Марті» завела б другу Марту.
     */
    suspend fun observe(
        name: String,
        kind: String,
        nowMs: Long,
        aliases: List<String> = emptyList(),
        peerId: String? = null,
        label: String? = null,
        note: String = "",
        source: String = "conversation",
    ): String {
        val clean = name.trim()
        if (clean.isEmpty()) return ""
        val key = nameKey(clean)

        val existing = peerId?.let { dao.byPeer(it) }
            ?: dao.byNameKey(key).firstOrNull { it.kind == kind }
            ?: dao.getAll().firstOrNull { e ->
                e.kind == kind && e.allNames.any { FactMention.matches(it, clean) }
            }

        if (existing != null) {
            val merged = (existing.aliases.lineSequence().map { it.trim() } + aliases.map { it.trim() })
                .filter { it.isNotEmpty() && !FactMention.matches(it, existing.name) }
                .distinct()
                .joinToString("\n")
            dao.update(
                existing.copy(
                    aliases = merged,
                    peerId = peerId ?: existing.peerId,
                    label = label ?: existing.label,
                    note = note.ifBlank { existing.note },
                    lastSeenMs = nowMs,
                ),
            )
            return existing.id
        }

        val id = UUID.randomUUID().toString()
        dao.insert(
            MemoryEntityEntity(
                id = id,
                kind = kind,
                name = clean,
                nameKey = key,
                aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"),
                peerId = peerId,
                label = label,
                note = note,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                source = source,
            ),
        )
        return id
    }

    /** Кого й що згадали в цій фразі. */
    suspend fun mentioned(queryTokens: List<String>): List<Mention> {
        if (queryTokens.isEmpty()) return emptyList()
        val out = ArrayList<Mention>()
        for (e in dao.getAll()) {
            val hit = e.allNames.firstOrNull { n ->
                FactMention.tokens(n).any { nameToken -> queryTokens.any { FactMention.matches(nameToken, it) } }
            }
            if (hit != null) out += Mention(e, hit)
        }
        return out
    }

    /**
     * Слова фрази, доповнені іменами згаданих сутностей. Це і є звʼязок: назвав
     * сестру — дістав факти, у яких стоїть «Марта», і навпаки.
     */
    suspend fun expand(queryTokens: List<String>): List<String> {
        val extra = mentioned(queryTokens).flatMap { m -> m.entity.allNames.flatMap { FactMention.tokens(it) } }
        return (queryTokens + extra).distinct()
    }

    suspend fun list(): List<MemoryEntityEntity> = dao.getAll()

    suspend fun byId(id: String): MemoryEntityEntity? = dao.byId(id)

    suspend fun forget(id: String) = dao.deleteById(id)

    suspend fun clear() = dao.clear()

    private fun nameKey(name: String): String =
        FactMention.tokens(name).firstOrNull()?.let { FactMention.stem(it) } ?: name.lowercase()
}
