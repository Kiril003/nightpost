package local.phantom.companion.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.phantom.companion.BuildConfig
import local.phantom.companion.Distribution
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.ai.route.NightpostBrainRoute
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.component.PhantomSlider
import local.phantom.companion.core.design.license.LocalEntitlements
import local.phantom.companion.core.design.theme.FONT_SCALE_MAX
import local.phantom.companion.core.design.theme.FONT_SCALE_MIN
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.core.license.LicenseReason
import local.phantom.companion.data.AppEventBus
import local.phantom.companion.data.appearance.Appearance
import local.phantom.companion.data.appearance.Wallpaper
import local.phantom.companion.data.backup.setCloudBackup
import local.phantom.companion.feature.stream.voice.rememberRecordAudioPermissionGate
import local.phantom.companion.license.LicenseService
import local.phantom.companion.navigation.Routes
import local.phantom.companion.ui.messenger.AgencySessionManager
import local.phantom.companion.ui.settings.AiKeyEntry
import local.phantom.companion.ui.settings.AssetsBody
import local.phantom.companion.ui.settings.BackgroundLocationEntry
import local.phantom.companion.ui.settings.BatteryGrantEntry
import local.phantom.companion.ui.settings.BrainOrderRows
import local.phantom.companion.ui.settings.DeleteEverythingRow
import local.phantom.companion.ui.settings.DeleteModelsEntry
import local.phantom.companion.ui.settings.DirectoryHandleEntry
import local.phantom.companion.ui.settings.FalsePositionEntry
import local.phantom.companion.ui.settings.HfTokenEntry
import local.phantom.companion.ui.settings.LockScreenRevealRows
import local.phantom.companion.ui.settings.NotificationGrantEntry
import local.phantom.companion.ui.settings.OperatorStanceEntry
import local.phantom.companion.ui.settings.RetentionBody
import local.phantom.companion.ui.settings.ScreenAwarenessEntry
import local.phantom.companion.ui.settings.SettingsViewModel
import local.phantom.companion.ui.settings.rememberPermissionHealth
import local.phantom.companion.vm.PhantomViewModel

enum class ProfileSection(val slug: String) {
    SETTINGS("settings"),
    PRIVACY("privacy"),
    APP_LOCK("app-lock"),
    NOTIFICATIONS("notifications"),
    DEVICES("devices"),
    PHANTOM("phantom"),
    POINTS("points"),
    DATA("data"),
    APPEARANCE("appearance"),
    LANGUAGE("language"),
    HELP("help"),
    DEVELOPER("developer"),
    ADVANCED("advanced"),
    ;

    val route: String get() = "profile/$slug"

