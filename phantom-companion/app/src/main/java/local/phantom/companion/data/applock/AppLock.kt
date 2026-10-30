package local.phantom.companion.data.applock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Замок застосунку: код (і, якщо дозволено, відбиток) поверх усього PHANTOM.
 *
 * Що він стереже — і чого ні. Він ховає ВІДКРИТИЙ застосунок від того, хто
 * взяв розблокований телефон: розмови, мапу, людей. Вміст сповіщень ховає
 * окремий перемикач ([hidesNotificationContent]), а не сам замок (T6). Ключ шифрування сховищ він
 * не замінює — ті й так у Keystore апарата.
 *
 * Код не зберігається: лише PBKDF2-SHA256 із сіллю ([ITERATIONS]). Невдалі
 * спроби вмикають наростальну паузу — пʼять промахів поспіль коштують хвилину,
 * далі вдвічі більше.
 *
 * Чистий клас: стан у [Slot], час — [clock]. Android-обвʼязка — [AppLockStore].
 */
class AppLock(
    private val slot: Slot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {

    interface Slot {
        fun read(): String?
        fun write(value: String)
    }

    @Serializable
    data class State(
        val enabled: Boolean = false,
        val salt: String = "",
        val hash: String = "",
        val biometric: Boolean = false,
        /** Через скільки після виходу із застосунку зачинятись; 0 — одразу. */
        val autoLockMs: Long = 0L,
        /** «Ховати текст у сповіщеннях» — і без замка. null — не чіпали: до T6 замок ховав сам, тож старим замкам лишається схованим. */
        val hideNotificationText: Boolean? = null,
        val failures: Int = 0,
        val waitUntilMs: Long = 0L,
    ) {
        val hidesText: Boolean get() = hideNotificationText ?: enabled
    }

    sealed interface Attempt {
        data object Opened : Attempt
        data object Wrong : Attempt
        /** Забагато промахів — спроба не рахується до [untilMs]. */
        data class Wait(val untilMs: Long) : Attempt
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var state: State = slot.read()?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State()

    /** Увімкнений замок стартує зачиненим: процес, піднятий з нуля, не знає, хто тримає телефон. */
    private val _locked = MutableStateFlow(state.enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _settings = MutableStateFlow(state)
    val settings: StateFlow<State> = _settings.asStateFlow()

    private var backgroundAtMs: Long? = null

    private fun save(next: State) {
        state = next
        _settings.value = next
        slot.write(json.encodeToString(State.serializer(), next))
    }

    val enabled: Boolean get() = state.enabled

    /** Сповіщення не мають права казати ні хто, ні що. */
    fun hidesNotificationContent(): Boolean = state.hidesText

    // ── код ──────────────────────────────────────────────────────────────

    /** Увімкнути замок або змінити код. Код — лише цифри, щонайменше [MIN_DIGITS]. */
    fun setPasscode(code: String): Boolean {
        if (!isValidCode(code)) return false
        val salt = ByteArray(16).also(random::nextBytes)
        save(state.copy(enabled = true, salt = b64(salt), hash = b64(derive(code, salt)), failures = 0, waitUntilMs = 0L))
        _locked.value = false
        return true
    }

    fun unlock(code: String): Attempt {
        val now = clock()
        if (now < state.waitUntilMs) return Attempt.Wait(state.waitUntilMs)
        if (!matches(code)) {
            val failures = state.failures + 1
            val wait = if (failures >= FREE_FAILURES) now + PAUSE_MS * (1L shl (failures - FREE_FAILURES).coerceAtMost(10)) else 0L
            save(state.copy(failures = failures, waitUntilMs = wait))
            return if (wait > 0) Attempt.Wait(wait) else Attempt.Wrong
        }
        save(state.copy(failures = 0, waitUntilMs = 0L))
        _locked.value = false
        return Attempt.Opened
    }

    /** Відбиток прийнято системою (BiometricPrompt) — відчинити, якщо він дозволений. */
    fun unlockByBiometric(): Boolean {
        if (!state.enabled || !state.biometric) return false
        save(state.copy(failures = 0, waitUntilMs = 0L))
        _locked.value = false
        return true
    }

    /** Вимкнути — лише знаючи код. */
    fun disable(code: String): Boolean {
        if (!state.enabled || !matches(code)) return false
        save(state.copy(enabled = false, salt = "", hash = "", biometric = false, failures = 0, waitUntilMs = 0L))
        _locked.value = false
        return true
    }

    fun setBiometric(on: Boolean) {
        if (state.enabled) save(state.copy(biometric = on))
    }

    fun setAutoLock(ms: Long) {
        if (ms in AUTO_LOCK_CHOICES) save(state.copy(autoLockMs = ms))
    }

    fun setHideNotificationText(on: Boolean) = save(state.copy(hideNotificationText = on))

    // ── життя застосунку ─────────────────────────────────────────────────

    fun onBackground() {
        backgroundAtMs = clock()
        if (state.enabled && state.autoLockMs == 0L) _locked.value = true
    }

    fun onForeground() {
        val since = backgroundAtMs ?: return
        backgroundAtMs = null
        if (state.enabled && clock() - since >= state.autoLockMs) _locked.value = true
    }

    fun lockNow() {
        if (state.enabled) _locked.value = true
    }

    private fun matches(code: String): Boolean {
        if (!state.enabled || state.salt.isEmpty()) return false
        val want = unb64(state.hash)
        return MessageDigest.isEqual(want, derive(code, unb64(state.salt)))
    }

    companion object {
        const val MIN_DIGITS = 4
        const val MAX_DIGITS = 12
        const val ITERATIONS = 120_000
        const val FREE_FAILURES = 5
        const val PAUSE_MS = 60_000L

        /** Одразу · 1 хв · 5 хв · 1 год. */
        val AUTO_LOCK_CHOICES = listOf(0L, 60_000L, 5 * 60_000L, 60 * 60_000L)

        fun isValidCode(code: String): Boolean = code.length in MIN_DIGITS..MAX_DIGITS && code.all { it in '0'..'9' }

        private fun derive(code: String, salt: ByteArray): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256))
                .encoded

        private fun b64(raw: ByteArray) = Base64.getEncoder().encodeToString(raw)
        private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
