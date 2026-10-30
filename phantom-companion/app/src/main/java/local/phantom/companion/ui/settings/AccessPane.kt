package local.phantom.companion.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.Distribution
import local.phantom.companion.R
import local.phantom.companion.core.data.alerts.ALARM_CHANNEL_ID
import local.phantom.companion.core.data.alerts.AirAlertDelivery
import local.phantom.companion.data.LockScreenReveal
import local.phantom.companion.data.LockScreenRevealStore
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ChoiceRow
import local.phantom.companion.ui.home.GrantRow
import local.phantom.companion.ui.home.InfoRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.SwitchRow
import local.phantom.companion.ui.home.TextRow

private const val SCREEN_SERVICE =
    "local.phantom.companion.feature.godmode.service.ScreenAwarenessService"

/** The three OS grants PHANTOM asks for, as they stand right now. */
internal data class PermissionHealth(
    val screen: Boolean = false,
    val notifications: Boolean = false,
    val background: Boolean = false,
) {
    val granted: Int get() = listOf(screen, notifications, background).count { it }
    val total: Int get() = 3
}

@Composable
internal fun rememberPermissionHealth(): PermissionHealth {
    val context = LocalContext.current
    return rememberOnResume(context) { readPermissionHealth(context) }
}

/**
 * Дозвіл «бачити екран» і мʼякий важіль над ним — поруч. Розділені, вони
 * читалися як два незалежні перемикачі; насправді перший є замком другого, і
 * поки замок закритий, перемикача просто немає — світитись «увімкнено» без
 * дозволу він не має права.
 *
 * Служби спецможливостей у store і seeker немає (маніфест store її знімає), тож там
 * рядка немає теж: інакше дотик вів би на системний екран без застосунку.
 */
