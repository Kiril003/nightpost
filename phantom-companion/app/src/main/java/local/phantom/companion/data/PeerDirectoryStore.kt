package local.phantom.companion.data

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.peer.PeerDirectory
import local.phantom.companion.core.net.peer.PeerDirectoryClient
import local.phantom.companion.core.net.peer.PeerInvite

/**
 * Моє імʼя для пошуку — і єдине місце, де телефон розмовляє з довідником.
 *
 * З коробки не опубліковано, і це не обережність, а умова: доки власник сам не
 * назве імʼя, на сервері немає нічого, що повʼязувало б цей ключ зі словом.
 * Знімається так само одним дотиком, і локальний запис гасне разом із записом
 * на сервері — інакше екран показував би імʼя, якого вже нема.
 *
 * Знайдене звідси НЕ вважається звіреним: підпис доводить, що запис склав
 * власник ключа, а не що ключ належить тій людині, яку шукали. Тому контакт
 * лягає в реєстр найслабшим джерелом ключа, і звірка ключів лишається.
 */
class PeerDirectoryStore(
    context: Context,
    private val baseUrl: String,
    private val myInvite: suspend () -> PeerInvite.Invite?,
    private val signingKey: suspend () -> ByteArray?,
    private val http: HttpClient? = null,
) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _handle = MutableStateFlow(prefs.getString(KEY_HANDLE, null))

    /** null — не опубліковано. Це типовий стан. */
    val handle: StateFlow<String?> = _handle.asStateFlow()

    private val _untilSeconds = MutableStateFlow(prefs.getLong(KEY_UNTIL, 0L))

    /** Доки сервер тримає запис. Нуль, коли нічого не опубліковано. */
    val untilSeconds: StateFlow<Long> = _untilSeconds.asStateFlow()

    sealed interface Outcome {
        data class Published(val handle: String, val untilSeconds: Long) : Outcome
        data object Withdrawn : Outcome

        /** Імʼя вже за кимось. */
        data object Taken : Outcome

        /** Не імʼя: не та абетка або не та довжина. */
        data object BadHandle : Outcome

        /** Телефон ще не має власного ключа — публікувати нема чого. */
        data object NoIdentity : Outcome

        /** Складання без адреси сервера: назовні не пішло нічого. */
        data object NoAddress : Outcome

        /** Сервер живий, довідник на ньому ще не відкрито — його власне слово. */
        data class Closed(val detail: String) : Outcome

        data object Refused : Outcome
        data object Unreachable : Outcome
    }

    sealed interface AutoStep {
        data object None : AutoStep
        data object Withdraw : AutoStep
        data class Publish(val name: String) : AutoStep
    }

    sealed interface Found {
        data class One(
            val handle: String,
            val invite: PeerInvite.Invite,
            val compact: String,
        ) : Found

        /** Сервер відповів: такого імені немає. */
        data object None : Found

        data object BadHandle : Found

        /** Запис є, але він не витримав перевірки. Це не «нікого немає». */
        data class NotProven(val why: PeerDirectory.Verdict) : Found

        data object NoAddress : Found

        /** Довідник на сервері ще не відкрито, і сервер сам сказав, чому. */
        data class Closed(val detail: String) : Found

        data object Unreachable : Found
    }

    suspend fun lookup(raw: String?): Found = withContext(Dispatchers.IO) {
        val name = PeerDirectory.normalise(raw) ?: return@withContext Found.BadHandle
        val client = client() ?: return@withContext Found.NoAddress
        when (val answer = client.lookup(name)) {
            is PeerDirectoryClient.Lookup.Found -> {
                val verdict = PeerDirectory.verify(answer.record, System.currentTimeMillis())
                if (verdict is PeerDirectory.Verdict.Trusted) {
                    Found.One(name, verdict.invite, answer.record.invite)
                } else {
                    Found.NotProven(verdict)
                }
            }
            PeerDirectoryClient.Lookup.None -> Found.None
            is PeerDirectoryClient.Lookup.Closed -> Found.Closed(answer.detail)
            PeerDirectoryClient.Lookup.Unreachable -> Found.Unreachable
        }
    }

    suspend fun publish(raw: String?): Outcome = withContext(Dispatchers.IO) {
        val name = PeerDirectory.normaliseForPublish(raw) ?: return@withContext Outcome.BadHandle
        val client = client() ?: return@withContext Outcome.NoAddress
        val invite = myInvite() ?: return@withContext Outcome.NoIdentity
        val key = signingKey() ?: return@withContext Outcome.NoIdentity
        val outcome = client.publish(name, invite, key, System.currentTimeMillis())
        // Записуємо ЛИШЕ те, що сервер підтвердив. Імʼя, збережене наперед,
        // показувало б власникові опубліковане імʼя, якого в довіднику нема.
        if (outcome is PeerDirectoryClient.Outcome.Published) {
            remember(outcome.handle, outcome.untilSeconds)
            prefs.edit().remove(KEY_OPTED_OUT).apply()
        }
        when (outcome) {
            is PeerDirectoryClient.Outcome.Published ->
                Outcome.Published(outcome.handle, outcome.untilSeconds)
            PeerDirectoryClient.Outcome.Taken -> Outcome.Taken
            is PeerDirectoryClient.Outcome.Closed -> Outcome.Closed(outcome.detail)
            PeerDirectoryClient.Outcome.Refused -> Outcome.Refused
            PeerDirectoryClient.Outcome.Unreachable -> Outcome.Unreachable
            PeerDirectoryClient.Outcome.Withdrawn -> Outcome.Refused
        }
    }

    suspend fun withdraw(): Outcome = withContext(Dispatchers.IO) {
        // Вибір записується до запиту: якщо мережі нема, авто-публікація все
        // одно не має права повернути нік, який людина щойно зняла.
        prefs.edit().putBoolean(KEY_OPTED_OUT, true).apply()
        val name = _handle.value ?: return@withContext Outcome.Withdrawn
        val client = client() ?: return@withContext Outcome.NoAddress
        val key = signingKey() ?: return@withContext Outcome.NoIdentity
        when (val outcome = client.withdraw(name, key, System.currentTimeMillis())) {
            PeerDirectoryClient.Outcome.Withdrawn -> {
                remember(null, 0L)
                Outcome.Withdrawn
            }
            is PeerDirectoryClient.Outcome.Closed -> Outcome.Closed(outcome.detail)
            PeerDirectoryClient.Outcome.Unreachable -> Outcome.Unreachable
            PeerDirectoryClient.Outcome.Taken,
            PeerDirectoryClient.Outcome.Refused,
            is PeerDirectoryClient.Outcome.Published,
            -> Outcome.Refused
        }
    }

    val optedOut: Boolean get() = prefs.getBoolean(KEY_OPTED_OUT, false)

    /**
     * Нік задано — людину можна знайти. Викликається на старті й раз на
     * [AUTO_EVERY_MS]; сама вирішує, чи є що робити ([autoStep]).
     */
    suspend fun autoPublish(nick: String?): Outcome? {
        val step = autoStep(
            handle = _handle.value,
            untilSeconds = _untilSeconds.value,
            optedOut = optedOut,
            tried = prefs.getBoolean(KEY_AUTO_TRIED, false),
            nick = nick,
            nowMs = System.currentTimeMillis(),
        )
        return when (step) {
            AutoStep.None -> null
            AutoStep.Withdraw -> withdraw()
            is AutoStep.Publish -> publish(step.name).also { out ->
                // Нічийний збій (мережа, закритий довідник) не рахується спробою:
                // на наступному старті пробуємо знову. Відмова по суті — рахується.
                val final = out is Outcome.Published || out == Outcome.Taken ||
                    out == Outcome.BadHandle || out == Outcome.Refused
                if (final && _handle.value == null) prefs.edit().putBoolean(KEY_AUTO_TRIED, true).apply()
            }
        }
    }

    private fun remember(name: String?, untilSeconds: Long) {
        if (name == null) {
            prefs.edit().remove(KEY_HANDLE).remove(KEY_UNTIL).apply()
        } else {
            prefs.edit().putString(KEY_HANDLE, name).putLong(KEY_UNTIL, untilSeconds).apply()
        }
        _handle.value = name
        _untilSeconds.value = if (name == null) 0L else untilSeconds
    }

    // Один клієнт на процес: кожен виклик з власним HttpClient лишав би по
    // пулу зʼєднань на дотик.
    @Volatile
    private var cached: PeerDirectoryClient? = null

    private fun client(): PeerDirectoryClient? =
        cached ?: PeerDirectoryClient.open(baseUrl, http)?.also { cached = it }

    companion object {
        private const val FILE_NAME = "phantom_directory_handle_v1"
        private const val KEY_HANDLE = "handle"
        private const val KEY_UNTIL = "until"
        private const val KEY_OPTED_OUT = "opted_out"
        private const val KEY_AUTO_TRIED = "auto_tried"

        const val AUTO_EVERY_MS = 6L * 60 * 60 * 1000

        /**
         * Що робити зараз. Явне «ніхто» перемагає все; опублікований нік
         * поновлюємо до спливу; неопублікований із придатним ніком — один раз.
         */
        fun autoStep(
            handle: String?,
            untilSeconds: Long,
            optedOut: Boolean,
            tried: Boolean,
            nick: String?,
            nowMs: Long,
        ): AutoStep = when {
            optedOut -> if (handle != null) AutoStep.Withdraw else AutoStep.None
            handle != null ->
                if (expired(untilSeconds, nowMs) || dueForRenewal(untilSeconds, nowMs)) {
                    AutoStep.Publish(handle)
                } else {
                    AutoStep.None
                }
            tried -> AutoStep.None
            else -> PeerDirectory.normaliseForPublish(nick)?.let { AutoStep.Publish(it) } ?: AutoStep.None
        }

        /**
         * Чи пережив локальний запис свій строк на СЕРВЕРІ.
         *
         * Досі [untilSeconds] лежав у налаштуваннях і не звірявся з годинником
         * ніколи: рядок показував «@name · до 3 жовтня» і третього листопада
         * теж, а дії «опублікувати знову» не було взагалі — імʼя на екрані
         * було, у довіднику його вже не було.
         *
         * Нуль — нічого не опубліковано, і це не «строк вийшов».
         */
        fun expired(untilSeconds: Long, nowMs: Long): Boolean =
            untilSeconds > 0L && untilSeconds * 1_000L <= nowMs

        /** Сервер тримає нік 30 днів; без поновлення він звільняється для чужого. */
        fun dueForRenewal(untilSeconds: Long, nowMs: Long): Boolean =
            untilSeconds > 0L && untilSeconds * 1_000L - nowMs < RENEW_AHEAD_MS

        const val RENEW_AHEAD_MS = 7L * 24 * 60 * 60 * 1000
    }
}
