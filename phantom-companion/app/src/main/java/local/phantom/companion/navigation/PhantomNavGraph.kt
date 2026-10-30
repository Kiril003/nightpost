package local.phantom.companion.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.LocalBottomChromeInset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import local.phantom.companion.PhantomApp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.phantom.companion.feature.onboarding.OnboardingViewModel
import local.phantom.companion.feature.onboarding.ui.LanguageStep
import local.phantom.companion.feature.onboarding.ui.OnboardingScreen
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.feature.stream.ui.vault.VaultScreen
import local.phantom.companion.feature.stream.vault.VaultViewModel
import local.phantom.companion.ui.home.activityPhantomVm
import local.phantom.companion.ui.home.savePhoto
import local.phantom.companion.ui.launcher.ProfileLauncherScreen
import local.phantom.companion.ui.screens.PairScreen
import local.phantom.companion.vm.PhantomViewModel
import local.phantom.companion.core.data.lifelog.LifeLogEntryEntity


@Composable
fun PhantomNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    val navContext = LocalContext.current
    val container = remember { (navContext.applicationContext as PhantomApp).container }

    // Requests are sticky and made before this graph composes; each waits for
    // Bootstrap to land, then clears itself so a return here does not re-fire it.
    suspend fun settled() {
        navController.currentBackStackEntryFlow.first {
            it.destination.route != Routes.Bootstrap.path &&
                it.destination.route != Routes.Language.path &&
                it.destination.route != Routes.Onboarding.path
        }
    }
    fun home() {
        // Домівка вже згори — нічого не чіпати. `popBackStack` тоді повертає
        // false, і `navigate(Home)` перебудовував домівку разом із WebView:
        // відкрита розмова мінялась на список (дзвінок PHANTOM, A52 02.10).
        if (navController.currentDestination?.route == Routes.Home.path) return
        if (!navController.popBackStack(Routes.Home.path, inclusive = false)) {
            navController.navigate(Routes.Home.path) { launchSingleTop = true }
        }
    }

    // Перевірка короткого шляху (LaunchRoute): Bootstrap міг повести на
    // домівку з памʼяті, не дочекавшись Room. Щойно граф зійшов із Bootstrap,
    // звіряємо з профілями; розійшлось — ведемо туди, куди треба насправді, і
    // памʼятаємо нову відповідь для наступного старту.
    LaunchedEffect(Unit) {
        val list = runCatching { container.profileStore.observeAll().first() }.getOrNull() ?: return@LaunchedEffect
        val target = LaunchRoute.target(list)
        LaunchRoute.remember(navContext, target)
        val landed = navController.currentBackStackEntryFlow.first { it.destination.route != Routes.Bootstrap.path }
        if (landed.destination.route == Routes.Home.path && target != Routes.Home.path) {
            navController.navigate(target) { popUpTo(Routes.Home.path) { inclusive = true } }
        }
    }
    LaunchedEffect(Unit) {
        container.homeTabRequest.collect { tab ->
            if (tab == null) return@collect
            settled()
            home()
        }
    }
    LaunchedEffect(Unit) {
        container.mapOpenRequests.collect {
            settled()
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.MAP
        }
    }
    LaunchedEffect(Unit) {
        container.peerChatRequest.collect { id ->
            if (id == null) return@collect
            settled()
            container.peerChatRequest.value = null
            if (id != CALL_ANY) local.phantom.companion.self.PhantomVisits.openChat.value = id
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.CHATS
        }
    }
    LaunchedEffect(Unit) {
        container.peerGroupRequest.collect { id ->
            if (id == null) return@collect
            settled()
            container.peerGroupRequest.value = null
            local.phantom.companion.self.PhantomVisits.openChat.value = id
            container.homeTabRequest.value = local.phantom.companion.ui.home.HomeTab.CHATS
        }
    }
    LaunchedEffect(Unit) {
        container.peerCardRequest.collect { id ->
            if (id == null) return@collect
            settled()
            container.peerCardRequest.value = null
            navController.navigate(Routes.PeerCard.of(id))
        }
    }
    if (local.phantom.companion.BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            container.events.debugRouteRequest.collect { route ->
                if (route == null) return@collect
                settled()
                container.events.debugRouteRequest.value = null
                runCatching { navController.navigate(route) }
            }
        }
    }
    LaunchedEffect(Unit) {
        container.events.routeRequest.collect { route ->
            if (route == null) return@collect
            settled()
            container.events.routeRequest.value = null
            runCatching { navController.navigate(route) { launchSingleTop = true } }
        }
    }

    // Paid rights are handed out here: the lock must be able to lead to the licence screen.
    val licenseVerdict by container.license.state.collectAsStateWithLifecycle()
    val entitlements = remember(licenseVerdict, navController) {
        object : local.phantom.companion.core.design.license.Entitlements {
            override fun has(feature: local.phantom.companion.core.license.LicenseFeature) =
                licenseVerdict.has(feature)

            override fun trialDaysLeft(
                feature: local.phantom.companion.core.license.LicenseFeature,
            ): Int? = licenseVerdict.trialDaysLeft.takeIf { licenseVerdict.trialOnly(feature) }

            override fun openLicense() {
                runCatching { navController.navigate(Routes.SettingsLicense.path) }
            }
        }
    }
    val bottomChrome = remember { local.phantom.companion.core.design.layout.BottomChromeState() }

    CompositionLocalProvider(
        local.phantom.companion.core.design.layout.LocalBottomChrome provides bottomChrome,
        LocalBottomChromeInset provides 0.dp,
        local.phantom.companion.core.design.license.LocalEntitlements provides entitlements,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.Bootstrap.path,
            enterTransition = { PhantomMotion.chamberEnter },
            exitTransition = { PhantomMotion.chamberExit },
            popEnterTransition = { PhantomMotion.chamberReturnEnter },
            popExitTransition = { PhantomMotion.chamberReturnExit },
        ) {
            composable(Routes.Bootstrap.path) { BootstrapDestination(navController) }
            composable(Routes.Language.path) { LanguageDestination(navController) }
            composable(Routes.Onboarding.path) { OnboardingDestination(navController) }
            composable(Routes.Launcher.path) { LauncherDestination(navController) }
            composable(Routes.Pair.path) { PairDestination(navController) }
            composable(Routes.Scan.path) { ScanDestination(navController) }
            composable(Routes.Home.path) { local.phantom.companion.ui.home.HomeScreen(navController) }
            composable(local.phantom.companion.ui.home.ProfileSection.ROUTE) { entry ->
                val section = local.phantom.companion.ui.home.ProfileSection.of(entry.arguments?.getString("section"))
                if (section == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    local.phantom.companion.ui.home.ProfileSectionScreen(section, navController)
                }
            }
            composable(Routes.Vault.path) { VaultDestination(navController) }
            composable(Routes.PeerCard.path) { entry ->
                PeerCardDestination(navController, entry.arguments?.getString("peerId").orEmpty())
            }
            composable(Routes.SettingsDiagnostics.path) {
                local.phantom.companion.ui.settings.DiagnosticsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPrecision = { navController.navigate(Routes.Precision.path) },
                )
            }
            composable(Routes.SettingsTools.path) {
                local.phantom.companion.ui.settings.ToolsSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Precision.path) {
                val ctx = LocalContext.current
                val source = remember(ctx) { local.phantom.companion.feature.precision.LivePrecisionSource(ctx) }
                val stateFlow = remember(source) { source.state() }
                val state by stateFlow.collectAsStateWithLifecycle(
                    initialValue = local.phantom.companion.feature.precision.PrecisionUiState(),
                )
                local.phantom.companion.feature.precision.PrecisionScreen(
                    onBack = { navController.popBackStack() },
                    state = state,
                )
            }
            composable(Routes.SettingsVoice.path) {
                local.phantom.companion.ui.settings.VoiceSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SettingsMic.path) {
                local.phantom.companion.ui.settings.MicPrivacyScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SettingsMap.path) {
                local.phantom.companion.ui.settings.MapSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SettingsSymbiote.path) {
                local.phantom.companion.ui.settings.SymbioteSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SettingsWhisper.path) {
                local.phantom.companion.ui.settings.WhisperSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SettingsLicense.path) {
                local.phantom.companion.ui.settings.LicenseScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SettingsDataLicenses.path) {
                local.phantom.companion.ui.settings.DataLicensesScreen(onBack = { navController.popBackStack() })
            }
            composable(local.phantom.companion.ui.screens.WebSignIn.ROUTE) {
                local.phantom.companion.ui.screens.WebSignInDestination(navController)
            }
        }
    }
}

