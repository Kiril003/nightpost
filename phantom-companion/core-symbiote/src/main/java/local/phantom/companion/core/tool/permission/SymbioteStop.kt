package local.phantom.companion.core.tool.permission

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.agent.AgentMissionHub

/**
 * Процес-широкий вимикач «Зупинити все» для власного асистента телефона.
 *
 * Синхронізується з `BodyLadder` (плитка у шторці зупиняє і накази ПК,
 * і інструменти та агентні цикли самого телефона). Відновлення — лише явне ([resume]).
 */
object SymbioteStop {

    private val _stopped = MutableStateFlow(false)
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    private val _generation = AtomicLong(0L)
    val generation: Long get() = _generation.get()

    private val prompters = CopyOnWriteArraySet<ToolConsentPrompter>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun registerPrompter(prompter: ToolConsentPrompter): () -> Unit {
        prompters += prompter
        return { prompters -= prompter }
    }

    fun unregisterPrompter(prompter: ToolConsentPrompter) {
        prompters -= prompter
    }

    fun registerOnStop(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun unregisterOnStop(listener: () -> Unit) {
        listeners -= listener
    }

    fun stopAll() {
        _generation.incrementAndGet()
        _stopped.value = true
        prompters.forEach { it.withdraw() }
        AgentMissionHub.missions.value
            .filter { it.active }
            .forEach { AgentMissionHub.cancel(it.id) }
        listeners.forEach { runCatching { it() } }
    }

    fun resume() {
        _stopped.value = false
    }
}
