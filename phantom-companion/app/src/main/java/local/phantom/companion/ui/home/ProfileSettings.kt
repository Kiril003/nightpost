package local.phantom.companion.ui.home

import androidx.annotation.StringRes
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.BuildConfig
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.ai.route.NightpostBrainRoute
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.core.net.peer.PeerRoster
import local.phantom.companion.navigation.Routes
import local.phantom.companion.ui.messenger.WebNav
import local.phantom.companion.vm.PhantomViewModel
import local.phantom.companion.core.design.R as DesignR

private const val PROFILE_PREFS = "phantom_profile_ui"
private const val KEY_DEV = "developer_unlocked"
private const val KEY_ERASE_DEBUG = "erase_in_debug"
private const val DEV_TAPS = 7

internal fun developerUnlocked(context: Context): Boolean =
    context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEV, false)

private fun unlockDeveloper(context: Context) {
    context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEV, true).apply()
}

internal fun eraseUnlocked(context: Context): Boolean =
    context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ERASE_DEBUG, false)

internal fun setEraseUnlocked(context: Context, unlocked: Boolean) {
    context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ERASE_DEBUG, unlocked).apply()
}

internal fun deleteEverythingVisible(isDebug: Boolean, unlocked: Boolean) = !isDebug || unlocked

/** Усе, що не є обличчям людини, — за одним рядком «Налаштування» на Профілі. */
@Composable
internal fun SettingsSection(back: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val vm: PhantomViewModel = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val geminiKey by container.aiKeyStore.geminiKey.collectAsStateWithLifecycle()
    val serverMind by produceState<NightpostBrainRoute.Standing?>(null, geminiKey, state.paired) {
        value = if (state.paired != null || !geminiKey.isNullOrBlank()) null
        else runCatching { container.nightpostBrain.standing() }.getOrNull() ?: NightpostBrainRoute.Standing.UNKNOWN
    }
    var devOpen by remember { mutableStateOf(developerUnlocked(context)) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<Toast?>(null) }

    fun tapVersion() {
        if (devOpen) return
        versionTaps++
        val left = DEV_TAPS - versionTaps
        toast?.cancel()
        toast = if (left <= 0) {
            unlockDeveloper(context)
            devOpen = true
            Toast.makeText(context, R.string.profile_dev_unlocked, Toast.LENGTH_SHORT)
        } else if (left <= 3) {
            Toast.makeText(context, context.getString(R.string.profile_dev_steps, left), Toast.LENGTH_SHORT)
        } else {
            null
        }
        toast?.show()
    }

    SubScreen(stringResource(R.string.profile_settings), back) {
        SettingsBody(
            facts = SettingsFacts(
                appearance = when (state.themeMode) {
                    ThemeMode.Light -> stringResource(R.string.profile_value_day)
                    ThemeMode.Dark -> stringResource(R.string.profile_value_night)
                    else -> stringResource(R.string.profile_value_auto)
                },
                language = languageValue(AppLanguage.current(context)),
                devices = state.paired?.let { stringResource(R.string.profile_value_count, 1) },
                server = mindValue(state.paired != null, !geminiKey.isNullOrBlank(), serverMind)?.let { stringResource(it) },
                version = plainVersion(),
                developer = devOpen,
            ),
            go = go,
            onVersion = { tapVersion() },
            onCalls = {
                WebNav.request(WebNav.Door.OpenTab(CALL_SETTINGS_TAB))
                container.homeTabRequest.value = HomeTab.CHATS
                back()
            },
            onPeople = {
                WebNav.openPlace(WebNav.PLACE_PEOPLE) { container.homeTabRequest.value = it }
                back()
            },
            onPages = {
                WebNav.openPlace(WebNav.PLACE_BUILDER) { container.homeTabRequest.value = it }
                back()
            },
            points = { PointsRow(go) },
        )
    }
}

/** Ключ веб-шторки дзвінків для всіх розмов; той самий відчиняє рядок «Дзвінки» на веб-екрані Я. */
internal const val CALL_SETTINGS_TAB = "call-settings"

internal class SettingsFacts(
    val appearance: String,
    val language: String,
    val devices: String?,
    val server: String?,
    val version: String,
    val developer: Boolean,
)

