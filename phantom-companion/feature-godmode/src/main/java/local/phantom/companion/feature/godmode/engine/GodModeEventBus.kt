package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Unified event definition for Tier 2 GodMode (System -> AI).
 */
sealed class GodModeEvent {
    data class Notification(val pkg: String, val title: String, val text: String) : GodModeEvent()
    data class NotificationBurst(val pkg: String, val combinedText: String) : GodModeEvent()
    data class ScreenChanged(val pkg: String, val className: String) : GodModeEvent()
    data class ScreenSnapshot(val pkg: String, val textContent: String) : GodModeEvent()
}

/**
 * Commands from AI -> System.
 */
sealed class GodModeCommand {
    data class ClickText(val text: String) : GodModeCommand()
    data class TypeText(val targetNodeText: String, val input: String) : GodModeCommand()
    data class GlobalAction(val action: Int) : GodModeCommand()
}

/**
 * Central bus for system events. Android Services push into this bus,
 * and the TaskerEngine/AI Router consume from it.
 */
object GodModeEventBus {
    private val _events = MutableSharedFlow<GodModeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GodModeEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GodModeCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<GodModeCommand> = _commands.asSharedFlow()

    fun push(event: GodModeEvent) {
        _events.tryEmit(event)
    }

    fun execute(command: GodModeCommand) {
        _commands.tryEmit(command)
    }
}
