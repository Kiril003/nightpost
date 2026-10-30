package local.phantom.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import local.phantom.companion.data.backup.autoRestore
import local.phantom.companion.data.backup.refreshAutoSnapshot
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import local.phantom.companion.core.net.peer.PeerCallLine
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.privacy.NotificationAsk
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
import local.phantom.companion.data.syncLiveDialogService
import local.phantom.companion.data.warmLiveVoiceAfterFirstFrame
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.core.design.theme.SunLook
import local.phantom.companion.core.design.theme.resolveThemeAt
import local.phantom.companion.ui.theme.LivingSun
import local.phantom.companion.ui.theme.SunPlaces
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.design.theme.LocalContextFrame
import local.phantom.companion.navigation.PhantomNavGraph
import local.phantom.companion.ui.PhantomAppShell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import local.phantom.companion.core.design.sound.LocalSoundEngine
import local.phantom.companion.feature.stream.ui.map.LocalMapStyleConfig
import local.phantom.companion.feature.stream.ui.map.MapStyleConfig
import local.phantom.companion.vm.PhantomViewModel

// Phase 1.5-B — extends FragmentActivity (still a ComponentActivity, just
// fragment-aware) so the Vault surface can host a BiometricPrompt. The Fragment
// machinery is otherwise unused — we keep `setContent` + Compose nav verbatim.
class MainActivity : FragmentActivity() {

    companion object {
        /**
         * Знайомство ДІЙШЛО ДО КІНЦЯ — не «профіль існує».
         *
         * Профіль створюється на кроці 2 з 4 (`handlePinSubmitted`), тож
         * попередня умова `profileStore.count() > 0` ставала істинною ПОСЕРЕД
         * знайомства: системні діалоги місця, мікрофона й сповіщень падали
         * поверх картки 04/04, яка обіцяє рівно один доступ — читання екрана.
         * Людина не могла зіставити жоден дозвіл із жодною обіцянкою.
         */
        private const val ONBOARDING_DONE = "onboarding_done"

        private const val ASKED_BLUETOOTH = "asked_bluetooth_in_call"
        private const val ASKED_BATTERY = "asked_battery_after_contact"
        private const val PEOPLE_SEEN = "people_seen"

        fun markOnboardingDone(context: Context) {
            context.getSharedPreferences("phantom_launch", MODE_PRIVATE)
                .edit().putBoolean(ONBOARDING_DONE, true).commit()
        }
    }

