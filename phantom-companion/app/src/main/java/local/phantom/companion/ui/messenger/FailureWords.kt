package local.phantom.companion.ui.messenger

import local.phantom.companion.R
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.route.NightpostBrainRoute

/**
 * Чому відповіді немає — словами, які людина може прочитати.
 *
 * Доти сюди йшло `t.message`, а це рядок винятку: «network unavailable for
 * cloud route», «cloud rejected: 429 {…}». Англійською, на склі, з нутрощами
 * бібліотеки всередині. Причини з [AIError] названі типом, тож речення
 * беруться з типу, а не з рядка; [AIError.Broken] — наш власний збій, і його
 * рядок винятку людині не показується.
 *
 * Остання гілка — для того, чого перелік не знає: вона теж каже мовою
 * розмови, і технічний хвіст лишає позаду речення, а не замість нього.
 */
internal object FailureWords {

    fun of(cause: Throwable?, str: (Int, Array<out Any>) -> String): String {
        fun say(id: Int, vararg args: Any) = str(id, args)
        return when (cause) {
            is AIError.NoNetwork -> say(R.string.ai_fail_no_network)
            is AIError.CloudRejected -> when (cause.httpCode) {
                401, 403 -> say(R.string.ai_fail_key)
                429 -> if (NightpostBrainRoute.isSpent(cause.body)) say(R.string.ai_fail_daily_limit) else say(R.string.ai_fail_too_often)
                in 500..599 -> say(R.string.ai_fail_cloud_down)
                else -> say(R.string.ai_fail_cloud, cause.httpCode)
            }
            is AIError.Cancelled -> say(R.string.ai_fail_cancelled)
            is AIError.NoBrain -> say(R.string.ai_fail_no_brain)
            is AIError.PcUnavailable -> say(R.string.ai_fail_pc, cause.httpCode)
            null -> say(R.string.ai_fail_silent)
            is AIError.Broken -> say(R.string.ai_fail_unknown)
            else -> {
                val tail = cause.message?.trim().orEmpty()
                if (tail.isEmpty()) say(R.string.ai_fail_unknown) else say(R.string.ai_fail_unknown_with_tail, tail.take(120))
            }
        }
    }
}
