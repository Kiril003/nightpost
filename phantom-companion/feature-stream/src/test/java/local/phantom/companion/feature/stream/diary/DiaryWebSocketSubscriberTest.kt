package local.phantom.companion.feature.stream.diary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.data.diary.DecisionEntry
import local.phantom.companion.core.data.diary.DecisionEntryDao
import local.phantom.companion.core.data.diary.DecisionStatus
import local.phantom.companion.core.data.diary.DiaryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-D2 — pure JVM tests for the wire parser + the subscriber's
 * dispatch table. Acceptance from the prompt: «fake events → store
 * mutations». Covered below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryWebSocketSubscriberTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun envelope(type: String, dataJson: String): JsonObject {
        val raw = """{"channel":"decision_diary","type":"$type","data":$dataJson,"ts":1700000000000}"""
        return json.parseToJsonElement(raw) as JsonObject
    }

    /* ---- DiaryWireParser ---- */

    @Test
    fun `parses created envelope into DecisionEntry`() {
        val raw = envelope(
            "created",
            """{"id":"e1","ts":1700000000000,"title":"переїхати","context":"бо холодно","status":"актуально"}""",
        )
        val msg = DiaryWireParser.tryParse("created", raw) as DiaryWireMessage.Created

        assertEquals("e1", msg.entry.id)
        assertEquals(1_700_000_000_000L, msg.entry.createdAt)
        assertEquals("переїхати", msg.entry.title)
        assertEquals("бо холодно", msg.entry.context)
        assertEquals("актуально", msg.entry.status)
    }

    @Test
    fun `parses updated envelope with optional context`() {
        val withContext = envelope(
            "updated",
            """{"id":"e1","status":"переглянути","context":"змінилось"}""",
        )
        val a = DiaryWireParser.tryParse("updated", withContext) as DiaryWireMessage.Updated
        assertEquals("e1", a.id)
        assertEquals("переглянути", a.status)
        assertEquals("змінилось", a.context)

        val withoutContext = envelope(
            "updated",
            """{"id":"e2","status":"завершено"}""",
        )
        val b = DiaryWireParser.tryParse("updated", withoutContext) as DiaryWireMessage.Updated
        assertEquals("e2", b.id)
        assertEquals("завершено", b.status)
        assertNull(b.context)
    }

    @Test
    fun `parses deleted envelope`() {
        val raw = envelope("deleted", """{"id":"gone"}""")
        val msg = DiaryWireParser.tryParse("deleted", raw) as DiaryWireMessage.Deleted
        assertEquals("gone", msg.id)
    }

    @Test
    fun `parses follow_up envelope`() {
        val raw = envelope("follow_up", """{"id":"e1","prompt":"вартість зросла"}""")
        val msg = DiaryWireParser.tryParse("follow_up", raw) as DiaryWireMessage.FollowUp
        assertEquals("e1", msg.id)
        assertEquals("вартість зросла", msg.prompt)
    }

    @Test
    fun `unknown verb returns null`() {
        val raw = envelope("created", """{"id":"e1","ts":1700000000000,"title":"x","context":"y","status":"актуально"}""")
        assertNull(DiaryWireParser.tryParse("future_verb", raw))
    }

    @Test
    fun `created with missing required field returns null`() {
        // No `title` — required per §10.15
        val raw = envelope("created", """{"id":"e1","ts":1700000000000,"context":"y","status":"актуально"}""")
        assertNull(DiaryWireParser.tryParse("created", raw))
    }

    @Test
    fun `envelope without data field returns null`() {
        val raw = json.parseToJsonElement(
            """{"channel":"decision_diary","type":"deleted","ts":1}""",
        ) as JsonObject
        assertNull(DiaryWireParser.tryParse("deleted", raw))
    }

    /* ---- DiaryWebSocketSubscriber ---- */

    /** Fake DAO that mutates an in-memory list — pinned to verify subscriber dispatch. */
    private class FakeDao : DecisionEntryDao {
        val entries = MutableStateFlow<List<DecisionEntry>>(emptyList())
        override fun getAll() = entries
        override suspend fun getById(id: String): DecisionEntry? = entries.value.firstOrNull { it.id == id }
        override suspend fun insert(entry: DecisionEntry) {
            entries.update { current ->
                current.filterNot { it.id == entry.id } + entry
            }
        }
        override suspend fun delete(id: String) {
            entries.update { it.filterNot { e -> e.id == id } }
        }
    }

    @Test
    fun `Created handler upserts via store`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeDao()
        val sub = DiaryWebSocketSubscriber(DiaryStore(dao))

        sub.handle(
            DiaryWireMessage.Created(
                DecisionEntry(
                    id = "e1",
                    createdAt = 1L,
                    title = "rent lhasa",
                    context = "winter cold",
                    status = DecisionStatus.ACTIVE,
                ),
            ),
        )
        advanceUntilIdle()

        val stored = dao.entries.value.single()
        assertEquals("e1", stored.id)
        assertEquals("rent lhasa", stored.title)
        assertEquals(DecisionStatus.ACTIVE, stored.status)
    }

    @Test
    fun `Updated handler merges status and optional context onto existing`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeDao()
        dao.insert(
            DecisionEntry("e1", createdAt = 1L, title = "t", context = "old", status = DecisionStatus.ACTIVE),
        )
        val sub = DiaryWebSocketSubscriber(DiaryStore(dao))

        // Update status only — context preserved
        sub.handle(DiaryWireMessage.Updated("e1", status = DecisionStatus.REVISIT, context = null))
        advanceUntilIdle()
        var entry = dao.entries.value.single()
        assertEquals(DecisionStatus.REVISIT, entry.status)
        assertEquals("old", entry.context)
        // Title and createdAt untouched
        assertEquals("t", entry.title)
        assertEquals(1L, entry.createdAt)

        // Update both
        sub.handle(DiaryWireMessage.Updated("e1", status = DecisionStatus.DONE, context = "fresh"))
        advanceUntilIdle()
        entry = dao.entries.value.single()
        assertEquals(DecisionStatus.DONE, entry.status)
        assertEquals("fresh", entry.context)
    }

    @Test
    fun `Updated for unknown id is silently dropped (out-of-order delivery)`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeDao()
        val sub = DiaryWebSocketSubscriber(DiaryStore(dao))

        sub.handle(DiaryWireMessage.Updated("missing-id", status = DecisionStatus.DONE, context = null))
        advanceUntilIdle()

        assertEquals("no entry should be created from a stray Updated", 0, dao.entries.value.size)
    }

    @Test
    fun `Deleted handler removes the entry`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeDao()
        dao.insert(DecisionEntry("e1", 1L, "t", "c", DecisionStatus.ACTIVE))
        val sub = DiaryWebSocketSubscriber(DiaryStore(dao))

        sub.handle(DiaryWireMessage.Deleted("e1"))
        advanceUntilIdle()

        assertEquals(0, dao.entries.value.size)
    }

    @Test
    fun `FollowUp surfaces on the followUps SharedFlow without store mutation`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeDao()
        val sub = DiaryWebSocketSubscriber(DiaryStore(dao))

        // Collect the next followUp emission
        val collected = mutableListOf<DiaryWireMessage.FollowUp>()
        val collectorJob = launch {
            sub.followUps.collect { collected += it }
        }

        sub.handle(DiaryWireMessage.FollowUp("e1", prompt = "переглянути?"))
        advanceUntilIdle()

        assertEquals(1, collected.size)
        assertEquals("e1", collected[0].id)
        assertEquals("переглянути?", collected[0].prompt)
        // Store untouched
        assertEquals(0, dao.entries.value.size)

        collectorJob.cancel()
    }

    @Test
    fun `subscribe collects from a Flow and dispatches each event`() = runTest(UnconfinedTestDispatcher()) {
        val dao = FakeDao()
        val sub = DiaryWebSocketSubscriber(DiaryStore(dao))
        val source = MutableSharedFlow<DiaryWireMessage>(extraBufferCapacity = 8)

        val job = sub.subscribe(backgroundScope, source)

        source.emit(DiaryWireMessage.Created(DecisionEntry("e1", 1L, "first", "ctx", DecisionStatus.ACTIVE)))
        source.emit(DiaryWireMessage.Created(DecisionEntry("e2", 2L, "second", "ctx", DecisionStatus.ACTIVE)))
        source.emit(DiaryWireMessage.Deleted("e1"))
        advanceUntilIdle()

        val remaining = dao.entries.value
        assertEquals(1, remaining.size)
        assertEquals("e2", remaining.single().id)

        job.cancel()
    }
}