    companion object {
        const val ROUTE = "profile/{section}"
        fun of(slug: String?): ProfileSection? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * Секції ділять один маршрут `profile/{section}`, тож launchSingleTop підміняв
 * Налаштування Виглядом, і «Назад» минав Налаштування. Повтор того самого — ні.
 */
internal fun NavHostController.openSection(route: String) {
    val here = currentBackStackEntry
    val shown = here?.arguments?.getString("section")?.let { "profile/$it" } ?: here?.destination?.route
    if (shown != route) navigate(route)
}

@Composable
internal fun ProfileSectionScreen(section: ProfileSection, navController: NavHostController) {
    val back: () -> Unit = { navController.popBackStack() }
    fun go(route: String) = navController.openSection(route)
    when (section) {
        ProfileSection.SETTINGS -> SettingsSection(back, ::go)
        ProfileSection.PRIVACY -> PrivacySection(back, ::go)
        ProfileSection.NOTIFICATIONS -> NotificationsSection(back)
        ProfileSection.DEVICES -> DevicesSection(back, ::go)
        ProfileSection.PHANTOM -> PhantomSection(back, ::go)
        ProfileSection.POINTS -> PointsSection(back)
        ProfileSection.DATA -> DataSection(back, ::go)
        ProfileSection.APPEARANCE -> AppearanceSection(back)
        ProfileSection.LANGUAGE -> LanguageSection(back)
        ProfileSection.HELP -> HelpSection(back, ::go)
        ProfileSection.DEVELOPER -> DeveloperSection(back, ::go)
        ProfileSection.ADVANCED -> AdvancedSection(back)
        ProfileSection.APP_LOCK -> local.phantom.companion.ui.applock.AppLockSection(back)
    }
}

@Composable
private fun PrivacySection(back: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val lock = remember(context) { local.phantom.companion.data.applock.AppLockStore.of(context) }
    val lockState by lock.settings.collectAsStateWithLifecycle()
    val health = rememberPermissionHealth()
    val screenAware by container.voicePrefs.screenAwarenessEnabled.collectAsStateWithLifecycle()
    SubScreen(stringResource(R.string.profile_privacy), back) {
        RowGroup {
            NavRow(
                stringResource(R.string.applock_row),
                DesignR.drawable.lc_lock,
                value = stringResource(if (lockState.enabled) R.string.applock_on else R.string.applock_off),
            ) { go(ProfileSection.APP_LOCK.route) }
            RowDivider()
            SwitchRow(
                stringResource(R.string.applock_hide_text),
                checked = lockState.hidesText,
            ) { on -> lock.setHideNotificationText(on) }
            RowDivider()
            // Та сама плашка, що й «Ховати текст»: коли текст сховано всюди,
            // вибору для заблокованого екрана немає — є один факт, що діє.
            LockScreenRevealRows(hiddenEverywhere = lockState.hidesText)
        }
        RowGroup {
            val previews = remember { container.linkPreviewSetting }
            val makePreviews by previews.enabled.collectAsStateWithLifecycle()
            SwitchRow(
                stringResource(R.string.link_preview_row),
                checked = makePreviews,
            ) { previews.set(it) }
            RowDivider()
            DirectoryHandleEntry()
            RowDivider()
            CloudBackupRows(container)
        }
        RowGroup {
            NavRow(stringResource(R.string.privacy_mic), DesignR.drawable.lc_mic) {
                go(Routes.SettingsMic.path)
            }
            RowDivider()
            NavRow(stringResource(R.string.privacy_actions), DesignR.drawable.lc_privacy) {
                go(Routes.SettingsTools.path)
            }
            RowDivider()
            NavRow(stringResource(R.string.privacy_vault), DesignR.drawable.lc_lock) {
                go(Routes.Vault.path)
            }
        }
        // Служби спецможливостей у store і seeker немає — і групи про неї теж.
        if (Distribution.systemListeners) {
            RowGroup(caption = stringResource(R.string.look_privacy_screen)) {
                ScreenAwarenessEntry(
                    health = health,
                    enabled = screenAware,
                    onSetEnabled = container.voicePrefs::setScreenAwarenessEnabled,
                )
            }
        }
        // ACCESS_BACKGROUND_LOCATION у store знято: кнопка вела б на системний
        // екран, де дозволу «завжди» для застосунку вже немає.
        if (Distribution.backgroundLocation) {
            RowGroup(caption = stringResource(R.string.privacy_location)) {
                BackgroundLocationEntry()
            }
        }
        RowGroup {
            NavRow(stringResource(R.string.privacy_system), DesignR.drawable.lc_info) { openAppDetails(context) }
        }
    }
}

@Composable
private fun CloudBackupRows(container: local.phantom.companion.data.AppContainer) {
    val context = LocalContext.current
    val setting = remember(context) { local.phantom.companion.data.backup.AutoBackup.setting(context) }
    val on by setting.on.collectAsStateWithLifecycle()
    val cloudKey by setting.cloudKey.collectAsStateWithLifecycle()
    var held by remember(context) { mutableStateOf(local.phantom.companion.data.backup.AutoBackup.held(context)) }
    var secure by remember(context) { mutableStateOf(deviceSecure(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) secure = deviceSecure(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val state = local.phantom.companion.data.backup.cloudBackupState(on = on, held = held, deviceSecure = secure, cloudKey = cloudKey)
    SwitchRow(
        stringResource(R.string.cloud_backup_row),
        checked = on && !held,
        body = when (state) {
            local.phantom.companion.data.backup.CloudBackupState.HELD -> stringResource(R.string.cloud_backup_held)
            local.phantom.companion.data.backup.CloudBackupState.WEAK_NO_LOCK -> stringResource(R.string.cloud_backup_weak)
            local.phantom.companion.data.backup.CloudBackupState.PHONE_ONLY -> stringResource(R.string.cloud_backup_no_cloud)
            else -> null
        },
    ) { next ->
        held = false
        setting.set(next)
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.setCloudBackup(next) }
        }
    }
    if (state == local.phantom.companion.data.backup.CloudBackupState.WEAK_NO_LOCK) {
        RowDivider()
        NavRow(stringResource(R.string.cloud_backup_set_lock), DesignR.drawable.lc_lock) {
            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

private fun deviceSecure(context: Context): Boolean =
    context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

@Composable
private fun NotificationsSection(back: () -> Unit) {
    val context = LocalContext.current
    val health = rememberPermissionHealth()
    SubScreen(stringResource(R.string.profile_notifications), back) {
        RowGroup {
            NavRow(
                stringResource(R.string.notif_system),
                DesignR.drawable.lc_bell,
            ) { openNotificationSettings(context) }
        }
        RowGroup(caption = stringResource(R.string.notif_alerts_title)) {
            NotificationGrantEntry(health = health)
        }
        HushExceptionsGroup()
        if (Distribution.batteryAsk) {
            RowGroup(caption = stringResource(R.string.look_notif_background)) {
                BatteryGrantEntry(health = health)
            }
        }
    }
}

/** Chats muted in the messenger: the shade obeys this same store, so the list is the truth. */
@Composable
private fun HushExceptionsGroup() {
    val store = remember { PhantomApp.container().chatHush }
    val hush by store.state.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val rows = hush.live(now).rows()
    val locale = LocalContext.current.resources.configuration.locales[0]
    val mentions by store.mentionsThrough.collectAsStateWithLifecycle()
    RowGroup(caption = stringResource(R.string.notif_hush_title)) {
        SwitchRow(label = stringResource(R.string.notif_hush_mentions), checked = mentions, onChange = store::setMentionsThrough)
        RowDivider()
        if (rows.isEmpty()) {
            ActionRow(label = stringResource(R.string.notif_hush_none))
        }
        rows.forEachIndexed { i, (id, end, title) ->
            if (i > 0) RowDivider()
            val ends = hushEndText(end, now, java.time.ZoneId.systemDefault(), locale)
            ActionRow(
                label = title ?: stringResource(R.string.peer_unknown_sender),
                body = ends?.let { stringResource(R.string.notif_hush_until, it) } ?: stringResource(R.string.notif_hush_forever),
                action = stringResource(R.string.notif_hush_unmute),
                onClick = { store.clear(id) },
            )
        }
    }
}

@Composable
private fun DevicesSection(back: () -> Unit, go: (String) -> Unit) {
    val vm: PhantomViewModel = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val entitlements = LocalEntitlements.current
    SubScreen(stringResource(R.string.profile_devices), back) {
        DevicesBody(
            paired = state.paired,
            canPair = entitlements.has(LicenseFeature.BridgePair),
            onUnpair = { vm.unpair() },
            onPair = { go(Routes.Pair.path) },
            onOpenLicense = entitlements::openLicense,
        )
        ThisPhoneKey()
    }
}

@Composable
private fun PhantomSection(back: () -> Unit, go: (String) -> Unit) {
    val container = remember { PhantomApp.container() }
    val vm: PhantomViewModel = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val key by container.aiKeyStore.geminiKey.collectAsStateWithLifecycle()
    // Порядок мозків — збережений вибір людини; контейнер віддає той самий
    // потік, який читає роутер на кожен запит, тож екран і роутер не розходяться.
    val routing by container.brainPrefs.routing.collectAsStateWithLifecycle()
    // Сервер Nightpost — розум без ключа й без ПК; «є» лише коли він сам сказав так
    // (і не «лише на пристрої»), тож рядок «Розум» не бреше ні в той, ні в той бік.
    // Тим, у кого є ПК чи свій ключ, до сервера не ходимо взагалі: статус тут нічого б не змінив.
    val serverMind by produceState<NightpostBrainRoute.Standing?>(null, routing, key, state.paired) {
        value = if (state.paired != null || !key.isNullOrBlank()) null
        else runCatching { container.nightpostBrain.standing() }.getOrNull() ?: NightpostBrainRoute.Standing.UNKNOWN
    }
    val voice = container.voicePrefs
    val speaks by voice.ttsEnabled.collectAsStateWithLifecycle()
    val proactive by voice.proactiveEnabled.collectAsStateWithLifecycle()
    val live by voice.liveDialogEnabled.collectAsStateWithLifecycle()
    val micMuted by voice.micMuted.collectAsStateWithLifecycle()
    val voiceGate = rememberRecordAudioPermissionGate()
    SubScreen(stringResource(R.string.profile_server), back) {
        RowGroup(caption = stringResource(R.string.phantom_mind)) {
            InfoRow(
                stringResource(R.string.phantom_mind),
                mindValue(state.paired != null, !key.isNullOrBlank(), serverMind)?.let { stringResource(it) }.orEmpty(),
            )
            RowDivider()
            if (state.paired == null) {
                NavRow(stringResource(R.string.devices_pc_connect), DesignR.drawable.lc_devices) { go(Routes.Pair.path) }
                RowDivider()
            }
            NavRow(stringResource(R.string.phantom_advanced), DesignR.drawable.lc_layers) { go(ProfileSection.ADVANCED.route) }
        }
        RowGroup(caption = stringResource(R.string.look_brain_order)) {
            BrainOrderRows(
                routing = routing,
                onRoutingChange = container.brainPrefs::setRouting,
                keyStored = !key.isNullOrBlank(),
            )
        }
        RowGroup(caption = stringResource(R.string.look_phantom_talk)) {
            SwitchRow(
                stringResource(R.string.look_tts_title),
                checked = speaks,
            ) { voice.setTtsEnabled(it) }
            RowDivider()
            SwitchRow(
                stringResource(R.string.look_proactive_title),
                checked = proactive,
            ) { voice.setProactiveEnabled(it) }
            RowDivider()
            if (micMuted) {
                ActionRow(
                    label = stringResource(R.string.look_live_title),
                    action = stringResource(R.string.look_live_unmute),
                    onClick = { voice.setMicMuted(false) },
                )
            } else {
                SwitchRow(
                    stringResource(R.string.look_live_title),
                    checked = live,
                ) { on ->
                    if (on) voiceGate.requestThen { voice.setLiveDialogEnabled(true) }
                    else voice.setLiveDialogEnabled(false)
                }
            }
        }
        RowGroup {
            NavRow(stringResource(R.string.phantom_voice), DesignR.drawable.lc_volume) {
                go(Routes.SettingsVoice.path)
            }
            RowDivider()
            NavRow(stringResource(R.string.phantom_memory), DesignR.drawable.lc_brain) {
                openPhantomChat(container.events)
            }
        }
    }
}

@Composable
private fun AdvancedSection(back: () -> Unit) {
    SubScreen(stringResource(R.string.phantom_advanced), back) {
        RowGroup {
            AiKeyEntry()
        }
    }
}

/**
 * «Що я про вас знаю» живе в розмові з PHANTOM — меню чату, сторінка
 * «Що PHANTOM знає» (PhantomKnows) читає те саме ядро памʼяті, що й колишній
 * окремий екран. Тому рядок Профілю відкриває саму розмову: збирач запиту в
 * навграфі вмикає вкладку Чати й розгортає чат PHANTOM.
 */
internal fun openPhantomChat(events: AppEventBus) {
    events.peerChatRequest.value = AgencySessionManager.PHANTOM_AGENT_ID
}

@Composable
private fun DataSection(back: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val settingsVm: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    val days by settingsVm.retentionDays.collectAsStateWithLifecycle()
    val stats by settingsVm.retention.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, settingsVm) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) settingsVm.refreshRetention() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var showBackupSheet by remember { mutableStateOf(false) }
    var lastBackupText by remember {
        val epoch = container.backupKeys.lastBackupEpochMs()
        val text = if (epoch > 0L) {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epoch))
        } else {
            null
        }
        mutableStateOf(text)
    }

    val oldPhoneHint = remember {
        local.phantom.companion.data.backup.showOldPhoneHint(container.backupKeys.restoredAtMs(), System.currentTimeMillis())
    }

    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        local.phantom.companion.ui.backup.OpenBackupDocument(),
    ) { uri: Uri? -> restoreUri = uri }
    val mediaLeft = remember { local.phantom.companion.data.backup.BackupLocal.left(context.filesDir) }
    val solana by local.phantom.companion.core.solana.SolanaLayer.state.collectAsStateWithLifecycle()
    val backupOn by local.phantom.companion.data.backup.AutoBackup.setting(context).on.collectAsStateWithLifecycle()
    var walletRow by remember { mutableStateOf(local.phantom.companion.data.backup.WalletInCopy.NONE) }
    DisposableEffect(owner, solana.builtIn, backupOn) {
        fun read() {
            walletRow = local.phantom.companion.data.backup.walletInCopy(
                hasWallet = solana.builtIn != null,
                on = backupOn,
                held = local.phantom.companion.data.backup.AutoBackup.held(context),
                secure = deviceSecure(context),
                slot = local.phantom.companion.data.backup.AutoBackup.slotFor(context).has(),
            )
        }
        read()
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) read() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    restoreUri?.let { uri ->
        local.phantom.companion.ui.backup.BackupRestoreSheet(
            uri = uri,
            onDismiss = { restoreUri = null },
            onSuccess = {
                restoreUri = null
                local.phantom.companion.ui.backup.AppRestart.relaunch(context)
            },
        )
    }

    if (showBackupSheet) {
        local.phantom.companion.ui.backup.BackupCreateSheet(
            onDismiss = { showBackupSheet = false },
            onBackupSaved = { dateText ->
                lastBackupText = dateText
            },
        )
    }

    SubScreen(stringResource(R.string.profile_data), back) {
        RowGroup {
            NavRow(
                label = stringResource(R.string.profile_backup),
                icon = DesignR.drawable.lc_download,
                value = lastBackupText ?: stringResource(R.string.profile_backup_none),
            ) { showBackupSheet = true }
            if (oldPhoneHint) TextRow(stringResource(R.string.profile_backup_old_phone))
            RowDivider()
            NavRow(stringResource(R.string.profile_backup_restore), DesignR.drawable.lc_data) {
                pickBackup.launch(arrayOf("*/*", "application/octet-stream"))
            }
            if (mediaLeft > 0) TextRow(androidx.compose.ui.res.pluralStringResource(R.plurals.profile_backup_media_left, mediaLeft, mediaLeft))
            when (walletRow) {
                local.phantom.companion.data.backup.WalletInCopy.NO_LOCK -> TextRow(stringResource(R.string.profile_backup_wallet_no_lock))
                local.phantom.companion.data.backup.WalletInCopy.NEEDS_WORDS -> TextRow(stringResource(R.string.profile_backup_wallet_needs_words))
                else -> Unit
            }
        }
        RowGroup {
            NavRow(stringResource(R.string.profile_offline_maps), DesignR.drawable.lc_offline_maps) { go(Routes.SettingsMap.path) }
        }
        RowGroup {
            PaneSlot {
                RetentionBody(
                    days = days,
                    stats = stats,
                    onApplyInstant = settingsVm::applyRetention,
                    onConfirmShorten = settingsVm::confirmShortenRetention,
                )
            }
        }
        if (deleteEverythingVisible(BuildConfig.DEBUG, eraseUnlocked(context))) DeleteEverythingRow()
    }
}

@Composable
private fun AppearanceSection(back: () -> Unit) {
    val c = LocalPhantomColors.current
    val vm: PhantomViewModel = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val mode = when (state.themeMode) {
        ThemeMode.Light -> ThemeMode.Light
        ThemeMode.Dark -> ThemeMode.Dark
        else -> ThemeMode.Living
    }
    val store = remember { PhantomApp.container().appearance }
    val look by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val save: ((Appearance) -> Appearance) -> Unit = { change -> scope.launch(Dispatchers.IO) { store.update(change) } }
    var dark by remember(c.isDark) { mutableStateOf(c.isDark) }
    SubScreen(stringResource(R.string.profile_appearance), back) {
        LookPreview(store, look, dark)
        RowGroup(caption = stringResource(R.string.appearance_theme)) {
            ThemeTiles(
                options = listOf(
                    ThemeMode.Living to stringResource(R.string.appearance_auto),
                    ThemeMode.Light to stringResource(R.string.appearance_day),
                    ThemeMode.Dark to stringResource(R.string.appearance_night),
                ),
                selected = mode,
                onSelect = vm::setThemeMode,
            )
        }
        RowGroup(caption = stringResource(R.string.appearance_accent)) {
            AccentSwatches(look.accent, c.isDark) { id -> save { it.copy(accent = id) } }
        }
        RowGroup(caption = stringResource(R.string.appearance_wallpaper)) {
            WallpaperChoices(store, look)
            if (look.wallpaper.kind != Wallpaper.NONE) ShadeControls(store, look, dark) { dark = it }
        }
        RowGroup(caption = stringResource(R.string.appearance_text)) {
            var draft by remember(state.fontScale) { mutableFloatStateOf(state.fontScale) }
            Column(Modifier.padding(PhantomSpace.s4)) {
                Text(stringResource(R.string.appearance_text_sample), style = PhantomText.body, color = c.text)
                PhantomSlider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { vm.setFontScale(draft) },
                    valueRange = FONT_SCALE_MIN..FONT_SCALE_MAX,
                    steps = 8,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appearance_text_smaller), style = PhantomText.caption, color = c.text3)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.appearance_text_larger), style = PhantomText.caption, color = c.text3)
                }
            }
        }
        RowGroup(caption = stringResource(R.string.appearance_bubbles)) {
            DensityChoice(look.bubbles) { v -> save { it.copy(bubbles = v) } }
        }
        RowGroup(caption = stringResource(R.string.appearance_list)) {
            DensityChoice(look.list) { v -> save { it.copy(list = v) } }
        }
    }
}