/** `phantom://call` with nobody on the line still opens the chats. */
internal const val CALL_ANY = "call"

@Composable
private fun BootstrapDestination(navController: NavController) {
    local.phantom.companion.StartMarks.mark("bootstrap:compose")
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }

    // Минулий старт вів на домівку — ідемо туди одразу, не чекаючи Room
    // (LaunchRoute: на A52 це 1,9 с знака по центру). Перший кадр — ескіз
    // домівки; одразу після його малювання прогріваємо WebView месенджера,
    // щоб бандл вантажився, поки домівка компонується. Room перевіряє вибір фоном —
    // див. LaunchedEffect «перевірка короткого шляху» в PhantomNavGraph.
    // Перший запуск (збірка 18): мову ще не обирали — спершу екран мови,
    // раніше навіть за коротким шляхом на домівку. Оновлення з 17-ї без
    // вибору теж бачить його один раз.
    val needsLanguage = remember { AppLanguage.needsPick(context) }
    if (needsLanguage) {
        LaunchedEffect(Unit) {
            navController.navigate(Routes.Language.path) {
                popUpTo(Routes.Bootstrap.path) { inclusive = true }
            }
        }
        local.phantom.companion.core.design.component.PhantomLoaderScreen()
        return
    }

    val homeLast = remember { LaunchRoute.lastWasHome(context) }
    if (homeLast) {
        val theme = local.phantom.companion.core.design.theme.LocalPhantomTheme.current
        val canvas = local.phantom.companion.core.design.tokens.LocalPhantomColors.current.canvas.toArgb()
        val zoom = (local.phantom.companion.core.design.theme.LocalPhantomFontScale.current * 100).roundToInt()
        val view = androidx.compose.ui.platform.LocalView.current
        // The veil over the skeleton is the system splash's last frame, so holding it ~0.8 s
        // while WebView is built shows nothing new; the page starts loading that much sooner.
        androidx.compose.runtime.DisposableEffect(Unit) {
            var live = true
            local.phantom.companion.ui.messenger.WebMessengerPrewarm.afterNextDraw(view) {
                if (!live) return@afterNextDraw
                local.phantom.companion.StartMarks.mark("bootstrap:shortcut")
                (context as? android.app.Activity)?.let { activity ->
                    container.webTheme.value = local.phantom.companion.ui.messenger.webThemePayload(theme)
                    container.webThemePhase.value = local.phantom.companion.ui.messenger.webThemePhase(theme)
                    container.webSunLook.value = local.phantom.companion.ui.messenger.webSunLook(theme)
                    local.phantom.companion.ui.messenger.WebMessengerPrewarm.start(activity) {
                        local.phantom.companion.ui.messenger.createMessengerWeb(activity, container, canvas, zoom)
                    }
                }
                navController.navigate(Routes.Home.path) {
                    popUpTo(Routes.Bootstrap.path) { inclusive = true }
                }
            }
            onDispose { live = false }
        }
        local.phantom.companion.ui.home.HomeSkeleton()
        return
    }

    val profiles by container.profileStore.observeAll()
        .collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(profiles) {
        val list = profiles ?: return@LaunchedEffect
        local.phantom.companion.StartMarks.mark("bootstrap:profiles")
        val target = LaunchRoute.target(list)
        LaunchRoute.remember(context, target)
        navController.navigate(target) {
            popUpTo(Routes.Bootstrap.path) { inclusive = true }
        }
    }
    local.phantom.companion.core.design.component.PhantomLoaderScreen()
}