    private val unlocks = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val callOverLock by lazy { CallOverLock(this) { unlocks.value++ } }
    private val overLockBack by lazy { CallOverBack(callOverLock::leave) { onBackPressedDispatcher.onBackPressed() } }
    private val overLockInvoked: Any? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { overLockBack.handleOnBackPressed() } else null
    }

    private fun holdOverLockBack(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val cb = overLockInvoked as? android.window.OnBackInvokedCallback ?: return
        runCatching {
            if (on) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        }
    }
    private var overLockWatch: kotlinx.coroutines.Job? = null
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { unlocks.value++ }
    }

    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * Гаманець у вкладці (збірка 17). Поле, а не lazy: двері реєструють результат
     * вікна гаманця MWA, а це можна лише до STARTED.
     */
    val walletEmbed = local.phantom.companion.core.solana.WalletEmbed(this)

    private val notificationAsk = androidx.compose.runtime.mutableStateOf(false)

    // GPS, заведений на старті без дозволу, сам не оживе — його будить onResume.
    private var locationHeld = false

    /**
     * Мова Nightpost до Android 13: окремої мови застосунку в системі там
     * немає, тож активність бере вибір першого екрана / Профілю › Мова сама.
     * На 13+ це робить система (LocaleManager), і обгортка нічого не міняє.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(local.phantom.companion.core.data.locale.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartMarks.mark("act:onCreate")
        super.onCreate(savedInstanceState)
        // Перший кадр — полотно phantom_canvas (системна заставка, вікно під
        // Compose, завіса). Типовий enableEdgeToEdge() бере тему СИСТЕМИ: на
        // світлому A52 (04.10) він підкладав під три кнопки навігації світлий
        // серпанок, і в мить передачі від заставки до застосунку внизу темного
        // кадру спалахувала світла смуга. Стиль смуг — за самим полотном;
        // далі їх веде розвʼязана палітра (LaunchedEffect(barsAreDark) нижче).
        val firstFrameDark = androidx.core.graphics.ColorUtils
            .calculateLuminance(getColor(R.color.phantom_canvas)) < 0.5
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (firstFrameDark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
            navigationBarStyle = if (firstFrameDark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
        )
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        if (local.phantom.companion.data.backup.autoRestorePending(this)) {
            restoreAfterReinstall()
            return
        }
        if (BuildConfig.DEBUG) {
            androidx.core.view.OneShotPreDrawListener.add(window.decorView) {
                StartMarks.mark("act:firstDraw")
            }
        }
        androidx.core.view.OneShotPreDrawListener.add(window.decorView) {
            runCatching { (applicationContext as PhantomApp).container.warmLiveVoiceAfterFirstFrame() }
        }
        locationHeld = locationHeldNow()
        askOncePastOnboarding()
        ContextCompat.registerReceiver(this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        askAtTheRightMoment()
        routeIntent(intent)

        StartMarks.mark("act:setContent")
        setContent {
            StartMarks.mark("act:compose")
            val vm: PhantomViewModel = viewModel(factory = PhantomViewModel.Factory)
            StartMarks.mark("act:vm")
            val state by vm.state.collectAsStateWithLifecycle()
            // QH2 — resolve at the View boundary so [ThemeMode.System]
            // mirrors the OS-level dark-mode toggle live (no app
            // restart). `state.themeMode` carries the operator's
            // preference; `isSystemInDarkTheme()` provides the runtime
            // hint for the System branch.
            val systemDark = isSystemInDarkTheme()

            val container = (applicationContext as PhantomApp).container
            val contextFrame by (container.contextEngine?.latestFrame ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
            StartMarks.mark("act:context")

            // The SUN drives Dawn/Day/Dusk/Night for ThemeMode.Living, the
            // DEFAULT since 2026-08-24 — its height where the operator stands,
            // not the wall clock (24.09: a Kyiv December 16:30 is already dark,
            // a June 21:00 still light). Re-read every minute; see [rememberDaylight].
            //
            // The ContextFrame's timestamp is deliberately NOT the source any
            // more: it records when that frame was BUILT, and latestFrame is a
            // StateFlow that keeps the last one, so an engine that emitted once
            // and stalled used to pin the hour at its own — permanently, past
            // every boundary and past every recomposition.
            val daylight = rememberDaylight()

            val resolvedSpec = remember(state.themeMode, systemDark, daylight) {
                resolveThemeAt(state.themeMode, systemDark, daylight.look, daylight.hour)
            }
            // Профіль › Вигляд: акцент своєї особи — лише через токени теми.
            val look by container.appearance.state.collectAsStateWithLifecycle()
            // Гаманець — окреме вікно: він відкривається в тій самій темі й тому ж розмірі тексту.
            androidx.compose.runtime.LaunchedEffect(resolvedSpec, state.fontScale, look.accent) {
                local.phantom.companion.core.solana.SolanaLayer.useSkin(
                    local.phantom.companion.core.solana.WalletSkin(resolvedSpec, state.fontScale, look.accent),
                )
            }

            // EDGE-TO-EDGE — the bars belong to the canvas, not to the OS.
            // The default enableEdgeToEdge() reads the SYSTEM dark-mode flag,
            // but PHANTOM's Living theme resolves its own darkness (a night
            // palette under a light OS and vice versa), so the bar icons must
            // follow the RESOLVED surface. On 29+ the OS also paints its own
            // contrast scrim behind 3-button nav unless told not to — that
            // scrim is the «bottom frame» this kills.
            // Заставка наступного холодного старту — у цій самій полярності
            // (SplashNight): системну заставку малює система з values-night
            // за нічним режимом застосунку, ще до нашого коду.
            androidx.compose.runtime.LaunchedEffect(state.themeMode, resolvedSpec.isDark) {
                local.phantom.companion.ui.theme.SplashNight.sync(applicationContext, state.themeMode, resolvedSpec.isDark)
            }
            // Вибір теми з веб-месенджера (PhantomBridge.setThemeMode) — та сама
            // дорога, що й з рідного Профілю: одне сховище, одна палітра.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                container.themeModeRequest.collect { mode ->
                    if (mode == null) return@collect
                    container.themeModeRequest.value = null
                    vm.setThemeMode(mode)
                }
            }

            val barsAreDark = resolvedSpec.surfaceBase.luminance() < 0.5f
            androidx.compose.runtime.LaunchedEffect(barsAreDark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (barsAreDark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (barsAreDark) SystemBarStyle.dark(transparent)
                    else SystemBarStyle.light(transparent, transparent),
                )
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
            }

            val hasPeople = contextFrame?.who?.hasPeopleNearby ?: false
            androidx.compose.runtime.LaunchedEffect(hasPeople) {
                if (hasPeople) {
                    container.vaultStore.lockNow()
                }
            }

            // W1.5-4 — provide the central SoundEngine so every
            // composable that fires UX cues (timer finish, insight
            // appear, error notice, etc.) routes through the canonical
            // sound catalog. GHOST gating + Android tone backing live
            // inside AppContainer.soundEngine.
            val mapProviderId by container.mapPrefs.providerId.collectAsStateWithLifecycle()
            val mapApiKey by container.mapPrefs.apiKey.collectAsStateWithLifecycle()
            val mapCustomUrl by container.mapPrefs.customUrl.collectAsStateWithLifecycle()
            val mapStyleConfig = remember(mapProviderId, mapApiKey, mapCustomUrl) {
                MapStyleConfig(
                    providerId = mapProviderId,
                    apiKey = mapApiKey,
                    customUrl = mapCustomUrl,
                )
            }
            StartMarks.mark("act:theme")
            PhantomTheme(spec = resolvedSpec, fontScale = state.fontScale, accent = look.accent) {
                CompositionLocalProvider(
                    LocalSoundEngine provides container.soundEngine,
                    LocalMapStyleConfig provides mapStyleConfig,
                    LocalContextFrame provides contextFrame,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        remember { local.phantom.companion.data.erase.PrefsEraseJournal(applicationContext) }
                        val eraseState by local.phantom.companion.data.erase.PrefsEraseJournal.state.collectAsStateWithLifecycle()
                        if (eraseState.step == local.phantom.companion.data.erase.EraseStep.PENDING) {
                            local.phantom.companion.ui.settings.PendingDeletionScreen(
                                dueAtMs = eraseState.dueAtMs,
                                onRestored = { recreate() },
                            )
                        } else {
                            // ПЕРШИЙ ФІКС — the launch sequence draws OVER the
                            // graph, never in front of its construction: the app
                            // is composing and warming up underneath the whole
                            // time, so the splash costs no startup latency.
                            local.phantom.companion.core.design.component.PhantomSplashHost {
                                // Одне полотно токенів під усім графом.
                                PhantomAppShell {
                                    PhantomNavGraph()
                                }
                                // Понад графом, а не в Стрічці: інструмент кличуть
                                // і з веб-месенджера, і з карток — питання мусить
                                // наздогнати оператора на будь-якому екрані.
                                local.phantom.companion.ui.tools.ToolConsentHost(
                                    prompter = container.toolConsentPrompter,
                                )
                                LaunchNotificationAsk(notificationAsk)
                            }
                            // Замок застосунку — над усім, і над заставкою теж.
                            local.phantom.companion.ui.applock.AppLockGate(appLock)
                            val lockSettings by appLock.settings.collectAsStateWithLifecycle()
                            androidx.compose.runtime.LaunchedEffect(lockSettings.enabled) { syncSecureFlag() }
                        }
                    }
                }
            }
        }
    }

    private val appLock by lazy { local.phantom.companion.data.applock.AppLockStore.of(applicationContext) }

    // Замок рахує час від виходу: onStop — пішли, onStart — повернулись.
    // Під замком «Недавні» не показують мініатюру; знімки екрана дозволені (власник 20.10).
    override fun onStart() {
        StartMarks.mark("act:onStart")
        super.onStart()
        appLock.onForeground()
        StartMarks.mark("act:onStart:lock")
        local.phantom.companion.service.PeerMeshGate.foregrounded()
        StartMarks.mark("act:onStart:mesh")
        // Служба читає спарений ПК з EncryptedSharedPreferences (ключ з Keystore) — не до першого кадру.
        if (local.phantom.companion.service.LinkStart.fromForeground(local.phantom.companion.service.PhantomLinkService.alive)) {
            val app = applicationContext
            lifecycleScope.launch(Dispatchers.IO) { runCatching { local.phantom.companion.service.PhantomLinkService.ensureRunning(app) } }
        }
        StartMarks.mark("act:onStart:link")
        syncSecureFlag()
        StartMarks.mark("act:onStart:end")
    }

    override fun onStop() {
        appLock.onBackground()
        super.onStop()
        val container = (applicationContext as PhantomApp).container
        container.scope.launch(Dispatchers.IO) {
            runCatching { container.refreshAutoSnapshot() }
        }
    }

    // Живі сховища ще порожні: відновлене лягає під них лише через перезапуск процесу.
    private fun restoreAfterReinstall() {
        setContentView(android.view.View(this).apply { setBackgroundColor(getColor(R.color.phantom_canvas)) })
        val app = applicationContext
        val container = (app as PhantomApp).container
        container.scope.launch(Dispatchers.IO) {
            val result = runCatching { container.autoRestore() }.getOrNull()
            if (result is local.phantom.companion.data.backup.RestoreResult.Success) markOnboardingDone(app)
            local.phantom.companion.ui.backup.AppRestart.relaunch(app)
        }
    }

    // Прогрітий WebView месенджера тримає контекст цієї активності; якщо
    // домівка його так і не взяла (Room повів на знайомство), він іде з нею.
    override fun onDestroy() {
        local.phantom.companion.ui.messenger.WebMessengerPrewarm.dropFor(this)
        runCatching { unregisterReceiver(userPresent) }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && callOverLock.guarding) {
            if (event.action == android.view.KeyEvent.ACTION_UP) callOverLock.leave()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun syncSecureFlag() {
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!appLock.enabled)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    // Службу переднього плану з мікрофоном ОС не дає підняти з фону, а процес
    // стартує й без оператора. Вихід на передній план — єдина мить, коли той
    // старт дозволений; див. syncLiveDialogService.
    override fun onResume() {
        StartMarks.mark("act:onResume")
        super.onResume()
        runCatching { (applicationContext as PhantomApp).container.syncLiveDialogService() }
        val heldNow = locationHeldNow()
        if (heldNow && !locationHeld) {
            runCatching { (applicationContext as PhantomApp).container.sensorAdapter?.rearmLocation() }
        }
        locationHeld = heldNow
        askOncePastOnboarding()
        StartMarks.mark("act:onResume:end")
    }

    /**
     * СИРЕНА-3 — тап по сповіщенню про тривогу садить камеру на мапу.
     * Запит липкий (StateFlow), бо Continuum може скомпонуватися пізніше за
     * цей виклик — на холодному старті з тапу він компонується завжди пізніше.
     */
    private fun routeIntent(intent: Intent?) {
        if (debugWhereStand(intent)) return
        if (debugRouteIntent(intent)) return
        if (openCallIntent(intent)) return
        if ((applicationContext as? PhantomApp)?.let { local.phantom.companion.self.TalkEntry.route(this, intent, it.container.events) } == true) return
        if (openMapPointIntent(intent)) return
        if (openWebSignInIntent(intent)) return
        if (openGroupLinkIntent(intent)) return
        if (acceptInviteIntent(intent)) return
        if (local.phantom.companion.share.ShareInbox.accept(this, intent)) return
        if (openPeerGroupIntent(intent)) return
        if (openPeerIntent(intent)) return
        if (openVoiceSettingsIntent(intent)) return
        if (intent?.getBooleanExtra(
                local.phantom.companion.service.AirAlertNotifications.EXTRA_OPEN_MAP,
                false,
            ) != true
        ) {
            return
        }
        runCatching {
            (applicationContext as PhantomApp).container.homeTabRequest.value =
                local.phantom.companion.ui.home.HomeTab.MAP
        }
    }

    /**
     * `phantom://call` — тап по плашці дзвінка й повноекранний намір.
     *
     * Веде в МЕСЕНДЖЕР, бо саме там живе рушій, який дзвонить: його власний
     * екран виклику вже намальований і чекає на тіло сигналу, яке телефон
     * приніс сіткою. Окремого рідного екрана дзвінка немає навмисно — дві
     * машини станів на один дзвінок розійшлися б на першому ж перезбиранні
     * бандла.
     *
     * Своя гілка, а не спільна з `phantom://peer`: без неї адреса дісталась би
     * наступному розбирачеві, і `phantom://call` став би спробою відчинити
     * розмову з людиною на імʼя «call».
     */
    private fun openCallIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (!uri.isPhantomLink()) return false
        if (uri.host != local.phantom.companion.service.PeerCallNotifications.HOST_CALL) {
            return false
        }
        runCatching {
            val container = (applicationContext as PhantomApp).container
            val line = container.callHub.line
            val who = PeerCallLine.peerOf(line.value)
            if (callOverLock.open(line.value) && overLockWatch?.isActive != true) {
                overLockWatch = lifecycleScope.launch {
                    overLockBack.remove()
                    onBackPressedDispatcher.addCallback(this@MainActivity, overLockBack)
                    overLockBack.isEnabled = true
                    holdOverLockBack(true)
                    val hold = launch { holdCallSpot(container, who) }
                    line.takeWhile { callOverLock.follow(it) }.collect()
                    hold.cancel()
                    overLockBack.isEnabled = false
                    holdOverLockBack(false)
                }
            }
            // Той самий запит, що й у сповіщення про лист: граф відчиняє
            // месенджер. Порожній ключ не годиться — запит липкий і на null
            // не спрацьовує.
            container.peerChatRequest.value = who ?: local.phantom.companion.navigation.CALL_ANY
        }
        return true
    }

    // Вкладку й розмову веб міняє сам, повз «назад»: тут їх лише помічаємо й повертаємо.
    private suspend fun holdCallSpot(container: local.phantom.companion.data.AppContainer, caller: String?) {
        var before: CallSpot? = null
        kotlinx.coroutines.flow.combine(
            container.visibleTab,
            container.events.visiblePeerChat,
            container.events.visiblePeerGroup,
        ) { tab, chat, group -> CallSpot(tab == local.phantom.companion.ui.home.HomeTab.CHATS, chat ?: group) }
            .collect { now ->
                val was = before
                before = now
                if (was != null && CallSpot.leaves(was, now, caller) && callOverLock.leave()) {
                    container.peerChatRequest.value = caller ?: local.phantom.companion.navigation.CALL_ANY
                }
            }
    }

    private fun openGroupLinkIntent(intent: Intent?): Boolean {
        val link = groupLinkPayload(intent) ?: return false
        runCatching {
            val container = (applicationContext as PhantomApp).container
            local.phantom.companion.ui.messenger.WebNav.openGroupLink(link) { container.homeTabRequest.value = it }
        }
        return true
    }

    private fun openWebSignInIntent(intent: Intent?): Boolean {
        val payload = webSignInPayload(intent) ?: return false
        runCatching {
            val events = (applicationContext as PhantomApp).container.events
            events.webSignInPayload.value = payload
            events.routeRequest.value = local.phantom.companion.ui.screens.WebSignIn.ROUTE
        }
        return true
    }

    /** Шторка «говорив системний голос» веде туди, де це лікується. */
    private fun openVoiceSettingsIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(
                local.phantom.companion.service.VoiceFallbackNotice.EXTRA_OPEN_VOICE,
                false,
            ) != true
        ) {
            return false
        }
        runCatching {
            (applicationContext as PhantomApp).container.events.routeRequest.value =
                local.phantom.companion.navigation.Routes.SettingsVoice.path
        }
        return true
    }


    /**
     * Місце, яким поділились: наше `phantom://map`, чуже `geo:` або посилання
     * osm.org. Розбір живе в `:core-geo`, де його доводять без телефона.
     */
    private fun openMapPointIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val point = local.phantom.companion.core.geo.link.MapLink
            .parse(intent.data?.toString()) ?: return false
        runCatching {
            val container = (applicationContext as PhantomApp).container
            container.events.mapPointRequest.value = point
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.MAP
        }
        return true
    }

    /** `phantom://wherestand/[mine/]<district|quarter|exact|clear>[?peer=<id>|name=<імʼя>&min=<хв>]`. Debug only: стенд, названий стендом. */
    private fun debugWhereStand(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (!uri.isPhantomLink() || uri.host != "wherestand") return false
        val parts = uri.pathSegments.orEmpty()
        val mine = parts.firstOrNull() == "mine"
        val precision = local.phantom.companion.core.net.peer.PeerWhere.Precision.entries
            .firstOrNull { it.name.equals(parts.getOrNull(if (mine) 1 else 0), ignoreCase = true) }
        runCatching {
            val container = (applicationContext as PhantomApp).container
            val id = uri.getQueryParameter("peer")
            val name = uri.getQueryParameter("name")
            val peer = container.peers.peers.value.firstOrNull { (id != null && it.id == id) || (name != null && it.name == name) }
            val hub = container.peopleWhere
            when {
                peer == null -> hub.debugStand("Stand · test", precision)
                mine -> hub.debugMine(
                    peer.id,
                    precision,
                    uri.getQueryParameter("min")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 60_000L } ?: 0L,
                )
                else -> hub.debugStand("${peer.name} · stand", precision, fromId = peer.id)
            }
        }
        return true
    }

    /** `phantom://screen/<route>`. Debug only — the guard is the first line. */
    private fun debugRouteIntent(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (!uri.isPhantomLink() || uri.host != "screen") return false
        val route = uri.path?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return false
        runCatching {
            (applicationContext as PhantomApp).container.events.debugRouteRequest.value = route
        }
        return true
    }

    private fun openPeerGroupIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (!uri.isPhantomLink()) return false
        if (uri.host != local.phantom.companion.service.PeerNotifications.HOST_GROUP) return false
        val groupId = uri.path?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return false
        local.phantom.companion.service.NoticeLabels.requestGroup(
            post = { (applicationContext as PhantomApp).container.peerGroupRequest.value = it },
            onFail = { Toast.makeText(this, R.string.peer_group_not_in_messenger, Toast.LENGTH_LONG).show() },
            groupId = groupId,
        )
        return true
    }

    private fun openPeerIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (!uri.isPhantomLink()) return false
        if (uri.host != local.phantom.companion.service.PeerNotifications.HOST_PEER) return false
        val peerId = uri.path?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return false
        runCatching {
            if (peerId == local.phantom.companion.ui.messenger.AgencySessionManager.PHANTOM_AGENT_ID) {
                local.phantom.companion.self.PhantomVisits.tapped(this)
            }
            (applicationContext as PhantomApp).container.peerChatRequest.value = peerId
        }
        return true
    }

    private fun acceptInviteIntent(intent: Intent?): Boolean {
        val tappedInviteLink = isTappedInvite(intent)
        val raw = invitePayload(intent) ?: return false
        // Друг ділиться ЦІЛИМ повідомленням з телеграма, не вирізаним кодом —
        // код шукається в тексті, чистоти від курʼєра ніхто не обіцяв.
        val code = local.phantom.companion.core.net.peer.PeerInvite.findInText(raw) ?: raw
        val invite = when (val decoded = local.phantom.companion.core.net.peer.PeerInvite.decode(code)) {
            is local.phantom.companion.core.net.peer.PeerInvite.Decoded.Ph2 -> decoded.invite
            // Код нашого ж ПК: він цілий, просто іншого роду. Казати «неповний»
            // означало б послати людину просити переслати те саме ще раз.
            // Відмова називає мову І дорогу: що це за код і що з ним робити.
            local.phantom.companion.core.net.peer.PeerInvite.Decoded.PcEnvelope -> {
                Toast.makeText(this, R.string.invite_link_pc, Toast.LENGTH_LONG).show()
                return true
            }
            // Запрошення зі старого PHANTOM лікується оновленням, а не повтором.
            local.phantom.companion.core.net.peer.PeerInvite.Decoded.LegacyPh1 -> {
                Toast.makeText(this, R.string.invite_link_legacy, Toast.LENGTH_LONG).show()
                return true
            }
            // Тільки для того, що НАМАГАЛОСЬ бути запрошенням: чужий текст у
            // SEND не наша справа, а тапнуте phantom://invite і обірваний
            // месенджером код — наша, і мовчати про них — брехня бездіянням.
            local.phantom.companion.core.net.peer.PeerInvite.Decoded.NotInvite -> {
                if (!tappedInviteLink &&
                    !raw.contains(local.phantom.companion.core.net.peer.PeerInvite.PREFIX)
                ) {
                    return false
                }
                Toast.makeText(this, R.string.invite_link_bad, Toast.LENGTH_LONG).show()
                return true
            }
        }
        val container = runCatching { (applicationContext as PhantomApp).container }.getOrNull()
            ?: return false
        container.scope.launch {
            // Код передав інший застосунок: ключ не звірено, але розмова відкривається одразу.
            local.phantom.companion.ui.scan.PersonScanOutcome.land(
                source = local.phantom.companion.core.net.peer.PeerIdentity.KeySource.SHARED,
                accept = { src -> container.mesh.accept(invite, src) },
                markVerified = {},
                openChat = { id -> container.peerChatRequest.value = id },
                openCard = { id -> container.peerCardRequest.value = id },
            )
        }
        return true
    }

    /**
     * A fresh install used to meet the operator with a battery-optimisation
     * system screen and five permission dialogs stacked on top of a UI that had
     * not drawn a single pixel. Onboarding already asks for each of these one at
     * a time, with a sentence saying why — this now waits until that is done and
     * only tops up whatever is still missing.
     */
    private fun askOncePastOnboarding() {
        lifecycleScope.launch {
            val container = runCatching {
                (applicationContext as PhantomApp).container
            }.getOrNull() ?: return@launch
            // Спершу дешеві позначки: лічба профілів відкриває зашифровану БД,
            // і на головному потоці це сотні мс до першого кадру.
            val prefs = getSharedPreferences("phantom_launch", MODE_PRIVATE)
            if (!prefs.getBoolean(ONBOARDING_DONE, false)) return@launch
            // onResume теж кличе це; позначку ставить відповідь людини, не саме питання.
            if (!NotificationAsk.due(this@MainActivity, NotificationAsk.Source.LAUNCH)) return@launch
            val hasProfile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { container.profileStore.count() > 0 }.getOrDefault(false)
            }
            if (!hasProfile) return@launch
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        if (NotificationAsk.due(this, NotificationAsk.Source.LAUNCH)) notificationAsk.value = true
    }

    private fun locationHeldNow(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun askAtTheRightMoment() {
        lifecycleScope.launch {
            val container = runCatching { (applicationContext as PhantomApp).container }.getOrNull() ?: return@launch
            val (roster, line) = withContext(Dispatchers.IO) {
                runCatching { container.peers.peers to container.callHub.line }.getOrNull()
            } ?: return@launch
            val prefs = getSharedPreferences("phantom_launch", MODE_PRIVATE)
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    CallBluetoothAsk.moments(line, unlocks).collect { now ->
                        val bt = Manifest.permission.BLUETOOTH_CONNECT
                        val wanted = CallBluetoothAsk.wanted(
                            line = now,
                            sdk = android.os.Build.VERSION.SDK_INT,
                            declared = declaresPermission(bt),
                            granted = ContextCompat.checkSelfPermission(this@MainActivity, bt) == PackageManager.PERMISSION_GRANTED,
                            askedBefore = prefs.getBoolean(ASKED_BLUETOOTH, false),
                            keyguardLocked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true,
                        )
                        if (wanted) {
                            prefs.edit().putBoolean(ASKED_BLUETOOTH, true).apply()
                            askBluetooth.launch(bt)
                        }
                    }
                }
                roster.map(BatteryAskAfterContact::addedByHand)
                    .distinctUntilChanged()
                    .collect { people ->
                        if (prefs.getBoolean(ASKED_BATTERY, false)) return@collect
                        // Старі встановлення ONBOARDING_DONE не мають (A52, 08.10): знайомство за ними видає позначка asked_at_launch.
                        val settled = prefs.getBoolean(ONBOARDING_DONE, false) || prefs.getBoolean(NotificationAsk.Source.LAUNCH.key, false)
                        val step = BatteryAskAfterContact.step(prefs.getInt(PEOPLE_SEEN, BatteryAskAfterContact.UNSEEN), people, settled)
                        prefs.edit().putInt(PEOPLE_SEEN, step.seen).putBoolean(ASKED_BATTERY, step.ask).apply()
                        if (step.ask && Distribution.batteryAsk && !ignoresBatteryOptimizations()) {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                            }
                        }
                    }
            }
        }
    }

    // Store знімає BLUETOOTH_CONNECT: запит неоголошеного дозволу система відхиляє мовчки.
    private fun declaresPermission(permission: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(permission)
    }.getOrDefault(false)

    private fun ignoresBatteryOptimizations(): Boolean = runCatching {
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)
}