/** Три картки без заголовків; `points` кладе свій рядок із роздільником сам, лише коли є що показати. */
@Composable
internal fun SettingsBody(
    facts: SettingsFacts,
    go: (String) -> Unit,
    onVersion: () -> Unit,
    onCalls: () -> Unit,
    onPeople: () -> Unit = {},
    onPages: () -> Unit = {},
    points: @Composable () -> Unit,
) {
    Column(Modifier.testTag("settings-group-1").fillMaxWidth()) {
        RowGroup {
            NavRow(stringResource(R.string.profile_appearance), DesignR.drawable.lc_appearance, value = facts.appearance) {
                go(ProfileSection.APPEARANCE.route)
            }
            RowDivider()
            NavRow(stringResource(R.string.profile_language), DesignR.drawable.lc_language, value = facts.language) {
                go(ProfileSection.LANGUAGE.route)
            }
            RowDivider()
            NavRow(stringResource(R.string.profile_notifications), DesignR.drawable.lc_bell) { go(ProfileSection.NOTIFICATIONS.route) }
            RowDivider()
            NavRow(stringResource(R.string.profile_calls), DesignR.drawable.lc_phone, body = stringResource(R.string.profile_calls_all), onClick = onCalls)
            RowDivider()
            NavRow(stringResource(R.string.profile_people), DesignR.drawable.lc_people, onClick = onPeople)
            RowDivider()
            NavRow(stringResource(R.string.profile_pages), DesignR.drawable.lc_layers, onClick = onPages)
        }
    }
    Column(Modifier.testTag("settings-group-2").fillMaxWidth()) {
        RowGroup {
            NavRow(stringResource(R.string.profile_privacy), DesignR.drawable.lc_privacy) { go(ProfileSection.PRIVACY.route) }
            RowDivider()
            NavRow(stringResource(R.string.profile_devices), DesignR.drawable.lc_devices, value = facts.devices) {
                go(ProfileSection.DEVICES.route)
            }
            RowDivider()
            NavRow(stringResource(R.string.profile_offline_maps), DesignR.drawable.lc_offline_maps) { go(Routes.SettingsMap.path) }
            RowDivider()
            NavRow(stringResource(R.string.profile_data), DesignR.drawable.lc_data) { go(ProfileSection.DATA.route) }
        }
    }
    Column(Modifier.testTag("settings-group-3").fillMaxWidth()) {
        RowGroup {
            NavRow(stringResource(R.string.profile_server), DesignR.drawable.lc_chats, value = facts.server) {
                go(ProfileSection.PHANTOM.route)
            }
            points()
            RowDivider()
            NavRow(stringResource(R.string.profile_help), DesignR.drawable.lc_help) { go(ProfileSection.HELP.route) }
            if (facts.developer) {
                RowDivider()
                NavRow(stringResource(R.string.profile_developer), DesignR.drawable.lc_code) { go(ProfileSection.DEVELOPER.route) }
            }
            RowDivider()
            Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onVersion)) {
                InfoRow(stringResource(R.string.help_version), facts.version)
            }
        }
    }
}

/** Що каже рядок «Nightpost»: той самий маршрут, яким іде питання; null — ще питаємо сервер. */
@StringRes
internal fun mindValue(pc: Boolean, ownKey: Boolean, server: NightpostBrainRoute.Standing?): Int? = when {
    pc -> R.string.profile_value_on_pc
    ownKey -> R.string.profile_value_cloud
    server == null -> null
    server == NightpostBrainRoute.Standing.UP -> R.string.profile_value_server
    server == NightpostBrainRoute.Standing.SPENT -> R.string.profile_value_server_spent
    server == NightpostBrainRoute.Standing.UNKNOWN -> R.string.profile_value_server_quiet
    else -> R.string.profile_value_not_set
}

/** Мова в рядку — назва самою мовою, без прапора (прапори лишились на виборі мови, OD9). */
internal fun languageValue(language: AppLanguage): String = language.nativeName

internal fun plainVersion(raw: String = BuildConfig.VERSION_NAME): String =
    raw.substringBefore('+').substringBefore('-')

/** Пристрої › Цей телефон: відбиток і дата ключа — довідка, не рядок Налаштувань. */
@Composable
internal fun DeviceKeyRows(fingerprint: String?, created: String?) {
    if (fingerprint == null && created == null) return
    RowGroup(caption = stringResource(R.string.devices_this_phone)) {
        fingerprint?.let { InfoRow(stringResource(R.string.profile_key_short), it) }
        if (fingerprint != null && created != null) RowDivider()
        created?.let { InfoRow(stringResource(R.string.profile_key_created), it) }
    }
}

@Composable
internal fun ThisPhoneKey() {
    val container = remember { PhantomApp.container() }
    val meId by container.mesh.selfIdFlow.collectAsStateWithLifecycle()
    val created by produceState(0L) {
        value = withContext(Dispatchers.IO) { runCatching { container.profileStore.getActive()?.createdAtEpochMs }.getOrNull() ?: 0L }
    }
    DeviceKeyRows(
        fingerprint = if (meId.isNotBlank()) PeerRoster.shortId(meId) else null,
        created = if (meId.isNotBlank() && created > 0L) {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(created))
        } else {
            null
        },
    )
}
