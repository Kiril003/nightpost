package local.phantom.companion.core.data.lifelog

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.ai.OnnxEmbeddingHelper
import java.util.UUID

/**
 * Phase 7-E: Life Log Archive.
 *
 * Implements a multi-decade audio/visual/contextual archive.
 * Uses a local vector store (Room + cosine similarity) to allow the Familiar
 * to remember details from years ago.
 */
class LifeLogArchive(
    private val lifeLogDao: LifeLogDao,
    private val embeddingHelper: OnnxEmbeddingHelper
) {

    /**
     * Embeds and archives an event to the local database.
     */
    suspend fun archiveEvent(eventType: String, eventData: String, vector: FloatArray? = null, tags: String? = null) = withContext(Dispatchers.IO) {
        // QA-fix 2026-07-17 — never log eventData itself: it can carry
        // the full accessibility text of a FOREIGN app's screen, and
        // Log.i put that into logcat for any `adb logcat` to read.
        Log.d("LifeLog", "archive [$eventType] len=${eventData.length}")
        val embedding = vector ?: embeddingHelper.getEmbedding(eventData)
        val entry = LifeLogEntryEntity(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            eventType = eventType,
            eventData = eventData,
            embeddingBlob = embedding,
            tags = tags
        )
        lifeLogDao.insert(entry)
    }

    /**
     * QA-fix 2026-07-17 — one-time flush of firehose rows (the archive
     * seam used to store EVERY ScreenSnapshot, ~1/s, own screen
     * included; those rows poison [queryMemory]'s full-table cosine
     * scan). Returns the number of rows deleted.
     */
    suspend fun purgeType(eventType: String): Int = withContext(Dispatchers.IO) {
        lifeLogDao.deleteByType(eventType)
    }

    /**
     * Query memory using a raw FloatArray query vector.
     * Calculates cosine similarity of all records and returns sorted results.
     */
    suspend fun queryMemory(queryVector: FloatArray, limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        Log.d("LifeLog", "Searching life archive for relevant memories using query vector...")
        val all = lifeLogDao.getAllEntries()
        all.map { entry ->
            val score = cosineSimilarity(queryVector, entry.embeddingBlob)
            entry to score
        }
            .filter { it.second > 0.0f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { "[${it.first.eventType}] ${it.first.eventData} (similarity: ${it.second})" }
    }

    /**
     * Query memory using a text query.
     */
    suspend fun searchMemories(queryText: String, limit: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val queryVector = embeddingHelper.getEmbedding(queryText)
        queryMemory(queryVector, limit)
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0f
        var n1 = 0f
        var n2 = 0f
        for (i in v1.indices) {
            val val1 = v1[i]
            val val2 = v2[i]
            dot += val1 * val2
            n1 += val1 * val1
            n2 += val2 * val2
        }
        val denom = kotlin.math.sqrt(n1.toDouble() * n2.toDouble()).toFloat()
        return if (denom > 0f) dot / denom else 0f
    }
}