/** Сповіщення — єдине, що старт питає сам, і то після рядка «навіщо». */
@Composable
private fun LaunchNotificationAsk(trigger: androidx.compose.runtime.MutableState<Boolean>) {
    val context = LocalContext.current
    val gate = local.phantom.companion.core.design.permission.rememberPermissionGate(
        permissions = local.phantom.companion.privacy.LaunchAsks.permissions(android.os.Build.VERSION.SDK_INT),
        reason = local.phantom.companion.core.design.permission.PermissionReason(
            local.phantom.companion.core.design.R.drawable.lc_bell,
            R.string.perm_notify_title,
            R.string.perm_notify_body,
        ),
    )
    fun ask(source: NotificationAsk.Source) {
        // onResume приходить і після системного вікна: картка, що вже на екрані, лишається.
        if (gate.stage != local.phantom.companion.core.design.permission.GateAsk.NONE) return
        gate.request { ok ->
            NotificationAsk.answered(context, source)
            if (ok) local.phantom.companion.service.AirAlertService.ensureRunning(context)
        }
    }
    LaunchedEffect(trigger.value) {
        if (!trigger.value) return@LaunchedEffect
        trigger.value = false
        ask(NotificationAsk.Source.LAUNCH)
    }
    LaunchedEffect(Unit) { NotificationAsk.requests.collect { ask(it) } }
}

