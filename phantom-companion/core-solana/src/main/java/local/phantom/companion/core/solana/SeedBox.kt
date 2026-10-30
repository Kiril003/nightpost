package local.phantom.companion.core.solana

internal interface SeedLock {
    fun seal(plain: ByteArray): ByteArray
    fun open(blob: ByteArray): ByteArray
    fun drop()
}

internal interface SeedShelf {
    fun get(name: String): ByteArray?
    /** Усі записи й видалення лягають разом або жоден. */
    fun write(put: Map<String, ByteArray>, remove: Set<String>): Boolean
}

open class VaultLocked(why: String = WHY, cause: Throwable? = null) : IllegalStateException(why, cause) {
    constructor(cause: Throwable?) : this(WHY, cause)

    companion object {
        const val WHY = "vault_locked"
    }
}

class NoScreenLock : VaultLocked(NoScreenLock.WHY) {
    companion object {
        const val WHY = "no_screen_lock"
    }
}

/**
 * Зерно лежить лише під [bound]: ключ Keystore, що шифрує й відчиняє тільки у вікні після
 * PIN/відбитка. [legacy] (ключ без автентифікації зі старих збірок) лише читається — раз, щоб
 * перекласти старе зерно під [bound]; без блокування екрана старе зерно лежить недоторкане.
 */
internal class SeedBox(
    private val shelf: SeedShelf,
    private val legacy: SeedLock,
    private val bound: SeedLock,
    private val canBind: () -> Boolean,
) {

    val exists: Boolean get() = shelf.get(BOUND) != null || shelf.get(LEGACY) != null

    @Synchronized
    fun seal(words: String) {
        if (!canBind()) throw NoScreenLock()
        val blob = try {
            verifiedBound(words.toByteArray())
        } catch (e: Exception) {
            throw VaultLocked(e)
        }
        check(shelf.write(mapOf(BOUND to blob), setOf(LEGACY))) { "vault write failed" }
        legacy.drop()
    }

    @Synchronized
    fun open(): String {
        if (!canBind()) throw NoScreenLock()
        shelf.get(BOUND)?.let { blob ->
            return try {
                String(bound.open(blob))
            } catch (e: Exception) {
                throw VaultLocked(e)
            }
        }
        val old = shelf.get(LEGACY) ?: throw IllegalStateException("no wallet")
        // Старий ключ відчиняється без PIN, тож зерно віддається лише тоді, коли [bound] щойно прийняв розблокування.
        val plain = try {
            legacy.open(old)
        } catch (e: Exception) {
            throw VaultLocked(e)
        }
        val blob = try {
            verifiedBound(plain)
        } catch (e: Exception) {
            plain.fill(0)
            throw VaultLocked(e)
        }
        if (shelf.write(mapOf(BOUND to blob), setOf(LEGACY))) legacy.drop()
        return String(plain)
    }

    private fun verifiedBound(plain: ByteArray): ByteArray {
        val blob = bound.seal(plain)
        check(bound.open(blob).contentEquals(plain)) { "bound round trip" }
        return blob
    }

    companion object {
        const val LEGACY = "secret"
        const val BOUND = "secret_bound"
    }
}
