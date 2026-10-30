package local.phantom.companion.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.layout.HideBottomChrome
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.solana.WalletActivity
import local.phantom.companion.data.erase.EraseEverything
import local.phantom.companion.data.erase.eraseEverything
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.RowTone
import local.phantom.companion.ui.home.TextRow
import local.phantom.companion.ui.home.activityPhantomVm

@Composable
internal fun DeleteEverythingRow() {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val vm = activityPhantomVm()
    val state by vm.state.collectAsStateWithLifecycle()
    val controller = remember { DangerZoneController() }
    val phase by controller.phase.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val askCredential = rememberEraseCredential()
    var outcome by remember { mutableStateOf<EraseEverything.Outcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(phase) {
        while (phase == DangerZoneController.Phase.Cooldown && !controller.canCommit()) {
            delay(250L)
            tick++
        }
    }

    fun executeErase(authorised: Boolean) {
        if (!authorised || busy) return
        busy = true
        scope.launch {
            outcome = withContext(Dispatchers.IO) {
                container.eraseEverything(context).run(authorised = true)
            }
            busy = false
        }
    }

    RowGroup {
        val openConfirm: () -> Unit = {
            outcome = null
            controller.openFirstConfirm()
        }
        val commitConfirm: () -> Unit = {
            controller.commit {
                askCredential { authorised -> executeErase(authorised) }
            }
        }
        val retryErase: () -> Unit = {
            askCredential { authorised -> executeErase(authorised) }
        }
        val wipePhoneOnly: () -> Unit = {
            busy = true
            scope.launch(Dispatchers.IO) {
                container.eraseEverything(context).thisPhoneOnly()
            }
        }
        when (phase) {
            DangerZoneController.Phase.Idle -> {
                ActionRow(
                    label = stringResource(R.string.delete_everything),
                    danger = true,
                    onClick = if (busy) null else openConfirm,
                )
            }
            DangerZoneController.Phase.FirstConfirm,
            DangerZoneController.Phase.Cooldown,
            -> {
                TextRow(stringResource(R.string.delete_everything_after))
                RowDivider()
                CaptionRow(stringResource(R.string.delete_everything_stays))
                TextRow(stringResource(R.string.delete_everything_left_backup))
                RowDivider()
                TextRow(stringResource(R.string.delete_everything_left_photos))
                RowDivider()
                TextRow(stringResource(R.string.delete_everything_left_letters))
                if (state.paired != null) {
                    RowDivider()
                    TextRow(stringResource(R.string.delete_everything_pc))
                }
                RowDivider()
                if (phase == DangerZoneController.Phase.FirstConfirm) {
                    ActionRow(
                        label = stringResource(R.string.delete_everything),
                        danger = true,
                        onClick = { controller.acceptFirstConfirm() },
                    )
                } else {
                    val remaining = remember(tick, phase) { controller.remainingCooldownSeconds() }
                    val ready = remember(tick, phase) { controller.canCommit() }
                    ActionRow(
                        label = if (ready) {
                            stringResource(R.string.delete_everything)
                        } else {
                            "${stringResource(R.string.delete_everything)} · $remaining"
                        },
                        tone = if (ready) RowTone.Danger else RowTone.Quiet,
                        danger = ready,
                        onClick = if (ready) commitConfirm else null,
                    )
                }
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.delete_everything_cancel),
                    onClick = { controller.cancel() },
                )
            }
        }
        when (outcome) {
            is EraseEverything.Outcome.Failed -> {
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.delete_everything_retry),
                    onClick = if (busy) null else retryErase,
                )
                RowDivider()
                CaptionRow(stringResource(R.string.delete_everything_phone_only_note))
                ActionRow(
                    label = stringResource(R.string.delete_everything_phone_only),
                    danger = true,
                    onClick = if (busy) null else wipePhoneOnly,
                )
            }
            EraseEverything.Outcome.Protected -> {
                RowDivider()
                TextRow(stringResource(R.string.delete_everything_protected))
            }
            else -> Unit
        }
    }
}

@Composable
private fun CaptionRow(text: String) {
    val c = LocalPhantomColors.current
    Text(
        text = text,
        style = PhantomText.footnote,
        color = c.text3,
        modifier = Modifier.padding(start = PhantomSpace.s4, end = PhantomSpace.s4, top = PhantomSpace.rowV),
    )
}

internal fun cancelFailureText(outcome: EraseEverything.Outcome?): Int? =
    (outcome as? EraseEverything.Outcome.Failed)?.let {
        if (it.result == EraseEverything.EraseServer.Result.Unreachable) {
            R.string.delete_pending_offline
        } else {
            R.string.delete_pending_failed
        }
    }

@Composable
internal fun PendingDeletionScreen(
    dueAtMs: Long,
    onRestored: () -> Unit,
) {
    HideBottomChrome()
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val scope = rememberCoroutineScope()
    val cancelLabel = stringResource(R.string.delete_pending_cancel)
    val askCredential = rememberEraseCredential(title = cancelLabel)
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val dateText = remember(dueAtMs) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dueAtMs))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = PhantomSpace.gutterPhone),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (dueAtMs > 0) {
            Text(
                text = stringResource(R.string.delete_pending_on, dateText),
                style = PhantomText.title2,
                color = c.text,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(
                text = stringResource(R.string.delete_pending_note),
                style = PhantomText.callout,
                color = c.text2,
            )
            Spacer(Modifier.height(PhantomSpace.s5))
        }
        val cancelDeletion: () -> Unit = {
            askCredential { authorised ->
                if (authorised && !busy) {
                    busy = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            container.eraseEverything(context).cancel(authorised = true)
                        }
                        busy = false
                        failure = cancelFailureText(res)
                        if (res == EraseEverything.Outcome.Restored) {
                            onRestored()
                        }
                    }
                }
            }
        }
        RowGroup {
            val reason = failure
            if (reason != null) {
                TextRow(stringResource(reason))
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.delete_everything_retry),
                    onClick = if (busy) null else cancelDeletion,
                )
            } else {
                ActionRow(
                    label = cancelLabel,
                    onClick = if (busy) null else cancelDeletion,
                )
            }
            RowDivider()
            ActionRow(
                label = stringResource(R.string.delete_pending_wallet),
                onClick = { context.startActivity(Intent(context, WalletActivity::class.java)) },
            )
        }
    }
}
