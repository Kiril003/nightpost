package local.phantom.companion.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.self.TurnClock
import local.phantom.companion.self.TurnClock.Mark
import local.phantom.companion.self.TurnClock.Source
import local.phantom.companion.ui.messenger.AgencySessionManager
import local.phantom.companion.ui.messenger.WebMessengerAssistantLive

/**
 * Лише debug: хід у чаті PHANTOM з adb — тим самим шляхом, що й лист із веб-месенджера
 * (`WebMessengerBridge.append` → `ingestDirect` → `dispatchPhantomDialogue`), — і мітки
 * [TurnClock] на ньому. Захищено `android.permission.DUMP` — надіслати може тільки `adb shell`.
 *
 *   adb shell am broadcast -n local.phantom.companion.debug/local.phantom.companion.debug.ChatProbeReceiver \
 *     --es cmd ask --es text "Привіт" --es label greet --ei timeout_s 120
 *   … --es cmd report      # p50/p95 за джерелом, JSON
 *   … --es cmd reset
 *
 * Відповідь — у logcat під тегом [TurnClock.TAG] (`PhantomTurn`). Вміст відповіді не
 * пишеться — лише довжина.
 *
 * Що проба бачить сама, а що лише виводить:
 * - [Mark.FIRST_TOKEN] — перша фаза WRITING з текстом у `agencyManager.live`;
 * - [Mark.FIRST_VISIBLE] — перші слова, які хост віддав сторінці
 *   ([WebMessengerAssistantLive.shown]); месенджер закритий — мить, коли лист лягає в журнал;
 * - [Mark.DECIDED] проба не ставить: момент рішення знає лише маршрут (L6/L7 —
 *   `TurnClock.process.decide`);
 * - джерело: `reflex` — спостережено (відповідь без жодної фази моделі); `pc`/`cloud`/
 *   `offline` — виведено з готовності маршрутів на `bridge_in` за порядком BrainChain
 *   (`basis=routes`), доки маршрут не назве себе сам.
 */
class ChatProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PhantomApp ?: return
        when (intent.getStringExtra("cmd") ?: "ask") {
            "report" -> Log.i(TurnClock.TAG, "report " + TurnClock.process.report().toJson())
            "reset" -> {
                TurnClock.process.clear()
                Log.i(TurnClock.TAG, "reset")
            }
            "ask" -> {
                val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
                val label = intent.getStringExtra("label").orEmpty()
                val timeoutS = intent.getIntExtra("timeout_s", 120).coerceIn(5, 300)
                // Хід триває довше за час, який система дає приймачу, тож goAsync тут
                // не тримається: хід живе в області процесу, а не приймача.
                scope.launch { ask(app, text, label, timeoutS) }
            }
        }
    }

    private suspend fun ask(app: PhantomApp, text: String, label: String, timeoutS: Int) = busy.withLock {
        val c = app.container
        val agency = c.agencyManager
        if (agency.live.value != null) {
            Log.w(TurnClock.TAG, "busy label=$label — інший хід PHANTOM ще йде")
            return@withLock
        }
        // Готовність маршрутів — ДО bridge_in: проба не має красти час ходу.
        val pcReady = runCatching { c.pcBrainRoute.availableRoutes().isNotEmpty() }.getOrDefault(false)
        val cloudReady = runCatching { c.geminiRoute.availableRoutes().isNotEmpty() }.getOrDefault(false)
        val me = c.mesh.selfIdFlow.value.ifBlank { "operator_me" }
        val clock = TurnClock.process
        val turnId = "probe-" + UUID.randomUUID().toString().take(8)

        val seen = Seen()
        val watcher = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            agency.live.collect { live ->
                if (live == null) {
                    if (seen.messageId != null) seen.ended = true
                    return@collect
                }
                if (seen.messageId == null && live.conversationId == AgencySessionManager.PHANTOM_AGENT_ID) {
                    seen.messageId = live.messageId
                }
                if (live.messageId != seen.messageId) return@collect
                when (live.phase) {
                    AgencySessionManager.Phase.WRITING -> if (live.text.isNotEmpty()) {
                        seen.model = true
                        clock.mark(turnId, Mark.FIRST_TOKEN)
                    }
                    AgencySessionManager.Phase.TOOL -> seen.model = true
                    AgencySessionManager.Phase.FAILED -> seen.failure = live.reason.ifBlank { "failed" }
                    AgencySessionManager.Phase.THINKING -> Unit
                }
            }
        }

        val page = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            WebMessengerAssistantLive.shown.collect { f ->
                if (f != null && f.messageId == seen.messageId && f.phase == "writing" && f.text.isNotEmpty()) {
                    clock.mark(turnId, Mark.FIRST_VISIBLE)
                }
            }
        }

        clock.begin(turnId, label)
        val asked = PeerMessage(
            id = "msg_" + UUID.randomUUID().toString().take(12),
            fromId = me,
            toId = AgencySessionManager.PHANTOM_AGENT_ID,
            body = text,
            sentAtMs = System.currentTimeMillis(),
            state = PeerMessage.State.DELIVERED,
        )
        c.mesh.ingestDirect(asked)
        agency.dispatchPhantomDialogue(text, me, questionId = asked.id)

        // Кінець ходу — лист із тим самим id у журналі, поразка в `live` або кінець `live`
        // без листа (службова команда відповіла іншим листом).
        withTimeoutOrNull(timeoutS * 1000L) {
            combine(agency.live, c.mesh.outbox) { _, box -> box }.first { box ->
                val id = seen.messageId
                id != null && (box.any { it.id == id } || seen.failure != null || seen.ended)
            }
        }
        val reply = seen.messageId?.let { id -> c.mesh.outbox.value.firstOrNull { it.id == id } }
        watcher.cancel()
        page.cancel()
        when {
            reply != null -> {
                clock.mark(turnId, Mark.FIRST_VISIBLE)
                clock.finish(turnId, ok = true)
            }
            seen.failure != null -> clock.finish(turnId, ok = false, reason = seen.failure.orEmpty())
            seen.ended -> clock.finish(turnId, ok = false, reason = "ended_without_reply")
            else -> clock.finish(turnId, ok = false, reason = "timeout_${timeoutS}s")
        }
        when {
            !seen.model && reply != null -> clock.attribute(turnId, Source.REFLEX, basis = "observed")
            cloudReady -> clock.attribute(turnId, Source.CLOUD, basis = "routes")
            pcReady -> clock.attribute(turnId, Source.PC, basis = "routes")
            else -> clock.attribute(turnId, Source.OFFLINE, basis = "routes")
        }
        Log.i(
            TurnClock.TAG,
            clock.line(turnId) + " pc_ready=$pcReady cloud_ready=$cloudReady chars=${reply?.body?.length ?: 0}",
        )
    }

    /** Що проба побачила в `live`: пишеться з потоку спостерігача, читається з ходу. */
    private class Seen {
        @Volatile var messageId: String? = null
        @Volatile var model = false
        @Volatile var failure: String? = null
        @Volatile var ended = false
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val busy = Mutex()
    }
}
