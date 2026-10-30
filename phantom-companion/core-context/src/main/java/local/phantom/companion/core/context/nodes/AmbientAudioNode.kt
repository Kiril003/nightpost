package local.phantom.companion.core.context.nodes

import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.context.decision.DecisionNode
import local.phantom.companion.core.context.decision.NodeVote

class AmbientAudioNode : DecisionNode {

    override suspend fun evaluate(input: ContextInput): NodeVote {
        // High-priority check: YAMNet detected threats
        val threats = input.detectedSounds.filter { 
            it in setOf("Siren", "Emergency vehicle", "Police car (siren)", "Ambulance (siren)", "Fire engine (siren)", "Civil defense siren", "Crying", "Baby cry", "Screaming")
        }
        
        if (threats.isNotEmpty()) {
            return NodeVote(
                state = SystemState.SENTINEL,
                weight = 0.9f,
                rationale = "чую загрозу: ${threats.distinct().joinToString(", ")}"
            )
        }

        val db = input.micRmsDb
            ?: return NodeVote(SystemState.FOCUS, 0f, "")

        return when {
            db < -50f -> NodeVote(SystemState.FOCUS, 0.4f, "тихо — фокусоване середовище")
            db <= -30f -> NodeVote(SystemState.SENTINEL, 0.3f, "розмовне середовище")
            else -> NodeVote(SystemState.GHOST, 0.4f, "галасливо — режим примари")
        }
    }
}
