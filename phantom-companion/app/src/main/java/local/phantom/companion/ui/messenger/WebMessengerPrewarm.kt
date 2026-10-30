package local.phantom.companion.ui.messenger

import android.app.Activity
import android.view.View
import androidx.annotation.MainThread
import androidx.core.view.OneShotPreDrawListener
import java.lang.ref.WeakReference

/**
 * Прогрітий WebView месенджера: створений і вже вантажить бандл, поки граф
 * іще компонує домівку.
 *
 * Виміряно на A52 (debug, 04.10, до правки): WebView народжувався через
 * 5,5 с після старту процесу — аж коли Bootstrap дочекався Room, граф
 * перейшов на домівку і та скомпонувалась. Рендер-процес Chromium весь цей
 * час простоював. Тепер Bootstrap, якщо минулий старт вів на домівку
 * ([local.phantom.companion.navigation.LaunchRoute]), одразу після першого
 * кадру створює WebView тут — і бандл розбирається в рендер-процесі
 * паралельно з компонуванням домівки на головному потоці.
 *
 * Тримає рівно один WebView, привʼязаний до активності, що його створила:
 * [take] віддає його лише тій самій активності (WebView з контекстом старої
 * активності після перестворення був би витоком і зламаними діалогами), а
 * [drop] прибирає неузятий — напр., коли Room сказав «знайомство», а не
 * «домівка».
 */
internal object WebMessengerPrewarm {
    private var owner: WeakReference<Activity>? = null
    private var held: MessengerWeb? = null

    @MainThread
    fun start(activity: Activity, make: () -> MessengerWeb) {
        if (held != null && owner?.get() === activity) return
        drop()
        held = make()
        owner = WeakReference(activity)
    }

    /** Queued from the pre-draw, so [block] runs right after that frame is drawn and never delays it. */
    @MainThread
    fun afterNextDraw(view: View, block: () -> Unit) {
        OneShotPreDrawListener.add(view) { view.post(block) }
        view.invalidate()
    }

    /** Віддати прогрітий WebView — лише активності, що його створила. */
    @MainThread
    fun take(context: android.content.Context): MessengerWeb? {
        val web = held ?: return null
        if (owner?.get() !== context) return null
        held = null
        owner = null
        local.phantom.companion.StartMarks.mark("web:prewarmTaken")
        return web
    }

    /** Активність іде геть — неузятий WebView іде з нею (він тримає її контекст). */
    @MainThread
    fun dropFor(activity: Activity) {
        if (owner?.get() === activity) drop()
    }

    @MainThread
    fun drop() {
        held?.view?.let { v ->
            v.stopLoading()
            v.destroy()
        }
        held = null
        owner = null
    }
}
