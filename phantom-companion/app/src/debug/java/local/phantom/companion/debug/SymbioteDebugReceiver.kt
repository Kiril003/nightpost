package local.phantom.companion.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.net.PhantomApi

/**
 * Лише debug: дієслово тіла з adb, крізь ту саму драбину, що й лист із ПК.
 * Захищено `android.permission.DUMP` — надіслати може тільки `adb shell`.
 *
 *   adb shell am broadcast -n local.phantom.companion.debug/local.phantom.companion.debug.SymbioteDebugReceiver \
 *     --es verb phone.state --es a.limit 3
 *
 * Відповідь — у logcat під тегом `SymbioteDebug`.
 */
class SymbioteDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val verb = intent.getStringExtra("verb") ?: return
        val args = intent.extras?.keySet().orEmpty()
            .filter { it.startsWith("a.") }
            .associate { it.removePrefix("a.") to intent.getStringExtra(it).orEmpty() }
        val app = context.applicationContext as? PhantomApp ?: return
        val command = PhantomApi.SymbioteCommand(id = "dbg-" + UUID.randomUUID(), verb = verb, args = args)
        val pending = goAsync()
        scope.launch {
            try {
                app.container.symbiote.handleLocal(command) { r ->
                    Log.i(TAG, "${command.verb} ok=${r.ok} detail=${r.detail} payload=${r.payload}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SymbioteDebug"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
