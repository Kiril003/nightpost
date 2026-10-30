package local.phantom.companion.core.context.nodes

import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.context.decision.DecisionNode
import local.phantom.companion.core.context.decision.NodeVote

/**
 * Influences state based on the active application.
 * GHOST for privacy apps, FOCUS for tools, DREAM for media.
 */
class ActiveAppNode : DecisionNode {
    
    private val ghostApps = setOf(
        "com.android.settings",
        "org.thoughtcrime.securesms", // Signal
        "com.wallet",
        "local.phantom.companion.ghost"
    )
    
    private val focusApps = setOf(
        "com.google.android.apps.docs",
        "com.microsoft.office.word",
        "com.jetbrains.upsource"
    )

    override suspend fun evaluate(input: ContextInput): NodeVote {
        val pkg = input.activeAppPackage ?: return NodeVote(SystemState.FOCUS, 0f, "")
        
        return when {
            pkg in ghostApps -> 
                NodeVote(SystemState.GHOST, 0.8f, "відкрито конфіденційний додаток")
            
            pkg in focusApps -> 
                NodeVote(SystemState.FOCUS, 0.7f, "робота з документами/кодом")
            
            pkg.contains("youtube") || pkg.contains("netflix") ->
                NodeVote(SystemState.DREAM, 0.5f, "перегляд медіа")
                
            else -> NodeVote(SystemState.FOCUS, 0f, "")
        }
    }
}
