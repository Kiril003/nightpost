package local.phantom.companion.core.context

/**
 * Coarse-grained operator-attention state. Mirrors PHANTOM OS
 * SystemState; the companion can either receive this from server
 * pushes (PhantomLink "state" channel) or derive it locally via
 * [ContextEngine].
 */
enum class SystemState { SHADOW, FOCUS, DIALOGUE, SENTINEL, GHOST, DREAM }
