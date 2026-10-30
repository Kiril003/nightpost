package local.phantom.companion.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.scheduled.ScheduledLetters
import local.phantom.companion.ui.messenger.WebMessengerBridge
import org.json.JSONObject

/**
 * Один будильник на найраніший відкладений лист. Після кожної зміни списку
 * (додано, скасовано, надіслано) і після перезавантаження ставиться наново.
 */
object ScheduledLetterAlarms {

    const val ACTION_DUE: String = "local.phantom.companion.action.SCHEDULED_LETTER_DUE"
    private const val REQUEST_CODE = 0x5C4D
    private const val TAG = "ScheduledLetters"

    /** Мить будильника: прострочений лист (телефон був вимкнений) іде одразу. */
    fun fireAt(letter: ScheduledLetters.Letter?, nowMs: Long): Long? =
        letter?.let { maxOf(it.dueAtMs, nowMs + 1_000L) }

    fun rearm(context: Context, letters: ScheduledLetters, nowMs: Long = System.currentTimeMillis()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        val at = fireAt(letters.next(), nowMs)
        runCatching {
            if (at == null) {
                am.cancel(pi)
            } else if (exactAllowed(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure { Log.w(TAG, "could not arm scheduled letter", it) }
    }

    private fun exactAllowed(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduledLetterReceiver::class.java).apply {
                action = ACTION_DUE
                data = Uri.parse("phantom://scheduled/due")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * Лист іде тим самим шляхом, що й набраний у композері, — через міст, тож
 * усі відмови (ключ змінився, контакту нема, група) ті самі й тими словами.
 */
object ScheduledLetterSend {

    fun body(letter: ScheduledLetters.Letter): String =
        JSONObject()
            .put("body", letter.text)
            .put("kind", "text")
            .put("client_id", letter.id)
            .apply { if (letter.quiet) put("quiet", true) }
            .toString()

    /** Null — пішов; інакше — причина з відповіді мосту. */
    fun outcome(reply: String): String? {
        val o = runCatching { JSONObject(reply) }.getOrNull() ?: return reply.take(200)
        if (o.optBoolean("ok")) return null
        return o.optString("detail").ifBlank { o.optString("code").ifBlank { "?" } }
    }

    suspend fun via(dispatch: suspend (String, String, String?) -> String, letter: ScheduledLetters.Letter): String? =
        outcome(dispatch("POST", "/messenger/conversations/${letter.conversationId}/messages", body(letter)))
}

class ScheduledLetterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledLetterAlarms.ACTION_DUE) return
        val container: AppContainer = runCatching { PhantomApp.container() }.getOrNull() ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        container.scope.launch {
            try {
                val bridge = WebMessengerBridge(container)
                container.scheduledLetters.fireDue(System.currentTimeMillis()) { ScheduledLetterSend.via(bridge::dispatch, it) }
            } catch (t: Throwable) {
                Log.w("ScheduledLetters", "scheduled send failed", t)
            } finally {
                ScheduledLetterAlarms.rearm(appContext, container.scheduledLetters)
                pending.finish()
            }
        }
    }
}

/**
 * «Коли буде в мережі»: лист без часу йде, щойно від людини приходить будь-який
 * кадр (реєстр посуває її `lastSeenMs`). Будильника тут нема — є сама людина.
 */
object ScheduledLetterOnline {

    /** Хто озвався між двома знімками реєстру. Перший знімок — нікого: старт застосунку не є «озвався». */
    fun cameBack(prev: Map<String, Long>?, now: Map<String, Long>): List<String> =
        if (prev == null) emptyList() else now.filter { (id, seen) -> seen > (prev[id] ?: Long.MAX_VALUE) }.keys.toList()

    fun start(container: AppContainer) {
        container.scope.launch {
            var prev: Map<String, Long>? = null
            container.peers.peers.collect { list ->
                val seen = list.associate { it.id to it.lastSeenMs }
                val back = cameBack(prev, seen)
                prev = seen
                for (id in back) {
                    if (!container.scheduledLetters.waitsForOnline(id)) continue
                    runCatching {
                        val bridge = WebMessengerBridge(container)
                        container.scheduledLetters.fireOnline(id) { ScheduledLetterSend.via(bridge::dispatch, it) }
                    }.onFailure { Log.w("ScheduledLetters", "when-online send failed", it) }
                }
            }
        }
    }
}