/**
 * Екран мови: дотик ставить мову Nightpost (рідні рядки, веб-месенджер,
 * помічник) і веде далі — до знайомства, вибору профілю або в розмови, як
 * сказали б профілі на Bootstrap.
 */
@Composable
private fun LanguageDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val scope = rememberCoroutineScope()
    val guess = remember { AppLanguage.firstRunGuess(context) }
    LanguageStep(
        selected = guess,
        onPick = { language ->
            scope.launch {
                // Куди далі — за профілями, як на Bootstrap. Чекаємо Room, а не
                // беремо порожнечу: оновлення з профілем не мусить потрапити
                // на знайомство вдруге.
                val target = LaunchRoute.target(container.profileStore.observeAll().first())
                LaunchRoute.remember(context, target)
                // Android 13+ перестворює активність на новій мові сам (до 13 —
                // AppLanguage сам). Перестворення йде наступним кроком головного
                // потоку, тож перехід нижче встигає першим: відновлений стек уже
                // стоїть на знайомстві, а не знову на мові.
                // Телефон німецькою: рідних екранів німецькою немає, а месенджер є.
                // Незмінений вибір лишає застосунок «як у телефоні», інакше англійська
                // пришпилила б і месенджер.
                if (language == guess && AppLanguage.phoneHasNoneOfOurs()) {
                    AppLanguage.followPhone(context)
                } else {
                    AppLanguage.choose(context, language)
                }
                navController.navigate(target) {
                    popUpTo(Routes.Language.path) { inclusive = true }
                }
            }
        },
    )
}