/**
 * Профіль › Мова — той самий вибір, що й на першому екрані ([AppLanguage]):
 * англійська перша, далі решта мов із перекладом, прапор і власна назва.
 * Рідні екрани, месенджер і відповіді помічника міняються разом;
 * під списком пояснень немає.
 * Дотик до вже показаної мови теж ставить її: після «Як у системі» в
 * налаштуваннях Android показана мова — не обовʼязково та, що діє.
 */
@Composable
private fun LanguageSection(back: () -> Unit) {
    val context = LocalContext.current
    val current = AppLanguage.current(context)
    val phone = AppLanguage.followsPhone(context)
    SubScreen(stringResource(R.string.profile_language), back) {
        RowGroup {
            ChoiceRow(
                label = stringResource(R.string.language_system),
                leading = "📱",
                selected = phone,
                onSelect = { AppLanguage.followPhone(context) },
            )
            AppLanguage.offered.forEach { language ->
                RowDivider()
                ChoiceRow(
                    label = language.nativeName,
                    leading = language.flag,
                    selected = !phone && language == current,
                    onSelect = { AppLanguage.choose(context, language) },
                )
            }
        }
    }
}

@Composable
private fun HelpSection(back: () -> Unit, go: (String) -> Unit) {
    val container = remember { PhantomApp.container() }
    val verdict by container.license.state.collectAsStateWithLifecycle()
    SubScreen(stringResource(R.string.profile_help), back) {
        RowGroup {
            NavRow(stringResource(R.string.help_sources), DesignR.drawable.lc_map) { go(Routes.SettingsDataLicenses.path) }
            RowDivider()
            NavRow(
                stringResource(R.string.help_license),
                DesignR.drawable.lc_info,
                value = stringResource(helpLicenseValue(container.license.integrity.tampered, verdict.reason)),
            ) { go(Routes.SettingsLicense.path) }
        }
        RowGroup {
            NavRow(stringResource(R.string.help_reports), DesignR.drawable.lc_alert_circle) {
                local.phantom.companion.ui.messenger.WebNav.openHelp { container.homeTabRequest.value = it }
            }
        }
        RowGroup {
            InfoRow(stringResource(R.string.help_version), plainVersion())
        }
    }
}

