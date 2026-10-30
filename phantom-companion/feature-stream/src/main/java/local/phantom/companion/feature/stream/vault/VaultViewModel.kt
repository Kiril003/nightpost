package local.phantom.companion.feature.stream.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.core.data.vault.VaultStore

// Two locks gate the entries: `vaultStore.locked` (a lock_vault verb from
// PHANTOM OS, survives navigation) and the session flag (biometric, dies with
// the ViewModel). Either engaged means locked; the in-screen lock clears only
// the session, the global clamp stays the server verb's to own.
class VaultViewModel(
    private val vaultStore: VaultStore,
    entries: Flow<List<VaultEntity>>,
) : ViewModel() {

    private val sessionOpen = MutableStateFlow(false)
    private val attempt = MutableStateFlow(UnlockAttempt.Idle)
    private val trouble = MutableStateFlow<VaultTrouble?>(null)
    private val rereads = MutableStateFlow(0)

    // `entries` decrypts every row it emits, so it is collected only while both
    // locks are clear. Collecting it eagerly — which this used to do — held
    // plaintext for a screen still showing a closed lock.
    private val shelf: StateFlow<Shelf> =
        combine(vaultStore.locked, sessionOpen, rereads) { locked, open, _ -> !locked && open }
            .flatMapLatest { unlocked ->
                if (!unlocked) {
                    flowOf(Shelf.Sealed)
                } else {
                    entries
                        .map<List<VaultEntity>, Shelf> { Shelf.Ready(it) }
                        .onStart { emit(Shelf.Reading) }
                        // A throwing Room flow ends the collector, and a screen
                        // that then says «locked» is lying: it is unreadable,
                        // and only one of the two is something to act on.
                        .catch { emit(Shelf.Unreadable) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Shelf.Sealed)

    val state: StateFlow<VaultUiState> = combine(
        vaultStore.locked,
        sessionOpen,
        attempt,
        trouble,
        shelf,
    ) { storeLocked, open, attempt, trouble, shelf ->
        when {
            storeLocked || !open -> VaultUiState.Locked(attempt)
            shelf is Shelf.Unreadable -> VaultUiState.Broken
            shelf !is Shelf.Ready -> VaultUiState.Reading
            else -> VaultUiState.Open(
                groups = shelve(shelf.entries),
                total = shelf.entries.size,
                unopenable = shelf.entries.count { it.value.isEmpty() },
                trouble = trouble,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = VaultUiState.Locked(UnlockAttempt.Idle),
    )

    fun onUnlockRequested() {
        attempt.value = UnlockAttempt.Waiting
    }

    fun onBiometricSuccess() {
        vaultStore.unlock()
        attempt.value = UnlockAttempt.Idle
        sessionOpen.value = true
    }

    fun onUnlockRefused(reason: UnlockAttempt) {
        attempt.value = reason
    }

    fun onLockSession() {
        sessionOpen.value = false
        attempt.value = UnlockAttempt.Idle
    }

    fun onErase(id: String) {
        viewModelScope.launch {
            runCatching { vaultStore.deleteEntry(id) }
                .onFailure { trouble.value = it.asTrouble() }
        }
    }

    fun onDismissTrouble() {
        trouble.value = null
    }

    fun onReread() {
        rereads.value += 1
    }
}

// Matched by class name, not by type: SQLiteFullException is an Android class
// and this ViewModel stays host-free so the JVM tests can run it.
private fun Throwable.asTrouble(): VaultTrouble =
    if (generateSequence(this) { it.cause }.any { it.javaClass.name.endsWith("SQLiteFullException") }) {
        VaultTrouble.NoRoom
    } else {
        VaultTrouble.EraseFailed
    }

private sealed interface Shelf {
    data object Sealed : Shelf
    data object Reading : Shelf
    data object Unreadable : Shelf
    data class Ready(val entries: List<VaultEntity>) : Shelf
}

enum class UnlockAttempt { Idle, Waiting, NotRecognised, Cancelled, NoDeviceLock }

enum class VaultTrouble { NoRoom, EraseFailed }

sealed interface VaultUiState {

    data class Locked(val attempt: UnlockAttempt) : VaultUiState

    /** Open, but the first read has not landed yet. */
    data object Reading : VaultUiState

    /** Open, and the table will not be read at all. */
    data object Broken : VaultUiState

    data class Open(
        val groups: List<VaultGroup>,
        val total: Int,
        val unopenable: Int,
        val trouble: VaultTrouble?,
    ) : VaultUiState {
        val isEmpty: Boolean get() = total == 0
    }
}
