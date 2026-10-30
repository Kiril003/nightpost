package local.phantom.companion.core.design.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

class PermissionReason(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
)

class PermissionGate internal constructor(
    private val ask: (onAnswer: (Boolean) -> Unit) -> Unit,
    private val held: () -> Boolean,
    private val state: () -> GateAsk,
    private val confirm: () -> Unit,
) {
    fun granted(): Boolean = held()

    val stage: GateAsk get() = state()

    /** Відповідь приходить рівно один раз: true — дозвіл є, false — людина відмовилась. */
    fun request(onAnswer: (Boolean) -> Unit) = ask(onAnswer)

    fun requestThen(action: () -> Unit) = ask { if (it) action() }

    /** Те саме, що дотик до «Дозволити» на картці. */
    fun allow() = confirm()
}

/**
 * [anyOf] — для місця: «приблизне» теж відповідь, мапа з ним працює.
 */
@Composable
fun rememberPermissionGate(
    permissions: List<String>,
    reason: PermissionReason,
    anyOf: Boolean = false,
): PermissionGate {
    val context = LocalContext.current
    val model = remember { PermissionGateModel() }
    var ask by remember { mutableStateOf(GateAsk.NONE) }
    val satisfied = remember(context, permissions, anyOf) {
        {
            val held = permissions.map { permissionHeld(context, it) }
            if (anyOf) held.any { it } else held.all { it }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        PermissionMoment.answered()
        val ok = satisfied()
        model.onResult(
            granted = ok,
            canAskAgain = canAskAgain(ok, rationale(context, permissions), deniedBefore(context, permissions)),
        )
        markDenied(context, permissions, denied = !ok)
        ask = model.ask
    }

    val allow = {
        if (model.allow()) launcher.launch(permissions.toTypedArray())
        ask = model.ask
    }

    when (ask) {
        GateAsk.REASON, GateAsk.DENIED, GateAsk.BLOCKED -> {
            val blocked = ask == GateAsk.BLOCKED
            GateCard(
                icon = reason.icon,
                title = when (ask) {
                    GateAsk.DENIED -> stringResource(R.string.perm_denied_title)
                    GateAsk.BLOCKED -> stringResource(R.string.perm_blocked_title)
                    else -> stringResource(reason.title)
                },
                body = if (blocked) stringResource(R.string.perm_blocked_body) else stringResource(reason.body),
                primary = stringResource(if (blocked) R.string.perm_open_settings else R.string.perm_allow),
                secondary = stringResource(R.string.perm_not_now),
                onPrimary = {
                    if (blocked) {
                        model.openSettings()
                        runCatching { context.startActivity(appSettingsIntent(context)) }
                        ask = model.ask
                    } else {
                        allow()
                    }
                },
                onSecondary = {
                    model.dismiss()
                    ask = model.ask
                },
            )
        }
        GateAsk.NONE, GateAsk.ASKING -> Unit
    }

    // allow змінюється з кожною композицією (launcher), тож gate тримає його через rememberUpdatedState.
    val currentAllow by rememberUpdatedState(allow)
    return remember(model, satisfied) {
        PermissionGate(
            ask = { onAnswer ->
                // Остаточна відмова — це стан дозволу, а не картки: відмовляли раніше
                // і Android більше не пояснює (rationale немає) — вікна не буде.
                val blocked = !satisfied() && deniedBefore(context, permissions) && !rationale(context, permissions)
                model.request(granted = satisfied(), blocked = blocked, onAnswer = onAnswer)
                ask = model.ask
            },
            held = satisfied,
            state = { ask },
            confirm = { currentAllow() },
        )
    }
}

fun permissionHeld(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun rationale(context: Context, permissions: List<String>): Boolean {
    val activity = context.findActivity() ?: return false
    return permissions.any { !permissionHeld(context, it) && activity.shouldShowRequestPermissionRationale(it) }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private const val GATE_PREFS = "permission_gate"

private fun deniedBefore(context: Context, permissions: List<String>): Boolean {
    val prefs = context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE)
    return permissions.any { prefs.getBoolean("denied:$it", false) }
}

private fun markDenied(context: Context, permissions: List<String>, denied: Boolean) {
    val edit = context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE).edit()
    permissions.forEach { edit.putBoolean("denied:$it", denied) }
    edit.apply()
}

/** Спільна поверхня питання: причина, відмова, налаштування. */
@Composable
fun GateCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    primary: String,
    secondary: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Dialog(onDismissRequest = onSecondary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PhantomRadius.sheet))
                .background(c.raised)
                .padding(PhantomSpace.s6),
        ) {
            Box(
                modifier = Modifier
                    .size(PhantomSize.avatarM)
                    .clip(CircleShape)
                    .background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = c.accentText,
                    modifier = Modifier.size(PhantomSize.iconM),
                )
            }
            Spacer(Modifier.height(PhantomSpace.s4))
            Text(text = title, style = PhantomText.title2, color = c.text)
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(text = body, style = PhantomText.body, color = c.text2)
            Spacer(Modifier.height(PhantomSpace.s6))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = PhantomSize.buttonL)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onSecondary)
                        .padding(horizontal = PhantomSpace.s4),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = secondary, style = PhantomText.headline, color = c.text2)
                }
                Spacer(Modifier.width(PhantomSpace.s2))
                Box(
                    modifier = Modifier
                        .heightIn(min = PhantomSize.buttonL)
                        .clip(CircleShape)
                        .background(c.accent)
                        .clickable(role = Role.Button, onClick = onPrimary)
                        .padding(horizontal = PhantomSpace.s5),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = primary,
                        style = PhantomText.headline,
                        color = c.onAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
