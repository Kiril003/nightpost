package local.phantom.companion.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultStoreTest {

    private lateinit var store: VaultStore
    private lateinit var fakeDao: FakeVaultDao

    @Before
    fun setup() {
        fakeDao = FakeVaultDao()
        store = VaultStore(fakeDao, ReversingCipher)
    }

    @Test
    fun `save and retrieve entry`() = runTest {
        val entry = VaultEntity(
            id = "test-1",
            key = "my-password",
            value = "secret123",
            type = "secret"
        )
        store.saveEntry(entry)
        
        val retrieved = store.getEntry("test-1")
        assertNotNull(retrieved)
        assertEquals("my-password", retrieved?.key)
        assertEquals("secret123", retrieved?.value)
    }

    @Test
    fun `delete entry`() = runTest {
        val entry = VaultEntity(
            id = "test-2",
            key = "to-delete",
            value = "bye",
            type = "note"
        )
        store.saveEntry(entry)
        store.deleteEntry("test-2")
        
        val retrieved = store.getEntry("test-2")
        assertNull(retrieved)
    }

    @Test
    fun `get all entries flow`() = runTest {
        store.saveEntry(VaultEntity("1", "k1", "v1", "t"))
        store.saveEntry(VaultEntity("2", "k2", "v2", "t"))

        val all = store.getEntries().first()
        assertEquals(2, all.size)
    }

    // ── Phase 1-F — clamp invariants ─────────────────────────────────────────

    @Test
    fun `default locked is false`() = runTest {
        org.junit.Assert.assertFalse("default permits use", store.locked.value)
    }

    @Test
    fun `lockNow flips locked to true`() = runTest {
        store.lockNow()
        org.junit.Assert.assertTrue(store.locked.value)
    }

    @Test
    fun `lockNow is idempotent — second call no-op`() = runTest {
        store.lockNow()
        val firstSnapshot = store.locked.value
        store.lockNow()
        store.lockNow()
        assertEquals("multiple lockNow calls collapse", firstSnapshot, store.locked.value)
        org.junit.Assert.assertTrue(store.locked.value)
    }

    @Test
    fun `lockNow does not affect entry CRUD surface`() = runTest {
        // Lock state is advisory in Phase 1-F — it does not block raw DAO
        // CRUD because no in-memory unlock cache exists yet. Future phases
        // add the gate; for now we just assert we didn't accidentally wire
        // it into saveEntry / deleteEntry.
        store.lockNow()
        store.saveEntry(VaultEntity("k", "key", "value", "secret"))
        assertNotNull(store.getEntry("k"))
    }

    // ── per-value encryption ─────────────────────────────────────────────────

    @Test
    fun `the dao never sees a plaintext value`() = runTest {
        store.saveEntry(VaultEntity("t", "alerts_in_ua_token", "s3cr3t-token", "secret"))

        val stored = fakeDao.raw("t")!!.value
        org.junit.Assert.assertNotEquals("value must be sealed before it hits the dao", "s3cr3t-token", stored)
        org.junit.Assert.assertTrue("stored value carries the wire prefix", VaultCipher.isEncrypted(stored))
    }

    @Test
    fun `key and metadata stay readable — only the value is sealed`() = runTest {
        store.saveEntry(VaultEntity("t", "label", "secret", "secret", metadata = "kind=poi"))

        val stored = fakeDao.raw("t")!!
        assertEquals("label", stored.key)
        assertEquals("kind=poi", stored.metadata)
    }

    @Test
    fun `all three read paths open the value back`() = runTest {
        store.saveEntry(VaultEntity("t", "k", "secret", "secret"))

        assertEquals("secret", store.getEntry("t")?.value)
        assertEquals("secret", store.getEntries().first().single().value)
    }

    @Test
    fun `legacy plaintext rows survive a read`() = runTest {
        // A row written by a build from before MIGRATION_20_21.
        fakeDao.insert(VaultEntity("old", "k", "bare-plaintext", "secret"))

        assertEquals("bare-plaintext", store.getEntry("old")?.value)
    }

    @Test
    fun `one unopenable row does not take the whole vault down`() = runTest {
        val store = VaultStore(fakeDao, RefusingCipher)
        fakeDao.insert(VaultEntity("good", "k1", "PV1:fine", "secret"))
        fakeDao.insert(VaultEntity("bad", "k2", "PV1:rotten", "secret"))

        val all = store.getEntries().first()

        assertEquals("both rows still listed", 2, all.size)
        assertEquals("fine", all.single { it.id == "good" }.value)
        assertEquals("an unopenable secret is not a secret", "", all.single { it.id == "bad" }.value)
        assertEquals("k2", all.single { it.id == "bad" }.key)
    }

    @Test
    fun `an unopenable single entry reads back blank rather than as ciphertext`() = runTest {
        val store = VaultStore(fakeDao, RefusingCipher)
        fakeDao.insert(VaultEntity("bad", "alerts_in_ua_token", "PV1:rotten", "secret"))

        assertEquals("", store.getEntry("bad")?.value)
    }

    /** Opens everything but the row that lost its key. */
    private object RefusingCipher : VaultCipher {
        override fun encrypt(plaintext: String): String = "PV1:$plaintext"

        override fun decrypt(stored: String): String {
            if (!VaultCipher.isEncrypted(stored)) return stored
            val body = stored.removePrefix("PV1:")
            if (body == "rotten") throw IllegalStateException("key gone")
            return body
        }
    }

    /** Prefix + reverse: enough to prove the value is transformed on the way
     *  in and restored on the way out without an Android Keystore. */
    private object ReversingCipher : VaultCipher {
        override fun encrypt(plaintext: String): String = "PV1:" + plaintext.reversed()

        override fun decrypt(stored: String): String =
            if (VaultCipher.isEncrypted(stored)) stored.removePrefix("PV1:").reversed() else stored
    }

    private class FakeVaultDao : VaultDao {
        private val entries = mutableMapOf<String, VaultEntity>()
        private val flow = MutableStateFlow<List<VaultEntity>>(emptyList())

        /** What actually landed in the table, un-opened by the store. */
        fun raw(id: String): VaultEntity? = entries[id]

        override fun getAll(): Flow<List<VaultEntity>> = flow

        override suspend fun getById(id: String): VaultEntity? = entries[id]

        override suspend fun insert(entry: VaultEntity) {
            entries[entry.id] = entry
            flow.value = entries.values.toList()
        }

        override suspend fun delete(id: String) {
            entries.remove(id)
            flow.value = entries.values.toList()
        }
    }
}
