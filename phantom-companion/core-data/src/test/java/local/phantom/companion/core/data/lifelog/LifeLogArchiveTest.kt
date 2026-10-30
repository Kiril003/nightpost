package local.phantom.companion.core.data.lifelog

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.OnnxEmbeddingHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LifeLogArchiveTest {

    private class FakeLifeLogDao : LifeLogDao {
        private val list = mutableListOf<LifeLogEntryEntity>()

        override suspend fun insert(entry: LifeLogEntryEntity) {
            list.removeIf { it.id == entry.id }
            list.add(entry)
        }

        override suspend fun getAllEntries(): List<LifeLogEntryEntity> {
            return list.sortedByDescending { it.timestampMs }
        }

        override suspend fun getEntriesSince(sinceMs: Long): List<LifeLogEntryEntity> {
            return list.filter { it.timestampMs >= sinceMs }.sortedByDescending { it.timestampMs }
        }

        override suspend fun deleteById(id: String) {
            list.removeIf { it.id == id }
        }

        override suspend fun deleteByType(eventType: String): Int {
            val before = list.size
            list.removeIf { it.eventType == eventType }
            return before - list.size
        }

        override suspend fun clear() {
            list.clear()
        }
    }

    @Test
    fun testArchiveAndQuery() = runTest {
        val dao = FakeLifeLogDao()
        // Pass a non-existent file so it forces fallback hash embedding generator
        val helper = OnnxEmbeddingHelper(File("non_existent.onnx"))
        val archive = LifeLogArchive(dao, helper)

        archive.archiveEvent("TestEvent", "зустріч з Олею в кафе", tags = "оля,кафе")
        archive.archiveEvent("TestEvent", "робота над проектом в офісі", tags = "робота")
        archive.archiveEvent("TestEvent", "вечеря в ресторані", tags = "їжа")

        // Search for something similar to "кафе"
        val results = archive.searchMemories("зустріч у кафе", limit = 1)
        assertEquals(1, results.size)
        assertTrue(results[0].contains("зустріч з Олею в кафе"))
    }

    // QA-fix 2026-07-17 — the ScreenSnapshot firehose left thousands of
    // accessibility-dump rows in the table; purgeType is the one-time
    // flush AppContainer runs at startup. Pin: it removes exactly the
    // named family and leaves real episodes alone.
    @Test
    fun `purgeType removes only the named event family`() = runTest {
        val dao = FakeLifeLogDao()
        val helper = OnnxEmbeddingHelper(File("non_existent.onnx"))
        val archive = LifeLogArchive(dao, helper)

        archive.archiveEvent("ScreenSnapshot", "[ 1 , 2 ] дамп екрана |")
        archive.archiveEvent("ScreenSnapshot", "[ 3 , 4 ] ще дамп |")
        archive.archiveEvent("dialog_digest", "розмова про каву")

        assertEquals(2, archive.purgeType("ScreenSnapshot"))
        val left = dao.getAllEntries()
        assertEquals(1, left.size)
        assertEquals("dialog_digest", left[0].eventType)
    }

    @Test
    fun testCosineSimilarityPrecision() {
        val helper = OnnxEmbeddingHelper(File("non_existent.onnx"))
        val vec1 = helper.getFallbackEmbedding("кава з круасаном")
        val vec2 = helper.getFallbackEmbedding("круасан та кава")
        val vec3 = helper.getFallbackEmbedding("програмування на коліні")

        // Cosine similarity between similar text hashes should be high
        val dot1 = vec1.zip(vec2).map { it.first * it.second }.sum()
        val dot2 = vec1.zip(vec3).map { it.first * it.second }.sum()

        assertTrue(dot1 > dot2)
    }
}