/** Що потрібно палітрі від світу: кадр сонця (Living) і годинна стрілка (нічний морф). */
private data class Daylight(val look: SunLook, val hour: Int)

/**
 * Фаза сонця і година — перечитуються раз на хвилину, поки активність на
 * екрані, і одразу, коли ОС каже, що годинник чи пояс стрибнули.
 *
 * Тікер свідомий. Межу фази годинник більше не знає — вона залежить від
 * широти, дати й позиції, яка сама може змінитись, — а одна `delay` на
 * хвилину не тримає цикл кадрів (на відміну від нескінченної анімації, від
 * якої застерігала попередня версія): стан змінюється лише тоді, коли фаза
 * справді інша, тож перекомпозиція буває кілька разів на добу.
 * `repeatOnLifecycle` гасить тікер, щойно активність зупинена.
 */
@Composable
private fun rememberDaylight(): Daylight {
    val context = LocalContext.current
    var daylight by remember {
        mutableStateOf(Daylight(LivingSun.lookAt(System.currentTimeMillis(), SunPlaces.firstFrame(context)), wallClockHour()))
    }
    // Bumped by the receiver below to restart the loop at once.
    var clockJumps by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, clockJumps) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                val look = withContext(Dispatchers.IO) { LivingSun.lookNow(context, now) }
                daylight = Daylight(look, wallClockHour())
                delay(60_000L - (System.currentTimeMillis() % 60_000L))
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                clockJumps++
            }
        }
        val filter = IntentFilter(Intent.ACTION_TIME_CHANGED).apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return daylight
}

