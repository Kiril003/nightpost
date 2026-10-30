package local.phantom.companion.data.intent

import android.content.Context
import local.phantom.companion.core.net.intent.IntentClient
import local.phantom.companion.core.net.intent.IntentGateway

// Seeker бере маніфест store, а там SMS-дозволів немає: прохання їдуть лише сховком.
internal object IntentSmsFlavor {
    const val SMS = false

    fun port(context: Context, settings: IntentSettings): IntentClient.SmsPort? = null

    /** Домашнього шлюзу в цій збірці немає: він шле SMS. */
    fun sender(context: Context): IntentGateway.SmsOut? = null
}
