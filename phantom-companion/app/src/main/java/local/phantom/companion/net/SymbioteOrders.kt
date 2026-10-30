package local.phantom.companion.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.data.BodyLadder

/**
 * Рішення виконавця: драбина ДО дії, «так» від людини, дзвін до зупинки.
 *
 * Без Android — самі руки дає [Body], тож кожне правило перевіряється
 * звичайним тестом.
 */
class SymbioteOrders(
    private val ladder: BodyLadder,
    private val body: Body,
    private val scope: CoroutineScope,
    private val ringGapMs: Long = RING_GAP_MS,
    private val findCapMs: Long = FIND_CAP_MS,
    private val askTimeoutMs: Long = ASK_TIMEOUT_MS,
) {
    interface Body {
        suspend fun perform(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn
        suspend fun askYes(command: PhantomApi.SymbioteCommand, what: String): Boolean
        fun suggest(command: PhantomApi.SymbioteCommand, what: String)
        fun report(what: String)
        suspend fun ringOnce()
        fun finding(on: Boolean)
    }

    private var finder: Job? = null
    private val asks = mutableListOf<Job>()
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    suspend fun handle(
        command: PhantomApi.SymbioteCommand,
        post: suspend (PhantomApi.ResultIn) -> Unit,
    ) {
        val what = BodyLadder.RULES[command.verb]?.title ?: "це"
        when (val verdict = ladder.judge(command.verb)) {
            is BodyLadder.Verdict.Refuse -> post(answer(command, false, verdict.why, "refused"))
            BodyLadder.Verdict.Suggest -> {
                body.suggest(command, what)
                post(answer(command, false, "Запропоновано людині на телефоні", "suggested"))
            }
            BodyLadder.Verdict.Ask -> {
                post(answer(command, false, "Чекає на «так» на телефоні", "asked"))
                val job = scope.launch {
                    val yes = try {
                        withTimeoutOrNull(askTimeoutMs) { body.askYes(command, what) } == true
                    } catch (halted: CancellationException) {
                        withContext(NonCancellable) {
                            post(answer(command, false, "Усе зупинено на телефоні", "stopped"))
                        }
                        throw halted
                    }
                    post(
                        when {
                            ladder.stopped.value ->
                                answer(command, false, "Усе зупинено на телефоні", "stopped")
                            !yes -> answer(command, false, "Людина сказала «ні»", "declined")
                            else -> run(command)
                        },
                    )
                }
                synchronized(asks) {
                    asks.removeAll { !it.isActive }
                    asks += job
                }
            }
            is BodyLadder.Verdict.Do -> {
                val result = run(command)
                post(result)
                if (verdict.report && result.ok) body.report(what)
            }
        }
    }

    private suspend fun run(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn =
        when (command.verb) {
            "phone.find" -> startFinding(command)
            "phone.stop" -> {
                stopRinging()
                answer(command, true, "Замовк", "done")
            }
            else -> body.perform(command)
        }

    private fun startFinding(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
        if (finder?.isActive != true) {
            _ringing.value = true
            finder = scope.launch {
                body.finding(true)
                try {
                    withTimeoutOrNull(findCapMs) {
                        while (isActive && !ladder.stopped.value) {
                            body.ringOnce()
                            withTimeoutOrNull(ringGapMs) { ladder.stopped.first { it } }
                        }
                    }
                } finally {
                    _ringing.value = false
                    body.finding(false)
                }
            }
        }
        return answer(command, true, "Дзвоню, доки телефон не знайдуть", "done")
    }

    /** Людина торкнулась «Знайшов» або ПК сказав «перестань». */
    fun stopRinging() {
        finder?.cancel()
        finder = null
        _ringing.value = false
    }

    /** «Зупинити все»: дзвін і всі прохання, що чекають на «так», гаснуть. */
    fun halt() {
        stopRinging()
        synchronized(asks) {
            asks.forEach { it.cancel() }
            asks.clear()
        }
    }

    private fun answer(
        command: PhantomApi.SymbioteCommand,
        ok: Boolean,
        detail: String,
        status: String,
    ) = PhantomApi.ResultIn(
        command_id = command.id,
        ok = ok,
        detail = detail,
        payload = mapOf("status" to status),
    )

    companion object {
        const val RING_GAP_MS = 2_500L

        /** Запобіжник для батареї: загублений телефон не дзвонить до смерті. */
        const val FIND_CAP_MS = 15 * 60_000L
        const val ASK_TIMEOUT_MS = 90_000L
    }
}