/**
 * Що каже рядок «Ліцензія» в Допомозі: причину, а не наслідок. Коли підпис
 * чужий, рівень і так «Вільний» — сертифікат до воріт не доходить, — тож
 * значення називає підпис. Інакше факт лишався б на екран глибше, і його
 * ніхто б не шукав.
 */
@StringRes
internal fun helpLicenseValue(tampered: Boolean, reason: LicenseReason): Int =
    if (tampered) R.string.license_signature_row else LicenseService.explain(reason)

@Composable
private fun DeveloperSection(back: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val vm: PhantomViewModel = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    SubScreen(stringResource(R.string.profile_developer), back) {
        RowGroup {
            NavRow(stringResource(R.string.dev_diagnostics), DesignR.drawable.lc_code) { go(Routes.SettingsDiagnostics.path) }
            RowDivider()
            NavRow(stringResource(R.string.dev_precision), DesignR.drawable.lc_locate) { go(Routes.Precision.path) }
            RowDivider()
            NavRow(stringResource(R.string.dev_symbiote), DesignR.drawable.lc_brain) { go(Routes.SettingsSymbiote.path) }
            // Стрічка малює поверх інших застосунків; у store цього дозволу немає.
            if (Distribution.overlays) {
                RowDivider()
                NavRow(stringResource(R.string.dev_whisper), DesignR.drawable.lc_layers) { go(Routes.SettingsWhisper.path) }
            }
        }
        RowGroup(caption = stringResource(R.string.look_dev_keys)) {
            HfTokenEntry()
        }
        RowGroup(caption = stringResource(R.string.look_dev_place)) {
            OperatorStanceEntry()
            if (Distribution.falsePosition) {
                RowDivider()
                FalsePositionEntry()
            }
        }
        RowGroup(caption = stringResource(R.string.look_dev_models)) {
            PaneSlot {
                AssetsBody(
                    modelStates = state.modelStates,
                    onDownloadModel = vm::downloadModel,
                    onDeleteModel = vm::deleteModel,
                    onImportModel = { type, uri -> settingsVm.importModel(type, uri, context.contentResolver) },
                )
            }
            RowDivider()
            DeleteModelsEntry(
                anyModelPresent = state.modelStates.values.any { it.isPresent },
                onDeleteAll = { state.modelStates.keys.forEach { vm.deleteModel(it) } },
            )
        }
        if (BuildConfig.DEBUG) {
            var allowErase by remember { mutableStateOf(eraseUnlocked(context)) }
            RowGroup {
                SwitchRow(
                    stringResource(R.string.delete_everything_allow),
                    checked = allowErase,
                ) { on ->
                    allowErase = on
                    setEraseUnlocked(context, on)
                }
            }
        }
        RowGroup(caption = stringResource(R.string.look_dev_build)) {
            InfoRow(stringResource(R.string.look_build), "${BuildConfig.BUILD_TYPE} · ${BuildConfig.VERSION_CODE}")
            RowDivider()
            InfoRow(stringResource(R.string.look_package), BuildConfig.APPLICATION_ID)
            RowDivider()
            InfoRow(
                stringResource(R.string.look_web_bundle),
                "${BuildConfig.WEB_BUNDLE_SHA.take(8)} · ${BuildConfig.WEB_BUNDLE_SYNCED}",
            )
        }
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openAppDetails(context) }
}
