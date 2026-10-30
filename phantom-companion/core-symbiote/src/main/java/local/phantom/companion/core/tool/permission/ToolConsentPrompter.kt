package local.phantom.companion.core.tool.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Місток між воротами, які чекають, і екраном, який питає.
 *
 * [pending] має рівно одного читача — картку згоди у Стрічці. Якщо її не
 * показують, [ToolPermissionGate] відмовляє, а не виконує мовчки.
 *
 * [mutex] серіалізує питання: модель уміє просити кілька інструментів за
 * зворот, і два питання в одному полі затерли б одне одного, лишивши другий
 * виклик висіти назавжди.
 */
class ToolConsentPrompter {

    data class Pending(
        val ask: ToolAsk,
        private val answer: CompletableDeferred<Decision>,
    ) {
        fun answerWith(decision: Decision) {
            answer.complete(decision)
        }
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val mutex = Mutex()

    val closeable: () -> Unit = SymbioteStop.registerPrompter(this)

    fun dispose() {
        closeable()
        withdraw()
    }

    /** Відкликає поточне питання без збереження NEVER (при натисканні «Зупинити все»). */
    fun withdraw() {
        _pending.value?.answerWith(Decision.ASK)
    }

    /** Показує питання й чекає на дотик. Повертає рішення оператора. */
    suspend fun request(ask: ToolAsk): Decision {
        val genAtEntry = SymbioteStop.generation
        if (SymbioteStop.stopped.value) return Decision.ASK
        return mutex.withLock {
            if (SymbioteStop.stopped.value || SymbioteStop.generation != genAtEntry) {
                return@withLock Decision.ASK
            }
            val answer = CompletableDeferred<Decision>()
            val pendingAsk = Pending(ask, answer)
            _pending.value = pendingAsk
            if (SymbioteStop.stopped.value || SymbioteStop.generation != genAtEntry) {
                answer.complete(Decision.ASK)
            }
            try {
                val decision = answer.await()
                if (SymbioteStop.stopped.value || SymbioteStop.generation != genAtEntry) {
                    Decision.ASK
                } else {
                    decision
                }
            } finally {
                // Скасування турна (оператор пішов з екрана) мусить прибрати картку,
                // інакше вона переживе свій виклик і наступна відповідь піде в нікуди.
                _pending.compareAndSet(pendingAsk, null)
            }
        }
    }
}
