package local.phantom.companion.feature.stream.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.phantom.companion.core.data.vault.VaultDao
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.core.data.vault.VaultStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleEntries(): List<VaultEntity> = listOf(
        VaultEntity(
            id = "e1",
            key = "github · root",
            value = "gh_3a7f9d2b",
            type = "secret",
            metadata = "kind=token",
        ),
        VaultEntity(
            id = "e2",
            key = "map.recent",
            value = "аптека",
            type = "note",
            metadata = MAP_RECENT_META,
        ),
        VaultEntity(
            id = "e3",
            key = "старий ключ",
            value = "",
            type = "secret",
        ),
    )

    private fun storeOver(entries: Flow<List<VaultEntity>>): VaultStore {
        val dao = object : VaultDao {
            override fun getAll(): Flow<List<VaultEntity>> = entries
            override suspend fun getById(id: String): VaultEntity? = null
            override suspend fun insert(entry: VaultEntity) = Unit
            override suspend fun delete(id: String) = Unit
        }
        return VaultStore(dao)
    }

    @Test
    fun `default state is locked and idle`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)

        assertEquals(VaultUiState.Locked(UnlockAttempt.Idle), vm.state.value)
    }

    @Test
    fun `a requested unlock is waiting, not open`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)

        vm.onUnlockRequested()

        assertEquals(VaultUiState.Locked(UnlockAttempt.Waiting), vm.state.value)
    }

    @Test
    fun `a refusal carries its reason`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)

        vm.onUnlockRequested()
        vm.onUnlockRefused(UnlockAttempt.NotRecognised)

        assertEquals(VaultUiState.Locked(UnlockAttempt.NotRecognised), vm.state.value)
    }

    @Test
    fun `biometric success shelves the entries by kind`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)

        vm.onBiometricSuccess()

        val open = vm.state.first { it is VaultUiState.Open } as VaultUiState.Open
        assertEquals(3, open.total)
        assertEquals(1, open.unopenable)
        assertFalse(open.isEmpty)
        assertEquals(
            listOf(VaultKind.Unopenable, VaultKind.Secret, VaultKind.Trace),
            open.groups.map { it.kind },
        )
    }

    @Test
    fun `an empty table is open, not locked`() = runTest(dispatcher) {
        val entries = MutableStateFlow(emptyList<VaultEntity>())
        val vm = VaultViewModel(storeOver(entries), entries)

        vm.onBiometricSuccess()

        val open = vm.state.first { it is VaultUiState.Open } as VaultUiState.Open
        assertTrue(open.isEmpty)
        assertEquals(0, open.total)
    }

    @Test
    fun `a table that will not be read is broken, not locked`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val exploding = flow<List<VaultEntity>> { throw IllegalStateException("no key") }
        val vm = VaultViewModel(storeOver(entries), exploding)

        vm.onBiometricSuccess()

        assertEquals(VaultUiState.Broken, vm.state.first { it is VaultUiState.Broken })
    }

    @Test
    fun `the lock control clears the session`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)

        vm.onBiometricSuccess()
        assertTrue(vm.state.value is VaultUiState.Open)

        vm.onLockSession()
        assertEquals(VaultUiState.Locked(UnlockAttempt.Idle), vm.state.value)
    }

    @Test
    fun `an external lock clamps a session the operator just opened`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val store = storeOver(entries)
        val vm = VaultViewModel(store, entries)

        vm.onBiometricSuccess()
        assertTrue(vm.state.value is VaultUiState.Open)

        store.lockNow()

        assertEquals(VaultUiState.Locked(UnlockAttempt.Idle), vm.state.value)
    }

    @Test
    fun `re-unlocking after a session lock reopens without a restart`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)

        vm.onBiometricSuccess()
        vm.onLockSession()
        vm.onBiometricSuccess()

        assertTrue(vm.state.value is VaultUiState.Open)
    }

    @Test
    fun `the open state follows the entries flow`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val vm = VaultViewModel(storeOver(entries), entries)
        vm.onBiometricSuccess()

        entries.value = sampleEntries() + VaultEntity(
            id = "e4",
            key = "stream:1",
            value = "олег пише · треба передзвонити",
            type = "note",
        )

        val open = vm.state.first { it is VaultUiState.Open && it.total == 4 } as VaultUiState.Open
        assertTrue(open.groups.any { it.kind == VaultKind.Note })
    }

    @Test
    fun `an erase that fails for want of space says so`() = runTest(dispatcher) {
        val entries = MutableStateFlow(sampleEntries())
        val dao = object : VaultDao {
            override fun getAll(): Flow<List<VaultEntity>> = entries
            override suspend fun getById(id: String): VaultEntity? = null
            override suspend fun insert(entry: VaultEntity) = Unit
            override suspend fun delete(id: String): Unit = throw SQLiteFullException()
        }
        val vm = VaultViewModel(VaultStore(dao), entries)
        vm.onBiometricSuccess()
        vm.onErase("e1")

        val open = vm.state.value as VaultUiState.Open
        assertEquals(VaultTrouble.NoRoom, open.trouble)

        vm.onDismissTrouble()
        assertNull((vm.state.value as VaultUiState.Open).trouble)
    }

    // Matched by name, the way VaultViewModel matches the real Android one.
    private class SQLiteFullException : RuntimeException("disk full")
}
