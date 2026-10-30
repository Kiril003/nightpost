package local.phantom.companion.self

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.route.MemoryGround
import local.phantom.companion.core.memory.Ledger
import local.phantom.companion.core.memory.LedgerStore
import local.phantom.companion.core.memory.MemorySync
import local.phantom.companion.core.memory.Seal

/**
 * Памʼять на телефоні як система: зашифрована репліка на диску, опора відповіді
 * для [local.phantom.companion.core.ai.route.BrainChain] і синк із ПК.
 *
 * Синк біжить, коли спарений звʼязок став (`Connected`), і за кілька секунд
 * після будь-якої локальної зміни — тим самим каналом, що решта розмов із ПК.
 */
class MemoryRuntime(
    val ledger: Ledger,
    /** false — ключа немає (Keystore не відкрився): репліка живе лише в RAM. */
    val persistent: Boolean,
    private val transport: MemorySync.Transport?,
    private val paired: () -> Boolean,
) {
    val sync: MemorySync? = transport?.let { MemorySync(ledger, it) }
    private val mutex = Mutex()
    private val wanted = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var debounce: Job? = null

    /** Опора відповіді для BrainChain. [self] — лінивий, бо SelfMemory будується пізніше. */
    fun ground(self: () -> SelfMemory): MemoryGround = object : MemoryGround {
        override suspend fun dontKnow(query: String): String? = self().dontKnow(query)
        override suspend fun block(query: String): String? = self().promptBlock(query).ifBlank { null }
        override suspend fun cite(shortIds: List<String>): List<String> {
            val byShort = ledger.store.list().associateBy { it.shortId }
            return shortIds.mapNotNull { byShort[it]?.displayText() }
        }
    }

    fun start(scope: CoroutineScope, connected: StateFlow<Boolean>) {
        this.scope = scope
        scope.launch {
            connected.collect { on -> if (on) runOnce() }
        }
    }

    /** Локальна зміна: синк за [DEBOUNCE_MS], кілька змін поспіль — один сеанс. */
    fun changed() {
        val s = scope ?: return
        wanted.set(true)
        debounce?.cancel()
        debounce = s.launch {
            delay(DEBOUNCE_MS)
            runOnce()
        }
    }

    suspend fun runOnce(): MemorySync.Report? {
        val s = sync ?: return null
        if (!paired()) return null
        return mutex.withLock {
            wanted.set(false)
            runCatching { s.run() }.onFailure { Log.w(TAG, "memory sync failed", it) }.getOrNull()
                ?.also { if (!it.ok) Log.i(TAG, "memory sync: ${it.error}") }
        }
    }

    /** Чесний стан для «Профіль › PHANTOM › Памʼять». */
    fun state(): JsonObject = buildJsonObject {
        val known = ledger.known()
        put("node", "phone")
        put("items", known.count { it.status == "active" })
        put("pending", known.count { it.status == "pending" })
        put("budget_tokens", Ledger.BUDGET_TOKENS)
        put("persistent", persistent)
        put("sync", buildJsonObject {
            put("peer", "pc")
            put("paired", paired())
            sync?.state()?.forEach { (k, v) -> put(k, v) }
        })
    }

    companion object {
        private const val TAG = "MemoryRuntime"
        private const val DEBOUNCE_MS = 4_000L
        private const val PREFS = "phantom_memory_key_v1"
        private const val KEY = "secret"

        fun build(
            context: Context,
            device: String,
            transport: MemorySync.Transport?,
            paired: () -> Boolean,
        ): MemoryRuntime {
            val secret = runCatching { secret(context) }
                .onFailure { Log.e(TAG, "memory key unavailable — replica stays in RAM only", it) }
                .getOrNull()
            val store = if (secret != null) {
                LedgerStore(Seal.fromSecret(secret), File(File(context.filesDir, "memory"), "memory_ledger.json"))
            } else {
                LedgerStore(Seal.fromSecret("ram-" + java.util.UUID.randomUUID()))
            }
            return MemoryRuntime(Ledger(store, device = device), persistent = secret != null, transport, paired)
        }

        /** 32 випадкові байти, що живуть лише в EncryptedSharedPreferences (Keystore). */
        private fun secret(context: Context): String {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context, PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs.getString(KEY, null)?.let { return it }
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val fresh = Base64.getEncoder().encodeToString(raw)
            check(prefs.edit().putString(KEY, fresh).commit()) { "memory key not stored" }
            return fresh
        }
    }
}