@Composable
internal fun ScreenAwarenessEntry(
    health: PermissionHealth,
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    if (!Distribution.systemListeners) return
    val context = LocalContext.current
    GrantRow(
        label = stringResource(R.string.look_screen_grant),
        body = stringResource(R.string.look_screen_grant_body),
        granted = health.screen,
        grantedWord = stringResource(R.string.look_granted),
        grantWord = stringResource(R.string.look_grant),
        onGrant = { context.launchSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
    )
    if (health.screen) {
        RowDivider()
        SwitchRow(
            label = stringResource(R.string.look_screen_title),
            checked = enabled,
            body = stringResource(R.string.look_screen_body),
            onChange = onSetEnabled,
        )
    }
}

/**
 * Чи вийде тривога в шторку — і читання чужих сповіщень під нею. Тривога
 * стоїть ВИЩЕ: від неї залежить повітряна тривога, а від того — підказки ШІ.
 * Слухача сповіщень у store і seeker немає, тож і рядка про нього там немає.
 */
@Composable
internal fun NotificationGrantEntry(health: PermissionHealth) {
    val context = LocalContext.current
    if (UkraineAlertsRow()) {
        RowDivider()
        AlertDeliveryRow()
    }
    if (Distribution.systemListeners) {
        RowDivider()
        GrantRow(
            label = stringResource(R.string.settings_perm_notif),
            granted = health.notifications,
            grantedWord = stringResource(R.string.look_granted),
            grantWord = stringResource(R.string.look_grant),
            onGrant = {
                context.launchSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            },
        )
    }
}

/**
 * Чи вийде тривога в шторку — і куди веде дотик.
 *
 * Ціль дотику розгалужується не за станом, а за тим, що ще можливо: доки
 * системний діалог не вичерпаний, ведемо в нього; далі — в налаштування
 * застосунку; якщо дозвіл є, а стишено канал — прямо в канал, бо загальний
 * екран застосунку тут нічого не покаже.
 */
@Composable
private fun AlertDeliveryRow() {
    val context = LocalContext.current
    val delivery = rememberOnResume(context) { AirAlertDelivery.of(context) }
    val label = when (delivery) {
        is AirAlertDelivery.Silent -> stringResource(R.string.settings_alert_delivery_none)
        AirAlertDelivery.Quiet -> stringResource(R.string.settings_alert_delivery_quiet)
        AirAlertDelivery.Fine -> stringResource(R.string.settings_alert_delivery_ok)
    }
    ActionRow(
        label = label,
        action = when (delivery) {
            AirAlertDelivery.Fine -> null
            AirAlertDelivery.Quiet -> stringResource(R.string.look_open)
            is AirAlertDelivery.Silent -> stringResource(R.string.look_turn_on)
        },
        onClick = when (delivery) {
            AirAlertDelivery.Fine -> null
            AirAlertDelivery.Quiet -> {
                {
                    context.launchSettings(
                        Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
                        extras = mapOf(
                            Settings.EXTRA_APP_PACKAGE to context.packageName,
                            Settings.EXTRA_CHANNEL_ID to ALARM_CHANNEL_ID,
                        ),
                    )
                }
            }
            is AirAlertDelivery.Silent -> {
                {
                    context.launchSettings(
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        extras = mapOf(Settings.EXTRA_APP_PACKAGE to context.packageName),
                    )
                }
            }
        },
    )
}

internal fun uaAlertsBody(): Int =
    if (Distribution.airAlertWatch) R.string.settings_ua_alerts_body else R.string.settings_ua_alerts_body_map

/** Лише українською в Україні; типово вимкнено. Повертає, чи увімкнено. */
@Composable
private fun UkraineAlertsRow(): Boolean {
    val context = LocalContext.current
    val prefs = remember { local.phantom.companion.PhantomApp.container().mapPrefs }
    val choice by prefs.ukraineAlerts.collectAsStateWithLifecycle()
    if (!remember { local.phantom.companion.service.AirAlertGate.available(context) }) return false
    val on = remember(choice) { local.phantom.companion.service.AirAlertGate.on(context) }
    SwitchRow(
        label = stringResource(R.string.settings_ua_alerts),
        checked = on,
        onChange = { next ->
            prefs.setUkraineAlerts(next)
            local.phantom.companion.service.AirAlertService.ensureRunning(context)
        },
    )
    return on
}

/** Виняток з економії батареї: без нього система присипляє PHANTOM у кишені. */
@Composable
internal fun BatteryGrantEntry(health: PermissionHealth) {
    val context = LocalContext.current
    GrantRow(
        label = stringResource(R.string.look_battery_title),
        granted = health.background,
        grantedWord = stringResource(R.string.look_granted),
        grantWord = stringResource(R.string.look_grant),
        onGrant = {
            context.launchSettings(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        },
    )
}

/**
 * Що видно на заблокованому екрані.
 *
 * Обидва важелі — цей і «Ховати текст у сповіщеннях» — годують ту саму
 * плашку (PeerNotifications): коли текст сховано всюди, плашка завжди лише
 * «Нове повідомлення», хоч би що обрано тут. Тому за [hiddenEverywhere] вибору
 * немає взагалі — є один факт, який справді діє. Три варіанти з позначкою,
 * що нічого не означає, були б двома правдами на одному екрані.
 *
 * Кількість винятків показана поруч і знімається одним рухом — правило «на
 * замкненому екрані немає тексту» має перевірятись одним поглядом, інакше
 * забутий виняток на одній розмові тихо скасовує його для всіх.
 */
@Composable
internal fun LockScreenRevealRows(hiddenEverywhere: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { LockScreenRevealStore.of(context) }
    val settings by store.state.collectAsStateWithLifecycle()

    if (hiddenEverywhere) {
        InfoRow(
            label = stringResource(R.string.lock_reveal_title),
            value = stringResource(R.string.look_lock_reveal_forced),
        )
        return
    }

    TextRow(stringResource(R.string.lock_reveal_title), stringResource(R.string.lock_reveal_body))
    listOf(
        LockScreenReveal.SYSTEM to (R.string.lock_reveal_system to R.string.lock_reveal_system_cost),
        LockScreenReveal.REDACTED to (R.string.lock_reveal_redacted to R.string.lock_reveal_redacted_cost),
        LockScreenReveal.HIDDEN to (R.string.lock_reveal_hidden to R.string.lock_reveal_hidden_cost),
        LockScreenReveal.FULL to (R.string.lock_reveal_full to R.string.lock_reveal_full_cost),
    ).forEach { (level, words) ->
        val chosen = settings.default == level
        ChoiceRow(
            label = stringResource(words.first),
            selected = chosen,
            body = if (chosen) stringResource(words.second) else null,
            onSelect = { store.setDefault(level) },
        )
    }

    val exceptions = settings.perConversation.size
    if (exceptions > 0) {
        RowDivider()
        ActionRow(
            label = pluralStringResource(R.plurals.lock_reveal_exceptions, exceptions, exceptions),
            action = stringResource(R.string.lock_reveal_reset),
            onClick = { settings.perConversation.keys.forEach { store.setForConversation(it, null) } },
        )
    }
}

private fun Context.launchSettings(
    action: String,
    data: Uri? = null,
    extras: Map<String, String> = emptyMap(),
) {
    runCatching {
        startActivity(
            Intent(action).apply {
                if (data != null) this.data = data
                extras.forEach { (k, v) -> putExtra(k, v) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

private fun readPermissionHealth(context: Context): PermissionHealth = PermissionHealth(
    screen = isAccessibilityServiceEnabled(context, SCREEN_SERVICE),
    notifications = isNotificationServiceEnabled(context),
    background = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false),
)

private fun isAccessibilityServiceEnabled(context: Context, serviceName: String): Boolean {
    val expected = "${context.packageName}/$serviceName"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: ""
    return enabledServices.contains(expected)
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: ""
    return flat.split(":").any { name ->
        ComponentName.unflattenFromString(name)?.packageName == context.packageName
    }
}
