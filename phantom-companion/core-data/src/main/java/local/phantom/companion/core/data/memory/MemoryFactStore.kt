package local.phantom.companion.core.data.memory

import java.util.UUID
import kotlin.math.pow

/**
 * Tier 2 long-term memory: consolidate (near-duplicates reinforce, not pile up),
 * recall by semantic ⊕ recency ⊕ reinforcement, forget by confidence decay. The
 * caller supplies int8 embeddings, keeping this JVM-testable with no ONNX.
 */
class MemoryFactStore(private val dao: MemoryFactDao) {

    data class Recalled(
        val text: String,
        val category: String,
        val firstSeenMs: Long,
        val lastConfirmedMs: Long,
        val reinforceCount: Int,
        val score: Float,
        val confirmed: Boolean = false,
        val id: String = "",
    )

    /** Learn or reaffirm a fact; a same-category near-duplicate reinforces instead of duplicating. */
    suspend fun remember(
        text: String,
        category: String,
        source: String,
        embedding: IntArray?,
        nowMs: Long,
        dupThreshold: Float = 0.86f,
        entityId: String? = null,
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val bytes = embedding?.toInt8Bytes() ?: ByteArray(0)

        val peers = dao.getByCategory(category)
        val dup = if (embedding != null) {
            peers.filter { it.embedding.isNotEmpty() }
                .map { it to cosine(embedding, it.embedding) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= dupThreshold }?.first
        } else {
            peers.firstOrNull { it.text.equals(clean, ignoreCase = true) }
        }

        if (dup != null) {
            dao.update(
                dup.copy(
                    text = if (clean.length > dup.text.length) clean else dup.text,
                    embedding = if (bytes.isNotEmpty()) bytes else dup.embedding,
                    confidence = (dup.confidence + REINFORCE_STEP).coerceAtMost(1f),
                    lastConfirmedMs = nowMs,
                    reinforceCount = dup.reinforceCount + 1,
                    // Сказане вголос підвищує здогад до підтвердженого, але
                    // ніколи не знижує вже підтверджене назад.
                    confirmed = if (MemoryFactEntity.statedBySelf(source)) {
                        MemoryFactEntity.CONFIRMED
                    } else {
                        dup.confirmed
                    },
                    entityId = entityId ?: dup.entityId,
                ),
            )
            return dup.id
        }

        val id = UUID.randomUUID().toString()
        dao.insert(
            MemoryFactEntity(
                id = id,
                text = clean,
                category = category,
                embedding = bytes,
                confidence = INITIAL_CONFIDENCE,
                firstSeenMs = nowMs,
                lastConfirmedMs = nowMs,
                reinforceCount = 1,
                source = source,
                confirmed = if (MemoryFactEntity.statedBySelf(source)) {
                    MemoryFactEntity.CONFIRMED
                } else {
                    MemoryFactEntity.NOT_ASKED
                },
                entityId = entityId,
            ),
        )
        return id
    }

    /** Людина каже «так, це правда» — факт стає якорем підказки. */
    suspend fun confirm(id: String) = dao.setConfirmed(id, MemoryFactEntity.CONFIRMED)

    /** Знімає підтвердження: факт лишається, але перестає їхати щоразу. */
    suspend fun unconfirm(id: String) = dao.setConfirmed(id, MemoryFactEntity.NOT_ASKED)

    suspend fun attachTo(factId: String, entityId: String?) = dao.setEntity(factId, entityId)

    suspend fun factsAbout(entityId: String): List<MemoryFactEntity> = dao.byEntity(entityId)

    /** Забути все — «вимкнути памʼять» мусить мати кнопку, яка справді стирає. */
    suspend fun forgetAll() = dao.clear()

    /** Top-[k] facts for a query, fused semantic ⊕ recency ⊕ reinforcement, weighted by decayed confidence. */
    /** Everything held, newest confirmation first — for the memory screen. */
    suspend fun list(limit: Int = 200): List<MemoryFactEntity> =
        dao.recent(limit)

    /**
     * Forget one fact outright. A person must be able to delete a belief PHANTOM
     * formed about him — a wrong fact steers every answer until it is gone.
     */
    suspend fun forget(id: String) = dao.deleteById(id)

    suspend fun recall(
        embedding: IntArray?,
        nowMs: Long,
        k: Int = 6,
        halfLifeDays: Double = 45.0,
    ): List<Recalled> {
        val facts = dao.getAll()
        if (facts.isEmpty()) return emptyList()
        val halfLife = halfLifeDays * DAY_MS
        val hasQuery = embedding != null

        return facts.map { f ->
            val decayedConf = f.confidence * recencyDecay(nowMs - f.lastConfirmedMs, halfLife).toFloat()
            val rec = recencyDecay(nowMs - f.lastConfirmedMs, halfLife).toFloat()
            val rein = 1f - 1f / (1f + f.reinforceCount)
            val sem = if (hasQuery && f.embedding.isNotEmpty()) cosine(embedding!!, f.embedding).coerceIn(0f, 1f) else 0f
            val fused = if (hasQuery)
                0.5f * sem + 0.3f * rec + 0.2f * rein
            else
                0.6f * rec + 0.4f * rein
            Recalled(
                f.text, f.category, f.firstSeenMs, f.lastConfirmedMs, f.reinforceCount,
                fused * decayedConf.coerceAtLeast(0.05f),
                f.confirmed == MemoryFactEntity.CONFIRMED, f.id,
            )
        }.sortedByDescending { it.score }.take(k)
    }