private fun wallClockHour(): Int =
    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

/**
 * Чи це посилання НАШОЇ мови.
 *
 * Дві схеми, а не одна, бо на апараті власника стоять дві збірки: продакшен
 * ловить `phantom://`, debug — `phantom-debug://` (`BuildConfig.LINK_SCHEME`,
 * задається в `app/build.gradle.kts`), і система більше не питає «яким
 * відкрити». Але ГЕНЕРУЮТЬ обидві однаково — `phantom://…`, — тож рідні
 * сповіщення debug-збірки приходять сюди зі старою схемою і мусять
 * прийматись. Звідси перевірка на обидві, а не на `LINK_SCHEME` саму.
 */
internal fun Uri.isPhantomLink(): Boolean =
    scheme == "phantom" || scheme == BuildConfig.LINK_SCHEME || isSiteInvite()

private fun Uri.isSiteInvite(): Boolean = PeerInvite.isWebLink(toString())

private fun Uri.isInviteLink(): Boolean = isPhantomLink() && (host == "invite" || isSiteInvite())

internal fun isTappedInvite(intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_VIEW && intent.data?.isInviteLink() == true

internal fun invitePayload(intent: Intent?): String? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data?.takeIf { it.isInviteLink() }?.let { uri ->
        if (uri.isSiteInvite()) PeerInvite.codeInLink(uri.toString()).orEmpty()
        else uri.path?.trimStart('/') ?: uri.schemeSpecificPart
    }
    Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
    else -> null
}?.trim()

