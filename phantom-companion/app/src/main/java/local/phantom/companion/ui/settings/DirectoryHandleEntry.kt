package local.phantom.companion.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerDirectory
import local.phantom.companion.data.PeerDirectoryStore
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ProfileDialog
import local.phantom.companion.ui.home.ProfileField
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowTone

/**
 * МОЄ ІМʼЯ ДЛЯ ПОШУКУ.
 *
 * Стоїть у «Приватності»: це не оздоба профілю, а рішення
 * про те, чи існує слово, за яким цього власника можна знайти на сервері.
 * З коробки такого слова немає, і рядок про це каже вголос — «не опубліковано»
 * тут стан, а не порожнє поле.
 *
 * Знімається одним дотиком, і локальний запис гасне тільки після того, як
 * сервер підтвердив: показувати «знято», поки запис живий, було б рівно тією
 * брехнею, від якої цей рядок мав би боронити.
 */
@Composable
internal fun DirectoryHandleEntry() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val handle by container.directory.handle.collectAsStateWithLifecycle()
    val until by container.directory.untilSeconds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var asking by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val name = handle
    val expired = remember(until) {
        PeerDirectoryStore.expired(until, System.currentTimeMillis())
    }

    LaunchedEffect(handle, until) { notice = null }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_LIFE_MS)
            notice = null
        }
    }

    ActionRow(
        label = stringResource(R.string.dir_row_title),
        body = notice,
        action = when {
            busy -> stringResource(R.string.dir_working)
            expired -> stringResource(R.string.dir_row_republish)
            name != null -> "@$name"
            else -> stringResource(R.string.dir_row_off)
        },
        // Імʼя, що вже стоїть і живе, — значення, не дія.
        tone = if (name != null && !expired) RowTone.Quiet else RowTone.Action,
        // Поки триває запит, дотик нічого не робив би мовчки — рядок
        // просто перестає бути дією і каже про це словом. Після строку
        // рядок знову стає дією: імʼя на екрані є, у довіднику — вже ні.
        onClick = if (busy || (name != null && !expired)) {
            null
        } else {
            { draft = name.orEmpty(); notice = null; asking = true }
        },
    )
    if (name != null) {
        RowDivider()
        ActionRow(
            label = stringResource(R.string.dir_change_title),
            action = stringResource(R.string.dir_row_change),
            onClick = if (busy) null else { { draft = name; notice = null; asking = true } },
        )
        RowDivider()
        ActionRow(
            label = stringResource(R.string.dir_withdraw_title),
            action = stringResource(R.string.dir_row_withdraw),
            tone = RowTone.Danger,
            onClick = if (busy) {
                null
            } else {
                {
                    busy = true
                    notice = null
                    scope.launch {
                        val out = container.directory.withdraw()
                        if (out == PeerDirectoryStore.Outcome.Withdrawn) container.profileHerald.bump()
                        notice = context.wordFor(out)
                        busy = false
                    }
                }
            },
        )
    }

    if (asking) {
        ProfileDialog(
            title = stringResource(R.string.dir_dialog_title),
            body = stringResource(R.string.dir_dialog_body),
            confirm = stringResource(R.string.dir_dialog_save),
            // Гасне, поки імʼя не має форми, яку прийме сервер: краще
            // недоступна дія, ніж дія, що приносить 400 у відповідь.
            confirmEnabled = !busy && PeerDirectory.normaliseForPublish(draft) != null,
            onConfirm = {
                val asked = draft
                asking = false
                busy = true
                notice = null
                scope.launch {
                    val out = container.directory.publish(asked)
                    if (out is PeerDirectoryStore.Outcome.Published) container.profileHerald.bump()
                    notice = context.wordFor(out)
                    busy = false
                }
            },
            onDismiss = { asking = false },
        ) {
            ProfileField(value = draft, onValueChange = { draft = it }, hint = stringResource(R.string.dir_dialog_label))
            val bad = draft.isNotBlank() && PeerDirectory.normaliseForPublish(draft) == null
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(
                text = stringResource(if (bad) R.string.dir_err_bad_handle else R.string.dir_dialog_findable),
                style = PhantomText.callout,
                color = if (bad) LocalPhantomColors.current.danger else LocalPhantomColors.current.text2,
            )
        }
    }
}

/** null — вийшло; сам рядок уже показує новий стан, і додати нема чого. */
private fun Context.wordFor(outcome: PeerDirectoryStore.Outcome): String? = when (outcome) {
    is PeerDirectoryStore.Outcome.Published -> null
    PeerDirectoryStore.Outcome.Withdrawn -> null
    PeerDirectoryStore.Outcome.Taken -> getString(R.string.dir_err_taken)
    PeerDirectoryStore.Outcome.BadHandle -> getString(R.string.dir_err_bad_handle)
    PeerDirectoryStore.Outcome.NoIdentity -> getString(R.string.dir_err_no_identity)
    PeerDirectoryStore.Outcome.NoAddress -> getString(R.string.dir_err_no_address)
    // Слово сервера про самого себе. Наше «не достукались» тут було б менш
    // правдиве за те, що він щойно сказав.
    is PeerDirectoryStore.Outcome.Closed -> outcome.detail
    PeerDirectoryStore.Outcome.Unreachable -> getString(R.string.dir_err_unreachable)
    PeerDirectoryStore.Outcome.Refused -> getString(R.string.dir_err_refused)
}

/** Скільки живе слово про відмову. Довше — і воно вже не про теперішнє. */
private const val NOTICE_LIFE_MS = 6_000L