@Composable
private fun OnboardingDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val vm = remember(container) {
        OnboardingViewModel(profileStore = container.profileStore)
    }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = local.phantom.companion.ui.backup.OpenBackupDocument(),
    ) { uri: Uri? ->
        restoreUri = uri
    }

    if (restoreUri != null) {
        local.phantom.companion.ui.backup.BackupRestoreSheet(
            uri = restoreUri!!,
            onDismiss = { restoreUri = null },
            onSuccess = {
                restoreUri = null
                local.phantom.companion.MainActivity.markOnboardingDone(context)
                local.phantom.companion.ui.backup.AppRestart.relaunch(context)
            },
        )
    }

    OnboardingScreen(
        viewModel = vm,
        restoreAvailable = true,
        onRestore = {
            openDocLauncher.launch(arrayOf("*/*", "application/octet-stream"))
        },
        onPhotoPicked = { uri ->
            container.scope.launch(Dispatchers.IO) { savePhoto(context.applicationContext, container.selfFace, uri) }
        },
        onComplete = { _ ->
            // Аж тепер застосунок має право питати системні дозволи: до цього
            // рядка картка знайомства ще стоїть на екрані.
            local.phantom.companion.MainActivity.markOnboardingDone(context)
            navController.navigate(
                Routes.Home.path,
            ) {
                popUpTo(Routes.Onboarding.path) { inclusive = true }
            }
        },
        // Phase 1.5-A — Familiar voice on state advance. Gated by the
        // operator's tts preference (off by default in fresh installs);
        // the engine itself runs through RoutedTts so locale/voice config
        // is honoured the same way as in-stream replies.
        onNarrate = { line ->
            if (container.voicePrefs.ttsEnabled.value) {
                container.ttsEngine.speak(line)
            }
        },
    )
}

@Composable
private fun LauncherDestination(navController: NavController) {
    ProfileLauncherScreen(
        onProfileActivated = {
            navController.navigate(
                Routes.Home.path,
            ) {
                popUpTo(Routes.Launcher.path) { inclusive = true }
            }
        },
        onAddProfile = {
            navController.navigate(Routes.Onboarding.path)
        },
    )
}

@Composable
private fun PairDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val scanned = remember {
        container.events.pairPayload.value.also { container.events.pairPayload.value = null }
    }
    val vm: PhantomViewModel = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    PairScreen(
        state = state,
        onPair = vm::beginClaim,
        onClaimFromJson = vm::claimFromQrJson,
        onCancel = { navController.popBackStack() },
        onPaired = { navController.popBackStack() },
        scanned = scanned,
    )
}


// The VM never reaches into PhantomApp.container directly — it stays
// Android-host-free so feature-stream tests run on a plain JVM. This is the seam.
@Composable
private fun VaultDestination(navController: NavController) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }

    val vm: VaultViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                VaultViewModel(
                    vaultStore = container.vaultStore,
                    entries = container.vaultStore.getEntries(),
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    VaultScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onUnlockRequested = vm::onUnlockRequested,
        onBiometricSuccess = vm::onBiometricSuccess,
        onUnlockRefused = vm::onUnlockRefused,
        onLockSession = vm::onLockSession,
        onErase = vm::onErase,
        onDismissTrouble = vm::onDismissTrouble,
        onReread = vm::onReread,
    )
}