internal fun groupLinkPayload(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val url = intent.data?.toString() ?: return null
    return url.takeIf { local.phantom.companion.core.net.peer.PeerGroupJoin.isLink(it) }
}

internal fun webSignInPayload(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (uri.scheme != "https" || uri.host != "phantom-os.dev" || uri.path != "/s") return null
    return uri.getQueryParameter("p")?.trim()?.takeIf { it.isNotEmpty() }
}

// Над замком видно весь застосунок, а не окремий екран дзвінка: поки замок стоїть,
// кожна спроба піти з дзвінка просить PIN, а відмова лишає людину в дзвінку.
internal class CallOverLock(
    private val activity: android.app.Activity,
    private val onUnlocked: () -> Unit = {},
) {
    private var raised = false
    private var asking = false

    val guarding: Boolean
        get() = raised && keyguard()?.isKeyguardLocked == true

    fun open(line: PeerCallLine.Line): Boolean {
        if (line !is PeerCallLine.Line.Ringing && line !is PeerCallLine.Line.Talking) return false
        if (!raised) {
            raised = true
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
        return true
    }

    fun follow(line: PeerCallLine.Line): Boolean {
        if (raised && (line is PeerCallLine.Line.Idle || line is PeerCallLine.Line.Over)) {
            raised = false
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
        }
        return raised
    }

    fun leave(): Boolean {
        if (!guarding) return false
        if (!asking) {
            asking = true
            keyguard()?.requestDismissKeyguard(activity, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    asking = false
                    onUnlocked()
                }
                override fun onDismissCancelled() { asking = false }
                override fun onDismissError() { asking = false }
            })
        }
        return true
    }

    private fun keyguard() = activity.getSystemService(android.app.KeyguardManager::class.java)
}