    /**
     * Відбір для підказки: те, що згадали в цій фразі, плюс те, що людина
     * підтвердила.
     *
     * Це не той самий [recall]. Косинус на кирилиці нічого не важить — all-MiniLM
     * її ріже, — тож вагу веде збіг слів ([FactMention]), а вектор лишається
     * слабкою добавкою для латиниці. І головне: факт, якого ніхто не згадав і
     * не підтвердив, у підказку НЕ їде. «Усі факти щоразу» — це не памʼять, а
     * шум, який витісняє з вікна саме питання.
     *
     * [queryTokens] чекає вже розширені іменами сутностей слова
     * ([MemoryEntityStore.expand]), інакше «сестра» не дістане Марту.
     */
    suspend fun recallFor(
        queryTokens: List<String>,
        embedding: IntArray?,
        nowMs: Long,
        k: Int = 6,
        anchorCap: Int = 3,
        halfLifeDays: Double = 45.0,
    ): List<Recalled> {
        if (queryTokens.isEmpty()) return recall(embedding, nowMs, k, halfLifeDays)
        val facts = dao.getAll()
        if (facts.isEmpty()) return emptyList()
        val halfLife = halfLifeDays * DAY_MS

        val scored = facts.map { f ->
            val rec = recencyDecay(nowMs - f.lastConfirmedMs, halfLife).toFloat()
            val rein = 1f - 1f / (1f + f.reinforceCount)
            val sem = if (embedding != null && f.embedding.isNotEmpty()) {
                cosine(embedding, f.embedding).coerceIn(0f, 1f)
            } else {
                0f
            }
            val lex = FactMention.relevance(f.text, queryTokens)
            val decayed = (f.confidence * rec).coerceAtLeast(0.05f)
            val fused = (0.55f * lex + 0.15f * sem + 0.18f * rec + 0.12f * rein) * decayed
            Scored(
                f,
                lex,
                Recalled(
                    f.text, f.category, f.firstSeenMs, f.lastConfirmedMs, f.reinforceCount,
                    fused, f.confirmed == MemoryFactEntity.CONFIRMED, f.id,
                ),
            )
        }

        val anchors = scored
            .filter { it.fact.confirmed == MemoryFactEntity.CONFIRMED }
            .sortedByDescending { it.recalled.score }
            .take(anchorCap)
        val taken = anchors.mapTo(HashSet()) { it.fact.id }
        val mentioned = scored
            .filter { it.lexical > 0f && it.fact.id !in taken }
            .sortedByDescending { it.recalled.score }
            .take((k - anchors.size).coerceAtLeast(0))

        return (anchors + mentioned).sortedByDescending { it.recalled.score }.map { it.recalled }
    }

    private class Scored(val fact: MemoryFactEntity, val lexical: Float, val recalled: Recalled)

    /** Forget to stay small: drop below-[floor] facts, then evict the lowest-retained surplus over [cap]. */
    suspend fun decayAndPrune(
        nowMs: Long,
        cap: Int = 1500,
        floor: Float = 0.12f,
        halfLifeDays: Double = 45.0,
    ): Int {
        val halfLife = halfLifeDays * DAY_MS
        val facts = dao.getAll()
        var removed = 0
        val survivors = ArrayList<Pair<MemoryFactEntity, Float>>(facts.size)
        for (f in facts) {
            val retain = f.confidence * recencyDecay(nowMs - f.lastConfirmedMs, halfLife).toFloat() *
                (1f + f.reinforceCount * 0.05f)
            // Сказане людиною («запамʼятай») не вивітрюється само — лише «забудь».
            if (retain < floor && f.confirmed != MemoryFactEntity.CONFIRMED) {
                dao.deleteById(f.id); removed++
            } else {
                survivors += f to retain
            }
        }
        if (survivors.size > cap) {
            survivors.sortedBy { it.second }.take(survivors.size - cap).forEach {
                dao.deleteById(it.first.id); removed++
            }
        }
        return removed
    }

    private fun recencyDecay(ageMs: Long, halfLifeMs: Double): Double =
        0.5.pow(ageMs.coerceAtLeast(0).toDouble() / halfLifeMs)

    private fun cosine(query: IntArray, fact: ByteArray): Float {
        if (fact.isEmpty() || query.size != fact.size) return 0f
        var dot = 0L; var nq = 0L; var nf = 0L
        for (i in query.indices) {
            val q = query[i].toLong(); val f = fact[i].toLong()
            dot += q * f; nq += q * q; nf += f * f
        }
        val denom = kotlin.math.sqrt(nq.toDouble() * nf.toDouble())
        return if (denom > 0.0) (dot / denom).toFloat().coerceIn(-1f, 1f) else 0f
    }

    private fun IntArray.toInt8Bytes(): ByteArray =
        ByteArray(size) { this[it].coerceIn(-127, 127).toByte() }

    companion object {
        private const val INITIAL_CONFIDENCE = 0.6f
        private const val REINFORCE_STEP = 0.15f
        private const val DAY_MS = 86_400_000.0
    }
}
