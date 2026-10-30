package local.phantom.companion.data.intent

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.net.intent.IntentBrain
import local.phantom.companion.core.net.intent.IntentClient
import local.phantom.companion.core.net.intent.IntentGateway

/**
 * Збірка `field`: SMS-смуга Намірів до мого шлюзу (розд. 07 §3.3).
 *
 * Текстова SMS, base64 кадру (`IntentSms`): data SMS на порт пропускають не
 * всі оператори, а текст проходить скрізь, де проходить SMS.
 */
internal object IntentSmsFlavor {
    const val SMS = true

    fun port(context: Context, settings: IntentSettings): IntentClient.SmsPort = AndroidSmsPort(context.applicationContext, settings)

    /** SMS шлюзу на номер того, хто питав. */
    fun sender(context: Context): IntentGateway.SmsOut {
        val app = context.applicationContext
        return IntentGateway.SmsOut { number, text -> sendSms(app, number, text) }
    }
}

internal fun sendSms(context: Context, number: String, text: String): Boolean {
    if (number.isEmpty()) return false
    if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    } ?: return false
    return runCatching {
        val parts = manager.divideMessage(text)
        if (parts.size == 1) {
            manager.sendTextMessage(number, null, text, null, null)
        } else {
            manager.sendMultipartTextMessage(number, null, parts, null, null)
        }
        true
    }.getOrDefault(false)
}

internal class AndroidSmsPort(private val context: Context, private val settings: IntentSettings) : IntentClient.SmsPort {

    private fun permitted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Смуга є, лише коли видано обидва дозволи: відправити без змоги прийняти відповідь — марно. */
    override fun policy(): IntentBrain.SmsPolicy =
        settings.policy(permitted(Manifest.permission.SEND_SMS) && permitted(Manifest.permission.RECEIVE_SMS))

    override fun send(text: String): Boolean = sendSms(context, settings.gatewayNumber, text)
}

/**
 * SMS від мого шлюзу → клієнт Намірів; SMS від моїх телефонів на домашньому
 * шлюзі → шлюз. Чужі SMS не читаються далі номера відправника: склеювати й
 * розбирати чуже листування нам нема чого.
 *
 * Батарея: шлюз працює лише у вікні приймання ([goAsync], до [GATEWAY_WINDOW_MS]);
 * своїх wakelock'ів немає. Не встиг по LAN — кадр уже лежить у черзі сховку,
 * і відповідь приїде листом, коли прийде.
 */
class IntentSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val settings = IntentSettings(context)
        // Склеєна SMS приходить частинами в одному мовленні — у порядку частин.
        val fromGateway = messages.filter { settings.isGateway(it.originatingAddress) }
        if (fromGateway.isNotEmpty()) IntentInbox.deliver(fromGateway.joinToString("") { it.messageBody.orEmpty() })
        if (!settings.homeGateway) return
        val allowed = settings.allowed
        val byOwner = messages.groupBy { it.originatingAddress.orEmpty() }
            .filterKeys { from -> allowed.any { mine -> sameNumber(mine, from) } }
        if (byOwner.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(GATEWAY_WINDOW_MS) {
                    for ((from, parts) in byOwner) GatewayInbox.deliver(from, parts.joinToString("") { it.messageBody.orEmpty() })
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }
        val y = b.filter { it.isDigit() }
        return x.length >= 9 && y.length >= 9 && x.takeLast(9) == y.takeLast(9)
    }

    private companion object {
        /** Вікно приймання: менше за 10 с, після яких система вважає приймач завислим. */
        const val GATEWAY_WINDOW_MS = 9_000L
    }
}