internal data class CallSpot(val chats: Boolean, val conversation: String?) {
    companion object {
        // Перехід зі списку в розмову того, хто дзвонить, — це дорога ДО дзвінка, а не геть.
        fun leaves(before: CallSpot, now: CallSpot, caller: String?): Boolean =
            (before.chats && !now.chats) ||
                (now.conversation != null && now.conversation != caller && now.conversation != before.conversation) ||
                (caller != null && before.conversation == caller && now.conversation != caller)
    }
}

// Лише в розмові: поверх виклику діалог ховав би «взяти», а під час набору
// забирав би чергу в запиту мікрофона з WebView.
internal object CallBluetoothAsk {
    fun wanted(
        line: PeerCallLine.Line,
        sdk: Int,
        declared: Boolean,
        granted: Boolean,
        askedBefore: Boolean,
        keyguardLocked: Boolean,
    ): Boolean = line is PeerCallLine.Line.Talking && sdk >= 31 && declared && !granted &&
        !askedBefore && !keyguardLocked

    // Розмову, взяту над замком, лінія вже не перемкне: питання чекає, поки замок знімуть.
    fun moments(line: kotlinx.coroutines.flow.Flow<PeerCallLine.Line>, unlocks: kotlinx.coroutines.flow.Flow<Int>) =
        kotlinx.coroutines.flow.combine(line, unlocks) { now, _ -> now }
}

internal object BatteryAskAfterContact {
    const val UNSEEN = -1

    data class Step(val ask: Boolean, val seen: Int)

    // Рядок, що народився зі знайомства (addedAtMs == introducedAtMs), додала не ця людина.
    fun addedByHand(roster: List<PeerIdentity>): Int = roster.count {
        it.kind == PeerIdentity.Kind.PERSON && !(it.introducedAtMs > 0L && it.introducedAtMs == it.addedAtMs)
    }

    // Перший замір лише запамʼятовує лічбу: оновлений застосунок із людьми не питає на старті.
    // Посеред знайомства лічба не зсувається — спитаємо, щойно воно скінчиться.
    fun step(seen: Int, people: Int, settled: Boolean = true): Step = when {
        seen == UNSEEN || people <= seen -> Step(ask = false, seen = people)
        !settled -> Step(ask = false, seen = seen)
        else -> Step(ask = true, seen = people)
    }
}
