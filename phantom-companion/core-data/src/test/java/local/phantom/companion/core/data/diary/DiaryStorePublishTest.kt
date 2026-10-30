package local.phantom.companion.core.data.diary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-D3 — pure JVM tests for the publish + drain queue + codec.
 *
 * Acceptance from the prompt: «online → direct send; offline → queue;
 * reconnect → drain». All three branches plus partial-failure edge
 * cases are pinned below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryStorePublishTest {

    private val json = Json { ignoreUnknownKeys = true }

    /* ---- Codec ---- */

    @Test
    fun `created envelope encodes all five payload fields`() {
        val entry = DecisionEntry(
            id = "e1", createdAt = 1_700_000_000_000L,
            title = "переїхати", context = "холодно", status = DecisionStatus.ACTIVE,
        )
        val payload = DiaryPublishCodec.encodeData(entry, DiaryPublishCodec.VERB_CREATED)
        val obj = json.parseToJsonElement(payload) as JsonObject
        assertEquals("e1", (obj["id"] as JsonPrimitive).content)
        assertEquals("1700000000000", (obj["ts"] as JsonPrimitive).content)
        assertEquals("переїхати", (obj["title"] as JsonPrimitive).content)
        assertEquals("холодно", (obj["context"] as JsonPrimitive).content)
        assertEquals(DecisionStatus.ACTIVE, (obj["status"] as JsonPrimitive).content)
    }

    @Test
    fun `updated envelope encodes id and status, omits blank context`() {
        val entry = DecisionEntry("e1", 0L, "t", "", DecisionStatus.REVISIT)
        val payload = DiaryPublishCodec.encodeData(entry, DiaryPublishCodec.VERB_UPDATED)
        val obj = json.parseToJsonElement(payload) as JsonObject
        assertEquals("e1", (obj["id"] as JsonPrimitive).content)
        assertEquals(DecisionStatus.REVISIT, (obj["status"] as JsonPrimitive).content)
        assertNull("blank context must elide cleanly", obj["context"])
    }

    @Test
    fun `updated envelope includes context when non-blank`() {
        val entry = DecisionEntry("e1", 0L, "t", "новий контекст", DecisionStatus.REVISIT)
        val payload = DiaryPublishCodec.encodeData(entry, DiaryPublishCodec.VERB_UPDATED)
        val obj = json.parseToJsonElement(payload) as JsonObject
        assertEquals("новий контекст", (obj["context"] as JsonPrimitive).content)
    }

    @Test
    fun `deleted envelope encodes id only`() {
        val entry = DecisionEntry("e1", 0L, "t", "c", DecisionStatus.DONE)
        val payload = DiaryPublishCodec.encodeData(entry, DiaryPublishCodec.VERB_DELETED)
        val obj = json.parseToJsonElement(payload) as JsonObject
        assertEquals("e1", (obj["id"] as JsonPrimitive).content)
        assertNull(obj["title"])
        assertNull(obj["status"])
        assertNull(obj["context"])
    }

    @Test
    fun `unknown verb throws IllegalArgumentException loudly`() {
        val entry = DecisionEntry("e1", 0L, "t", "c", DecisionStatus.ACTIVE)
        try {
            DiaryPublishCodec.encodeData(entry, "future_verb")
            error("must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("future_verb"))
        }
    }

    @Test
    fun `envelope wraps data with channel type and ts`() {
        val data = """{"id":"e1"}"""
        val envelope = DiaryPublishCodec.encodeEnvelope(
            DiaryPublishCodec.VERB_DELETED,
            data,
            nowMs = 1_700_000_000_000L,
        )
        val obj = json.parseToJsonElement(envelope) as JsonObject
        assertEquals(DiaryPublishCodec.CHANNEL, (obj["channel"] as JsonPrimitive).content)
        assertEquals("deleted", (obj["type"] as JsonPrimitive).content)
        assertEquals("1700000000000", (obj["ts"] as JsonPrimitive).content)
        val inner = obj["data"] as JsonObject
        assertEquals("e1", (inner["id"] as JsonPrimitive).content)
    }

    /* ---- Fakes ---- */

    /** In-memory DAO for the diary entries. */
    private class FakeEntryDao : DecisionEntryDao {
        val entries = MutableStateFlow<List<DecisionEntry>>(emptyList())
        override fun getAll() = entries
        override suspend fun getById(id: String) = entries.value.firstOrNull { it.id == id }
        override suspend fun insert(entry: DecisionEntry) {
            entries.update { it.filterNot { e -> e.id == entry.id } + entry }
        }
        override suspend fun delete(id: String) {
            entries.update { it.filterNot { e -> e.id == id } }
        }
    }

    /** In-memory DAO for the pending publish queue. */
    private class FakePendingDao : PendingPublishDao {
        val items = MutableStateFlow<List<PendingPublish>>(emptyList())
        private var nextRowId: Long = 1L
        override fun observeAll() = items
        override suspend fun snapshot(): List<PendingPublish> = items.value.sortedBy { it.rowId }
        override suspend fun count(): Int = items.value.size
        override suspend fun insert(item: PendingPublish): Long {
            val withId = if (item.rowId == 0L) item.copy(rowId = nextRowId++) else item
            items.update { it + withId }
            return withId.rowId
        }
        override suspend fun deleteById(rowId: Long) {
            items.update { it.filterNot { p -> p.rowId == rowId } }
        }
        override suspend fun clear() { items.value = emptyList() }
    }

    /** Controllable transport — flips connectivity, records send calls, fakes failures. */
    private class FakeTransport(
        var online: Boolean = true,
        var rejectNext: Int = 0, // number of subsequent send() calls to reject
    ) : DiaryPublishTransport {
        val sentLog: MutableList<Pair<String, String>> = mutableListOf()
        override val isConnected: Boolean get() = online
        override suspend fun send(verb: String, payload: String): Boolean {
            if (rejectNext > 0) {
                rejectNext--
                return false
            }
            if (!online) return false
            sentLog += verb to payload
            return true
        }
    }

    private val sample = DecisionEntry(
        id = "e1", createdAt = 1L,
        title = "rent lhasa", context = "winter cold", status = DecisionStatus.ACTIVE,
    )

    /* ---- publish branches ---- */

    @Test
    fun `online publish goes straight to transport, no queue`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)
        val transport = FakeTransport(online = true)

        val result = store.publish(sample, DiaryPublishCodec.VERB_CREATED, transport)

        assertTrue(result)
        assertEquals(1, transport.sentLog.size)
        assertEquals("created", transport.sentLog.single().first)
        assertEquals(0, pending.count())
    }

    @Test
    fun `offline publish queues into pending_publish`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)
        val transport = FakeTransport(online = false)

        val result = store.publish(sample, DiaryPublishCodec.VERB_CREATED, transport)

        assertFalse(result)
        assertEquals(0, transport.sentLog.size)
        assertEquals(1, pending.count())
        val queued = pending.snapshot().single()
        assertEquals("created", queued.verb)
        assertTrue(queued.payload.contains("\"id\":\"e1\""))
    }

    @Test
    fun `online but rejected send falls through to queue`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)
        val transport = FakeTransport(online = true, rejectNext = 1)

        val result = store.publish(sample, DiaryPublishCodec.VERB_UPDATED, transport)

        assertFalse(result)
        assertEquals("rejected send must not be in sentLog", 0, transport.sentLog.size)
        assertEquals("rejected send must enqueue", 1, pending.count())
    }

    @Test
    fun `null transport always queues`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)

        val result = store.publish(sample, DiaryPublishCodec.VERB_DELETED, transport = null)

        assertFalse(result)
        assertEquals(1, pending.count())
    }

    @Test
    fun `publish with no pending dao silently drops on offline`() = runTest(UnconfinedTestDispatcher()) {
        // Backwards-compat path — Phase 1.5-D tests construct DiaryStore
        // with the legacy single-arg constructor. publish() must not throw.
        val store = DiaryStore(FakeEntryDao())
        val transport = FakeTransport(online = false)

        val result = store.publish(sample, DiaryPublishCodec.VERB_CREATED, transport)

        assertFalse("offline publish without pending dao returns false", result)
        // No exception thrown — that's the contract.
    }

    /* ---- drainPending ---- */

    @Test
    fun `drainPending sends all queued items in FIFO order on reconnect`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)
        val offline = FakeTransport(online = false)

        // Three queued envelopes
        store.publish(sample.copy(id = "e1"), DiaryPublishCodec.VERB_CREATED, offline)
        store.publish(sample.copy(id = "e2"), DiaryPublishCodec.VERB_UPDATED, offline)
        store.publish(sample.copy(id = "e3"), DiaryPublishCodec.VERB_DELETED, offline)
        assertEquals(3, pending.count())

        // Reconnect — drain
        val transport = FakeTransport(online = true)
        val drained = store.drainPending(transport)
        advanceUntilIdle()

        assertEquals(3, drained)
        assertEquals(0, pending.count())
        assertEquals(listOf("created", "updated", "deleted"), transport.sentLog.map { it.first })
    }

    @Test
    fun `drainPending stops at first failure preserving remaining queue`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)
        store.publish(sample.copy(id = "e1"), DiaryPublishCodec.VERB_CREATED, transport = null)
        store.publish(sample.copy(id = "e2"), DiaryPublishCodec.VERB_UPDATED, transport = null)
        store.publish(sample.copy(id = "e3"), DiaryPublishCodec.VERB_DELETED, transport = null)

        // Transport rejects the second send (network blip mid-drain)
        val flaky = FakeTransport(online = true, rejectNext = 1).apply {
            // Custom: reject the SECOND call; we manipulate by sending one
            // successful call first via direct-publish, then setting rejectNext.
        }
        // Hand-craft: flaky drain — reject the 2nd of 3
        val transport = object : DiaryPublishTransport {
            override val isConnected = true
            var calls = 0
            val log = mutableListOf<String>()
            override suspend fun send(verb: String, payload: String): Boolean {
                calls++
                if (calls == 2) return false
                log += verb
                return true
            }
        }

        val drained = store.drainPending(transport)
        advanceUntilIdle()

        assertEquals("only the first item drained before failure", 1, drained)
        assertEquals("queue retains the two failed/un-attempted items", 2, pending.count())
        // The first item is gone; e2 and e3 remain in order.
        val remaining = pending.snapshot().map { it.verb }
        assertEquals(listOf("updated", "deleted"), remaining)
    }

    @Test
    fun `drainPending no-ops when transport offline`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)
        store.publish(sample, DiaryPublishCodec.VERB_CREATED, transport = null)

        val transport = FakeTransport(online = false)
        val drained = store.drainPending(transport)

        assertEquals(0, drained)
        assertEquals("offline drain must not touch the queue", 1, pending.count())
        assertEquals(0, transport.sentLog.size)
    }

    @Test
    fun `drainPending no-ops with no pending dao`() = runTest(UnconfinedTestDispatcher()) {
        val store = DiaryStore(FakeEntryDao()) // legacy ctor, no queue
        val transport = FakeTransport(online = true)

        val drained = store.drainPending(transport)

        assertEquals(0, drained)
    }

    @Test
    fun `pendingCount and pendingFlow reflect queue state`() = runTest(UnconfinedTestDispatcher()) {
        val pending = FakePendingDao()
        val store = DiaryStore(FakeEntryDao(), pending)

        assertEquals(0, store.pendingCount())
        assertNotNull(store.pendingFlow())

        store.publish(sample, DiaryPublishCodec.VERB_CREATED, transport = null)
        assertEquals(1, store.pendingCount())
    }
}
