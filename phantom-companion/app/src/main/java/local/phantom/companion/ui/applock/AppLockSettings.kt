package local.phantom.companion.ui.applock

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.data.applock.AppLock
import local.phantom.companion.data.applock.AppLockStore
import local.phantom.companion.ui.home.NavRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.Segmented
import local.phantom.companion.ui.home.SubScreen
import local.phantom.companion.ui.home.SwitchRow

/** Крок налаштування замка: що зараз питає клавіатура. */
private sealed interface Step {
    data object List : Step
    data object NewCode : Step
    data class Confirm(val first: String) : Step
    data object CheckForChange : Step
    data object CheckForOff : Step
}

/** Профіль › Приватність і безпека › Замок застосунку. */
@Composable
internal fun AppLockSection(back: () -> Unit) {
    val context = LocalContext.current
    val c = LocalPhantomColors.current
    val lock = remember(context) { AppLockStore.of(context) }
    val s by lock.settings.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf<Step>(Step.List) }
    var note by remember { mutableStateOf<String?>(null) }
    val canBio = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    val goList = { step = Step.List; note = null }

    SubScreen(stringResource(R.string.applock_row), if (step == Step.List) back else goList) {
        when (val now = step) {
            Step.NewCode -> PinPad(
                title = stringResource(R.string.applock_new_code),
                note = note,
                confirmLabel = stringResource(R.string.applock_next),
                onDone = { code -> note = null; step = Step.Confirm(code); true },
            )
            is Step.Confirm -> PinPad(
                title = stringResource(R.string.applock_repeat_code),
                note = note,
                confirmLabel = stringResource(R.string.applock_save),
                onDone = { code ->
                    if (code == now.first && lock.setPasscode(code)) { goList(); true } else {
                        note = context.getString(R.string.applock_mismatch)
                        step = Step.NewCode
                        false
                    }
                },
            )
            Step.CheckForChange, Step.CheckForOff -> PinPad(
                title = stringResource(R.string.applock_current_code),
                note = note,
                confirmLabel = stringResource(R.string.applock_next),
                onDone = { code ->
                    val right = lock.unlock(code) == AppLock.Attempt.Opened
                    when {
                        !right -> { note = context.getString(R.string.applock_wrong); false }
                        now == Step.CheckForOff -> { lock.disable(code); goList(); true }
                        else -> { note = null; step = Step.NewCode; true }
                    }
                },
            )
            Step.List -> {
                Text(
                    text = stringResource(R.string.applock_explain),
                    style = PhantomText.footnote,
                    color = c.text3,
                    modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, bottom = PhantomSpace.s3),
                )
                if (!s.enabled) {
                    RowGroup {
                        NavRow(stringResource(R.string.applock_turn_on), DesignR.drawable.lc_lock) { step = Step.NewCode }
                    }
                } else {
                    RowGroup {
                        if (canBio) {
                            SwitchRow(stringResource(R.string.applock_biometric_row), s.biometric) { lock.setBiometric(it) }
                            RowDivider()
                        }
                        NavRow(stringResource(R.string.applock_change)) { step = Step.CheckForChange }
                        RowDivider()
                        NavRow(stringResource(R.string.applock_turn_off)) { step = Step.CheckForOff }
                    }
                    RowGroup(caption = stringResource(R.string.applock_auto)) {
                        val labels = listOf(
                            R.string.applock_auto_now, R.string.applock_auto_1m, R.string.applock_auto_5m, R.string.applock_auto_1h,
                        ).map { stringResource(it) }
                        Segmented(AppLock.AUTO_LOCK_CHOICES.zip(labels), s.autoLockMs) { lock.setAutoLock(it) }
                    }
                }
                Spacer(Modifier.height(PhantomSpace.s2))
            }
        }
    }
}
