package local.phantom.companion.privacy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Сповіщення питають двічі за життя: на старті після знайомства і ще раз,
 * коли людина вперше пише або телефонує — тоді «навіщо» очевидне.
 *
 * Позначка «питали» ставиться ЛИШЕ відповіддю. Питання, що не дожило до
 * відповіді (смерть процесу, складання екрана), лишається ненаставленим.
 */
object NotificationAsk {

    enum class Source(val key: String) {
        LAUNCH("asked_at_launch"),
        ENGAGEMENT("asked_on_engagement"),
    }

    private const val PREFS = "phantom_launch"

    private val asks = MutableSharedFlow<Source>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val requests: SharedFlow<Source> = asks.asSharedFlow()

    /**
     * Щойно людина відповіла на інше системне вікно (місце, камера) — не зараз:
     * картка сповіщень поверх щойно сказаного «ні» про інше — навʼязливо (FR-5).
     */
    fun due(
        context: Context,
        source: Source,
        sdk: Int = Build.VERSION.SDK_INT,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        missing(context, sdk) && !prefs(context).getBoolean(source.key, false) &&
            !local.phantom.companion.core.design.permission.PermissionMoment.quiet(nowMs)

    fun answered(context: Context, source: Source) {
        prefs(context).edit().putBoolean(source.key, true).apply()
    }

    /** Перший лист чи дзвінок: якщо сповіщення досі немає і цього разу ще не питали — питання вилітає. */
    fun onEngagement(context: Context): Boolean =
        due(context, Source.ENGAGEMENT) && asks.tryEmit(Source.ENGAGEMENT)

    private fun missing(context: Context, sdk: Int): Boolean =
        LaunchAsks.permissions(sdk).any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
