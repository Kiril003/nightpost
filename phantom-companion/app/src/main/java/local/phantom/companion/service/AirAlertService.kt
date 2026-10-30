package local.phantom.companion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.Distribution
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.voice.route.RoutedTts

/**
 * СИРЕНА-3 — вартовий неба, що переживає згортання застосунку.
 *
 * Чому служба, а не WorkManager: періодична робота має мінімум 15 хвилин, а
 * шахед долітає швидше. Foreground-служба — єдиний спосіб тримати мережу
 * живою в Doze з темпом у хвилини. Тип `specialUse`, а не `dataSync`, бо
 * Android 15 ріже dataSync до 6 годин на добу — вартовий, що замовкає на ніч,
 * не вартовий. Дозвіл уже оголошений (AutopilotService).
 *
 * Служба нічого не опитує сама: [local.phantom.companion.data.AppContainer.airAlertWatch]
 * — єдиний власник стану. Тут лише те, що вміє тільки служба: тримати процес
 * живим і перетворювати події на сповіщення.
 */
class AirAlertService : Service() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        local.phantom.companion.data.BootProbe.mark("service:AirAlertService")
        // Підйом — ПЕРШИМ: дедлайн 5 с система рахує від startForegroundService,
        // а перший дотик до контейнера будує весь лінивий граф і його не встигає.
        // Вартовий тривоги — найдорожчий із усіх: якщо процес гине на старті,
        // людина не чує сирени.
        AirAlertNotifications.registerChannels(this)
        // Рядок вартового — заголовок спільного рядка; тривога лишається
        // окремою подією на власному каналі.
        StatusShade.attach(
            this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )

        val container = runCatching { (application as PhantomApp).container }.getOrNull()
        if (container == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val watch = container.airAlertWatch
        // ПІДПИСКА ПЕРШОЮ, СПОСТЕРІГАЧ ДРУГИМ. Порядок тут — обмеження, не смак.
        //
        // `notices` — це SharedFlow без повтору: те, що випромінили, поки нікого
        // немає, зникає мовчки, і `tryEmit` про це не скаржиться, бо повернене
        // значення ніхто не читає. Сьогодні (31.08) зворотний порядок був
        // безпечний: єдиний шлях до `tryEmit` у `AirAlertWatch` проходить через
        // мережевий обмін (`fetchBase`/`fetchSiren`/`fetchDetail`), а єдиний
        // швидкий офлайн-вихід `republish()` не випромінює нічого — тобто
        // підписник устигав приєднатись на наступному ж диспетчі.
        //
        // Але це правильність, позичена в ПОВІЛЬНОСТІ сусіда. Перша ж розумна
        // оптимізація — «показати останні відомі тривоги миттєво з кешу» —
        // зробить випромінювання синхронним, і ПЕРШЕ сповіщення про повітряну
        // тривогу почне зникати. Без симптому: журнал збережеться, а сповіщення
        // просто не буде.
        //
        // `start()` ідемпотентний (@Synchronized, перевіряє `loop?.isActive`),
        // тож переставити його нижче безкоштовно. Не міняти назад.
        if (!collecting) {
            collecting = true
            val voice = AirAlertVoice(
                prewarm = { text -> (container.ttsEngine as? RoutedTts)?.prewarm(text) },
                submit = { cue -> container.navSpeaker.submit(cue) },
            )
            scope.launch {
                watch.notices.collect {
                    AirAlertNotifications.notify(this@AirAlertService, it)
                    // Окремо: прогрів голосу триває секунди, шторка чекати не мусить.
                    launch { voice.announce(it) }
                    // «Рюкзак перед підвалом» (розд. 07 §7): тривога почалась саме в моїй
                    // області (сповіщення лише такі) — ще є секунди доброго звʼязку.
                    if (it.kind == local.phantom.companion.feature.stream.ui.map.AirAlertNoticeKind.RAISED) {
                        launch {
                            runCatching {
                                container.backpack.pack(local.phantom.companion.core.net.backpack.Backpack.Trigger.ALERT)
                            }
                        }
                    }
                }
            }
        }

        watch.start()

        // STICKY — a killed watchman must come back on his own; the watch it
        // observes is process-scoped and survives the restart untouched.
        return START_STICKY
    }

    override fun onDestroy() {
        StatusShade.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirAlertService"

        /** Best-effort: a background start on API 31+ may be refused outright. */
        fun ensureRunning(context: Context) {
            if (!Distribution.airAlertWatch || !AirAlertGate.on(context)) {
                runCatching { context.stopService(Intent(context, AirAlertService::class.java)) }
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AirAlertService::class.java),
                )
            }.onFailure { Log.w(TAG, "sky watch service could not be started", it) }
        }
    }
}
