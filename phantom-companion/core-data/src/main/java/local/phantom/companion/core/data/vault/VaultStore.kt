package local.phantom.companion.core.data.vault

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * High-level accessor for the encrypted vault.
 *
 * Phase 1-F — exposes a [locked] flag that downstream unlock-cache consumers
 * (Phase 1-G+ Memory Palace, Vault screen) consult before serving any
 * biometric-derived secret. The flag is a non-suspending atomic StateFlow
 * so [lockNow] can be invoked from any context, including a `companion_control/lock_vault`
 * verb fired by PHANTOM OS over the live WebSocket.
 *
 * Default state is `false` (use permitted) because no in-memory unlock cache
 * exists yet — entries are decrypted on demand from the encrypted [VaultDao].
 * Once a session-cache lands, `locked = true` will gate access; for now the
 * flag is advisory but truthful.
 *
 * `lockNow()` is intentionally one-way in this phase. Re-unlocking requires
 * a future biometric ceremony that has not been wired yet. This keeps the
 * auth surface small until on-device validation confirms the lock pathway.
 *
 * This class is also the single chokepoint where [VaultEntity.value] is
 * sealed and opened — nothing else in the app reaches [VaultDao] — so the
 * DAO, and therefore the table, only ever sees [VaultCipher] ciphertext.
 */
class VaultStore(
    private val dao: VaultDao,
    private val cipher: VaultCipher = VaultCipher.deviceDefault(),
) {

    private val _locked = MutableStateFlow(false)

    /** True once [lockNow] has fired; consumers MUST refuse cached secrets. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * Atomic, idempotent lock. Multiple calls in quick succession are
     * collapsed to a single transition (MutableStateFlow.value setter
     * only emits when the new value differs).
     *
     * Triggered by the `companion_control/lock_vault` server verb (Phase
     * 1-F) and by future Settings → Lock Vault tap actions.
     */
    fun lockNow() {
        _locked.value = true
    }

    /**
     * Resets the lock state to false. Allows biometric prompt to restore
     * access to the vault.
     */
    fun unlock() {
        _locked.value = false
    }

    fun getEntries(): Flow<List<VaultEntity>> =
        dao.getAll().map { entries -> entries.map(::opened) }

    suspend fun getEntry(id: String): VaultEntity? = dao.getById(id)?.let(::opened)

    suspend fun saveEntry(entry: VaultEntity) =
        dao.insert(entry.copy(value = cipher.encrypt(entry.value)))

    suspend fun deleteEntry(id: String) = dao.delete(id)

    /**
     * A row whose value will not open is still a row. Letting the cipher throw
     * here took the whole vault down with one bad entry: [getEntries] is a Flow
     * the vault screen and the map's saved-place list collect, so a single
     * undecryptable value — a Keystore reset, a half-restored backup, a row that
     * escaped MIGRATION_20_21 — ended every collector and left the operator
     * unable to even see the entry, let alone delete it. The value is blanked
     * instead: an unopenable secret is not a secret, and no caller is handed
     * ciphertext to send somewhere as if it were one.
     */
    private fun opened(entry: VaultEntity): VaultEntity =
        runCatching { entry.copy(value = cipher.decrypt(entry.value)) }
            .getOrElse {
                android.util.Log.e("VaultStore", "vault entry ${entry.id} will not open", it)
                entry.copy(value = "")
            }
}
