package local.phantom.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.launch
import local.phantom.companion.core.context.ContextEngine
import local.phantom.companion.core.context.ContextEngineOwner
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.boot
import local.phantom.companion.service.NavigationService
import local.phantom.companion.service.PhantomLinkService
import local.phantom.companion.service.Watchdog

/**
 * App-level singletons live on a hand-rolled [AppContainer] rather than Hilt.
 * The indirection cost of a code-gen DI graph is not worth its build-time
 * penalty here.
 *
 * The notification channel is registered here so [PhantomLinkService] can
 * `startForeground` the moment ROOT toggles "stay paired" without a race
 * against channel creation.
 */
class PhantomApp : Application(), ContextEngineOwner {
    lateinit var container: AppContainer
        private set

    override val contextEngine: ContextEngine?
        get() = if (::container.isInitialized) container.contextEngine else null

    /**
     * Мова Nightpost до Android 13 — для всього процесу, а не лише для
     * `MainActivity`: сповіщення, служби й рядки з `applicationContext`, а
     * з ними `Locale.getDefault()` (дати, геокодер) беруть вибір першого
     * екрана / Профілю › Мова тут. На 13+ це робить система, і обгортка
     * нічого не міняє.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        StartMarks.mark("app:onCreate")
        super.onCreate()
        instance = this
        local.phantom.companion.feature.stream.ui.map.MapText.bind(this)
        // Відновити «Зупинити все» синхронно до будь-яких корутин і сервісів,
        // щоб перезапуск процесу не відкривав вікно з SymbioteStop.stopped == false.
        runCatching { local.phantom.companion.data.BodyLadder.restoreStopSynchronously(this) }
        // The only thing the main thread still owes the first frame: a graph
        // object whose fields are all lazy. Everything that USES the graph now
        // happens on [bootOffMainThread].
        container = AppContainer(this)
        // HIGHWAY — arm the car head before any Android Auto host can connect.
        // The host constructs PhantomCarAppService itself, so there is no
        // constructor to inject through; this is the seam. Application scope on
        // purpose: the car process runs with no Activity of ours alive.
        local.phantom.companion.feature.car.PhantomCarAppService.ports =
            local.phantom.companion.car.AppCarPorts(container)
        // HIGHWAY / voice — navSpeaker used to arm only as a side effect of
        // the AI's location cache being touched, which is true the moment
        // any phone screen opens first but was never guaranteed for a car-
        // triggered cold boot that lands straight on ACTION_NAVIGATE
        // (CarHomeScreen, the other trigger, is never shown for that intent
        // shape). Arming it here too costs one background coroutine, not the
        // main thread's first frame — see PhantomApp's own boot-off-main-
        // thread discipline below.
        container.scope.launch { runCatching { container.navSpeaker } }
        container.scope.launch {
            runCatching {
                local.phantom.companion.core.data.transit.TransitDelayMemory.shared =
                    local.phantom.companion.core.data.transit.TransitDelayMemory(java.io.File(filesDir, "transit/delays.tsv"))
                        .also { it.warm() }
            }
        }

        warmTypefaces()
        warmAppLock()
        bootOffMainThread()
        warmGeoAssets()
        leaseSensorsWhileVisible()
        StartMarks.mark("app:onCreate:end")
    }

    /**
     * The nine .ttf files, parsed before the first frame needs them.
     *
     * Its own thread rather than the boot queue: text is the very first thing
     * drawn, so this must not queue behind a database open. Whichever wins,
     * the result is a cache hit — losing the race only costs one parse.
     */
    private fun warmTypefaces() {
        Thread({
            runCatching {
                local.phantom.companion.core.design.theme.warmTypefaces(this)
            }
        }, "phantom-typefaces").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /**
     * Замок застосунку, відчинений до першого кадру — але не на головному потоці.
     *
     * MainActivity.onStart читає стан замка (`onForeground`, мініатюра «Недавніх»), і
     * має читати його ДО першого кадру: інакше вміст блимне повз замок. Стан
     * лежить у зашифрованому слоті (Keystore), і його відкриття на головному
     * потоці коштувало на A52 (debug, 04.10) ~0,45 с системної заставки
     * (act:onStart → act:onResume). Тут слот відкривається й читається на
     * своєму потоці одразу зі стартом процесу; якщо onStart прийде раніше, він
     * дочекається того самого `synchronized` в AppLockStore — не довше, ніж
     * було.
     */
    private fun warmAppLock() {
        Thread({
            runCatching { local.phantom.companion.data.applock.AppLockStore.of(this).enabled }
        }, "phantom-applock").apply { priority = Thread.NORM_PRIORITY }.start()
    }

    /**
     * IMU lives while a screen is up. Started, not resumed: a shade pulled
     * down over the map is still a drive. Background nav holds its own lease
     * via `setNavigationGrade`.
     */
    private fun leaseSensorsWhileVisible() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: android.app.Activity) {
                if (started++ == 0) container.sensorAdapter?.acquireImu()
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                if (--started <= 0) {
                    started = 0
                    container.sensorAdapter?.releaseImu()
                }
            }

            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
    }

    /**
     * Cold start used to run the whole application graph — Keystore-backed
     * preference stores, ktor engines, Room/SQLCipher, the AI routes, the
     * sensor + voice stacks, WorkManager, three notification channels and two
     * foreground services — synchronously inside `onCreate`, which is why the
     * launch sequence's own animation clock stalled: it is a Compose animation
     * driven by the main-thread frame clock, and the main thread was busy.
     *
     * One ordered background thread instead: the sequence between these steps
     * still matters (channels exist before any startForeground; the container
     * boots before the runtime that reads it), only the thread changed.
     */
    private fun bootOffMainThread() {
        Thread({
            // «Зупинити все» переживає перезапуск процесу: драбина піднімає SymbioteStop.
            runCatching { local.phantom.companion.data.BodyLadder.of(this) }
            // Один постійний рядок замість чотирьох. Видалення старих каналів
            // забирає і їхні сповіщення — шторка чиститься сама при оновленні.
            runCatching {
                local.phantom.companion.service.StatusShade.ensureChannel(this)
                local.phantom.companion.service.StatusShade.retireOldChannels(this)
            }.onFailure { t -> Log.w("PhantomApp", "status channel failed", t) }
            registerNavigationChannel()
            // СИРЕНА-3 — канали до першого startForeground, інакше вартовий
            // піднімається в порожній канал і тривога тоне беззвучно.
            runCatching {
                local.phantom.companion.service.AirAlertNotifications.registerChannels(this)
            }.onFailure { t -> Log.w("PhantomApp", "air alert channels failed", t) }
            runCatching { container.boot() }
                .onFailure { t -> Log.e("PhantomApp", "container boot failed", t) }
            // Ліцензія читається з Keystore і, якщо настав час, звіряється —
            // не частіше ніж раз на добу і ніколи не блокуючи запуск.
            runCatching { container.license.start() }
                .onFailure { t -> Log.w("PhantomApp", "license start failed", t) }
            // Store: оплачена, але не обміняна на ключ покупка Play доводиться
            // до кінця на старті — без підтвердження Play за три дні повертає
            // гроші сам. У польовій збірці checkout == null.
            runCatching { container.checkout?.start() }
                .onFailure { t -> Log.w("PhantomApp", "checkout start failed", t) }
            // Symbiote foundation — register every Tier 14+ FeatureManifest
            // with the ConstitutionRegistry at boot. Failures land in
            // `constitutionRegistry.rejections`, not in a crash, so a
            // mis-authored manifest never blocks cold launch.
            runCatching { container.bootSymbioteManifests() }
                .onFailure { t -> Log.e("PhantomApp", "Symbiote manifest boot failed", t) }
            // Tool manifests are audited inside ToolRegistry's init, and the
            // registry is lazy — every touch site sits in a lambda that only
            // runs while composing a prompt. Until then the diagnostics card
            // counted the refusals it had and said «жодного відхилення».
            runCatching { announceToolRefusals() }
                .onFailure { t -> Log.e("PhantomApp", "Tool constitution audit failed", t) }
            runCatching { container.symbioteRuntime.start(container.scope) }
                .onFailure { t -> Log.e("PhantomApp", "Symbiote runtime start failed", t) }
            runCatching { local.phantom.companion.service.ScheduledLetterOnline.start(container) }
                .onFailure { t -> Log.w("PhantomApp", "when-online letters failed", t) }
            // A reminder nobody arms is only a row in a table.
            runCatching {
                local.phantom.companion.service.ReminderAlarmSync.start(this, container)
            }.onFailure { t -> Log.e("PhantomApp", "Reminder alarm sync failed to start", t) }
            local.phantom.companion.service.AirAlertService.ensureRunning(this)
            PhantomLinkService.ensureRunning(this)
            runCatching { Watchdog.schedule(this) }
                .onFailure { t -> Log.w("PhantomApp", "watchdog schedule failed", t) }
        }, "phantom-boot").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /**
     * Дотик до `toolRegistry` і Є той аудит — реєстр лінивий. Warn, а не Error:
     * відмова — рішення політики, і в Стрічці на кожному запуску вона стала б
     * шумом, який вчаться не читати.
     */
    private fun announceToolRefusals() {
        val refused = container.toolRegistry.rejectedTools.value
        if (refused.isEmpty()) return
        container.errorBus.emit(
            source = getString(R.string.err_tool_refused_source),
            severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Warn,
            message = getString(
                R.string.err_tool_refused,
                refused.entries.joinToString("; ") { (id, verdict) ->
                    "$id (${verdict.displayName}) — " +
                        verdict.violations.joinToString(", ") {
                            getString(
                                local.phantom.companion.ui.settings.axiomLabel(it.axiom),
                            )
                        }
                },
            ),
        )
    }

    /**
     * Boot-time parse of the bundled geo assets on a background thread: 708 KB
     * of oblast geojson and the СИРЕНА region index, so whoever touches them
     * first gets a cache hit instead of a 100–300 ms parse (brief 03 defect
     * #1). Both loaders memoize behind double-checked caches, so a thread that
     * arrives mid-parse waits for THIS one instead of running its own.
     *
     * This is now a head start, not the defence. The shell no longer parses
     * either asset during composition at all — it hands both down as lambdas
     * resolved on IO (see `MapViewModel.oblastShapesLoad`), because a boot
     * thread can only race the first frame, never reliably beat it. What is
     * left to win is `MapScreen`'s own load at map composition.
     *
     * That is also why this thread is no longer MIN_PRIORITY: holding the
     * loaders' lock at the bottom of the scheduler while the UI thread blocks
     * on it is priority inversion — precisely the stall the warm-up exists to
     * prevent. One notch below normal: still yields to the UI, still gets run.
     */
    private fun warmGeoAssets() {
        Thread({
            runCatching {
                // Спершу країна, потім її дані: усе нижче читається з
                // активного пака, і без цього рядка грілося б із порожнечі.
                container.installCountryPacks()
                local.phantom.companion.feature.stream.ui.map.OblastShapes.load(this)
                local.phantom.companion.feature.stream.ui.map.RegionIndex.load(this)
                // The 4 MB bundled POI pack — parsed here so the first map
                // touch of poiRepository is a cache hit, not a 300–700 ms
                // stall (brief 03 defect #2).
                container.warmOfflinePoiPack()
                // Drawable is not findable: until this ran, offline search knew
                // only the viewports the operator had panned while online.
                container.seedPoiGazetteerIfNeeded()
                // Streets never had a live source to begin with — this is the
                // ONLY way «хрещатик» is ever findable with no network.
                container.seedStreetGazetteerIfNeeded()
                // І та сама причина для ВОДИ: пак пінів накриває Київ, а
                // питання «де вода» ставлять не лише в Києві. 13 461 точка
                // води по країні лежала в тому самому витягу OSM і не їхала.
                container.seedPlaceGazetteerIfNeeded()
                // І НАЗВА СЕЛА: `places.tsv` попри назву не несе жодного
                // поселення, а міста знав лише PlaceTable.kt — 314 рядків на
                // всю країну. Тут їх стає 68 010.
                container.seedSettlementGazetteerIfNeeded()
            }.onFailure { t -> Log.w("PhantomApp", "geo asset warm-up failed", t) }
        }, "geo-warmup").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /**
     * METAMORPHOSIS P1 — the counterweight to the resident local brain.
     * The warmed LlmInference engine pins hundreds of MB of native memory;
     * that is the deal the operator chose (instant first token) — but when
     * the OS signals real pressure while we're backgrounded, holding the
     * weights just converts «fast first message» into «process killed,
     * everything cold». Release when idle; the next turn (or the next app
     * start's warmer) reloads.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_UI_HIDDEN fires the moment the app leaves the foreground —
        // the natural session-end boundary. Fold the just-ended conversation into
        // Tier-2 long-term memory here (fail-safe + de-duplicated inside the call).
        if (level >= TRIM_MEMORY_UI_HIDDEN && ::container.isInitialized) {
            container.scope.launch { runCatching { container.consolidateMemory() } }
        }
        if (level >= TRIM_MEMORY_BACKGROUND && ::container.isInitialized) {
            container.scope.launch {
                runCatching {
                    (container.ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts)?.releasePremium()
                }
            }
        }
    }

    /**
     * NAV-FGS — turn-by-turn channel. IMPORTANCE_LOW so the ongoing drive
     * banner sits silently in the shade; the maneuver text updates in place
     * (setOnlyAlertOnce) rather than re-alerting every turn.
     */
    private fun registerNavigationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NavigationService.CHANNEL_ID,
            getString(R.string.nav_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.nav_service_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        private var instance: PhantomApp? = null

        fun container(): AppContainer = instance!!.container
    }
}
