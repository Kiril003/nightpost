package local.phantom.companion.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ProfileDialog
import local.phantom.companion.ui.home.ProfileField
import local.phantom.companion.ui.home.RowDivider

/**
 * Ключ хмарної моделі: вставити, змінити — і прибрати, поруч.
 *
 * Прибирання тут гасить ЛИШЕ ключ Gemini. Стара кнопка старого екрана
 * (спільне очищення ключів у `SettingsViewModel`) гасила разом і токен HuggingFace, хоча той
 * лише качає моделі й до відповідей стосунку не має: людина, що прибирала
 * «ключ розуму», мовчки втрачала й доступ до закритих моделей. Токен HF має
 * власний рядок і власне прибирання — у розділі «Для розробника».
 */
@Composable
internal fun AiKeyEntry() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    if (!container.aiKeyStore.canStore) {
        ActionRow(
            label = stringResource(R.string.ai_key_title),
            body = stringResource(R.string.key_seal_unavailable),
        )
        return
    }
    val key by container.aiKeyStore.geminiKey.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val stored = !key.isNullOrBlank()

    ActionRow(
        label = stringResource(R.string.ai_key_title),
        body = if (stored) {
            stringResource(R.string.ai_key_body_set)
        } else {
            stringResource(R.string.ai_key_body_unset)
        },
        action = stringResource(if (stored) R.string.ai_key_action_change else R.string.ai_key_action_paste),
        onClick = { draft = ""; asking = true },
    )
    if (stored) {
        RowDivider()
        ActionRow(
            label = stringResource(R.string.look_ai_key_remove),
            danger = true,
            onClick = { removing = true },
        )
    }

    if (asking) {
        ProfileDialog(
            title = stringResource(R.string.ai_key_dialog_title),
            body = stringResource(R.string.ai_key_dialog_body),
            confirm = stringResource(R.string.ai_key_save),
            confirmEnabled = draft.trim().length >= 20,
            onConfirm = {
                container.aiKeyStore.saveGeminiKey(draft.trim())
                asking = false
            },
            onDismiss = { asking = false },
        ) {
            ProfileField(value = draft, onValueChange = { draft = it }, hint = stringResource(R.string.ai_key_dialog_hint))
        }
    }

    if (removing) {
        ProfileDialog(
            title = stringResource(R.string.look_ai_key_remove_title),
            body = stringResource(R.string.look_ai_key_remove_body),
            confirm = stringResource(R.string.look_remove),
            danger = true,
            onConfirm = {
                container.aiKeyStore.saveGeminiKey(null)
                removing = false
            },
            onDismiss = { removing = false },
        )
    }
}
