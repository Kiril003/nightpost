package local.phantom.companion.core.design.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

enum class GateAsk { NONE, REASON, ASKING, DENIED, BLOCKED }

/**
 * Один шлях до будь-якого дозволу: причина → системне вікно → спокійна картка
 * після відмови → «Відкрити налаштування», коли вікна Android більше не дасть.
 * Кожне прохання отримує рівно одну відповідь — веб-месенджер не висить.
 */
class PermissionGateModel {
    var ask: GateAsk = GateAsk.NONE
        private set

    private var answer: ((Boolean) -> Unit)? = null

    /**
     * [blocked] — Android більше не покаже вікна (відмовлено двічі, rationale
     * немає). FR-4 (ніч 09.10): після остаточної відмови наступний вхід знову
     * показував першу картку «Дозволити», а «Дозволити» вело в «Доступ
     * вимкнено» — зайвий дотик і неправда. Тепер такий вхід одразу каже, як є,
     * і пропонує налаштування.
     */
    fun request(granted: Boolean, blocked: Boolean = false, onAnswer: (Boolean) -> Unit) {
        if (granted) return onAnswer(true)
        answer?.let { previous -> answer = null; previous(false) }
        answer = onAnswer
        ask = if (blocked) GateAsk.BLOCKED else GateAsk.REASON
    }

    fun allow(): Boolean {
        if (ask != GateAsk.REASON && ask != GateAsk.DENIED) return false
        ask = GateAsk.ASKING
        return true
    }

    fun onResult(granted: Boolean, canAskAgain: Boolean) {
        if (ask != GateAsk.ASKING) return
        if (granted) {
            ask = GateAsk.NONE
            deliver(true)
        } else if (canAskAgain) {
            // FR-5 (ніч 09.10): після першої відмови одразу виринала картка «Ще
            // немає доступу» з тим самим «Дозволити» — навʼязливо. Людина сказала
            // «ні»: функція отримує відмову, а спитаємо знову, коли людина сама
            // знову туди піде.
            ask = GateAsk.NONE
            deliver(false)
        } else {
            ask = GateAsk.BLOCKED
        }
    }

    fun dismiss() {
        ask = GateAsk.NONE
        deliver(false)
    }

    fun openSettings() = dismiss()

    private fun deliver(granted: Boolean) {
        val pending = answer ?: return
        answer = null
        pending(granted)
    }
}

/**
 * «Більше не питатиме» видно лише після відповіді: вікна, якого ОС не показала,
 * до запуску не відрізнити від вікна, що ще покажеться (дозвіл «лише цього разу»
 * спливає без жодного rationale). Тож блок — це друга відмова поспіль без rationale.
 */
fun canAskAgain(granted: Boolean, rationale: Boolean, deniedBefore: Boolean): Boolean =
    granted || rationale || !deniedBefore

fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Мить, коли людина щойно відповіла на системне вікно дозволу.
 *
 * FR-5 (ніч 09.10): на мапі після «ні» на місце одразу виринала картка
 * сповіщень — застосунок повертався з системного вікна (onResume), і стартове
 * питання про сповіщення бачило «пора». Людина торкнулась мапи, а її питають
 * про листи. Тепер інші питання хвилину мовчать після будь-якої відповіді.
 */
object PermissionMoment {
    const val QUIET_MS = 60_000L

    @Volatile
    var answeredAtMs: Long = 0L

    fun answered(nowMs: Long = System.currentTimeMillis()) {
        answeredAtMs = nowMs
    }

    fun quiet(nowMs: Long = System.currentTimeMillis()): Boolean =
        answeredAtMs > 0L && nowMs - answeredAtMs in 0 until QUIET_MS
}
