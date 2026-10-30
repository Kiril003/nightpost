package local.phantom.companion.core.context.nodes

import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.context.decision.DecisionNode
import local.phantom.companion.core.context.decision.NodeVote

class TimeOfDayNode : DecisionNode {

    override suspend fun evaluate(input: ContextInput): NodeVote {
        val hour = input.localHour
        val isWeekend = input.localDayOfWeek >= 6  // Sat=6, Sun=7

        return when {
            // Night hours (22:00–06:00) — any day
            hour >= 22 || hour < 6 ->
                NodeVote(SystemState.GHOST, 0.7f, "ніч — режим примари")

            // Transition edge just after night ends (06:00–07:00)
            hour == 6 ->
                NodeVote(SystemState.SENTINEL, 0.3f, "перехідна година")

            // Transition edge just before night begins (21:00–22:00)
            hour == 21 ->
                NodeVote(SystemState.SENTINEL, 0.3f, "перехідна година")

            // Weekend daytime
            isWeekend ->
                NodeVote(SystemState.SENTINEL, 0.4f, "вихідний — пасивне спостереження")

            // Weekday working hours (07:00–21:00)
            else ->
                NodeVote(SystemState.FOCUS, 0.6f, "робочі години")
        }
    }
}
