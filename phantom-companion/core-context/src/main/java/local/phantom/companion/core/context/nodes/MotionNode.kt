package local.phantom.companion.core.context.nodes

import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.context.decision.DecisionNode
import local.phantom.companion.core.context.decision.NodeVote

class MotionNode : DecisionNode {

    override suspend fun evaluate(input: ContextInput): NodeVote {
        val mag = input.accelMagnitude
            ?: return NodeVote(SystemState.FOCUS, 0f, "")

        return when {
            mag < 0.3f -> NodeVote(SystemState.FOCUS, 0f, "")
            mag <= 2.0f -> NodeVote(SystemState.FOCUS, 0.3f, "невелика активність — фокус")
            else -> NodeVote(SystemState.SENTINEL, 0.5f, "активний рух — пильність")
        }
    }
}
