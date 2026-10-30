package local.phantom.companion.core.solana

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

internal object CopyPassword {
    /** Чому пароль не годиться (рядок), або null — годиться. */
    fun problem(first: String, second: String?): Int? = when {
        first.length < WalletBackupFile.MIN_PASSWORD -> R.string.sol_copy_password
        second != null && first != second -> R.string.sol_copy_mismatch
        else -> null
    }
}

/** Зберегти копію: відбиток → пароль двічі → місце у файловій системі → зашифрований файл. */
@Composable
internal fun SaveCopyFlow(door: WalletDoor, onClose: () -> Unit, say: (WalletOutcome<*>, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var password by remember { mutableStateOf<CharArray?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WalletBackupFile.MIME)) { uri: Uri? ->
        val w = words
        val p = password
        if (uri == null || w == null || p == null) return@rememberLauncherForActivityResult onClose()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = WalletBackupFile.seal(w, p)
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }.isSuccess
            }
            p.fill(' ')
            val text = if (ok) {
                val (name, place) = withContext(Dispatchers.IO) { CopyPlace.read(ctx, uri) }
                SolanaLayer.markCopied(name, place)
                CopyPlace.label(ctx, place)?.let { ctx.getString(R.string.sol_copy_saved_at, it) } ?: ctx.getString(R.string.sol_copy_saved)
            } else {
                ctx.getString(R.string.sol_copy_failed)
            }
            say(WalletOutcome.Done(Unit), text)
            onClose()
        }
    }
    LaunchedEffect(Unit) {
        if (!door.host(R.string.sol_confirm_words).confirm()) return@LaunchedEffect onClose()
        words = runCatching { withContext(Dispatchers.IO) { SolanaLayer.backupWords() } }.getOrNull()
        if (words == null) {
            say(WalletOutcome.Failed(VaultLocked.WHY), null)
            onClose()
        }
    }
    if (words == null) return
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var why by remember { mutableStateOf<Int?>(null) }
    WalletSheet(title = stringResource(R.string.sol_copy_save), onDismiss = onClose, secure = true) {
        PasswordField(first, { first = it; why = null }, stringResource(R.string.sol_copy_password), why?.takeIf { it == R.string.sol_copy_password }?.let { stringResource(it) })
        PasswordField(second, { second = it; why = null }, stringResource(R.string.sol_copy_password_again), why?.takeIf { it == R.string.sol_copy_mismatch }?.let { stringResource(it) })
        SheetActions(
            confirm = stringResource(R.string.sol_copy_choose),
            onConfirm = {
                why = CopyPassword.problem(first, second)
                if (why == null) {
                    password = first.toCharArray()
                    create.launch(WalletBackupFile.NAME)
                }
            },
            dismiss = stringResource(R.string.sol_cancel),
            onDismiss = onClose,
            enabled = first.isNotEmpty() && second.isNotEmpty(),
        )
    }
}

/** Відновити з копії: файл → пароль → ті самі слова, що й «Відновити зі слів». */
@Composable
internal fun OpenCopyFlow(door: WalletDoor, onClose: () -> Unit, say: (WalletOutcome<*>, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult onClose()
        picked = uri
        scope.launch {
            bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() }
            if (bytes == null) {
                say(WalletOutcome.Done(Unit), ctx.getString(R.string.sol_copy_bad))
                onClose()
            }
        }
    }
    LaunchedEffect(Unit) { pick.launch(arrayOf("*/*")) }
    val file = bytes ?: return
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    WalletSheet(title = stringResource(R.string.sol_copy_restore), onDismiss = onClose, secure = true) {
        WalletWarning(stringResource(R.string.sol_restore_warning))
        PasswordField(text, { text = it; bad = false }, stringResource(R.string.sol_copy_password), if (bad) stringResource(R.string.sol_copy_bad) else null)
        SheetActions(
            confirm = stringResource(R.string.sol_copy_open),
            onConfirm = {
                busy = true
                scope.launch {
                    val words = withContext(Dispatchers.Default) { WalletBackupFile.open(file, text.toCharArray()) }
                    if (words == null) {
                        busy = false
                        bad = true
                        return@launch
                    }
                    val restored = restoreSeed(door, say) { SolanaLayer.restore(words) }
                    busy = false
                    when (restored) {
                        true -> {
                            picked?.let { u -> withContext(Dispatchers.IO) { CopyPlace.read(ctx, u) } }?.let { (name, place) -> SolanaLayer.markCopied(name, place) }
                            say(WalletOutcome.Done(Unit), ctx.getString(R.string.sol_restore_ok))
                            onClose()
                        }
                        false -> bad = true
                        null -> onClose()
                    }
                }
            },
            dismiss = stringResource(R.string.sol_cancel),
            onDismiss = onClose,
            enabled = text.isNotEmpty() && !busy,
        )
    }
}

/** Перевірити копію: файл → пароль → відбиток → чи це слова саме цього гаманця. Гаманець не міняється. */
@Composable
internal fun CheckCopyFlow(door: WalletDoor, onClose: () -> Unit, say: (WalletOutcome<*>, String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult onClose()
        picked = uri
        scope.launch {
            bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() }
            if (bytes == null) {
                say(WalletOutcome.Done(Unit), ctx.getString(R.string.sol_copy_bad))
                onClose()
            }
        }
    }
    LaunchedEffect(Unit) { pick.launch(arrayOf("*/*")) }
    val file = bytes ?: return
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    WalletSheet(title = stringResource(R.string.sol_copy_check), onDismiss = onClose, secure = true) {
        PasswordField(text, { text = it; bad = false }, stringResource(R.string.sol_copy_password), if (bad) stringResource(R.string.sol_copy_bad) else null)
        SheetActions(
            confirm = stringResource(R.string.sol_copy_check_go),
            onConfirm = {
                busy = true
                scope.launch {
                    val password = text.toCharArray()
                    val result = CopyCheck.of(file, password) {
                        if (door.host(R.string.sol_confirm_words).confirm()) withContext(Dispatchers.IO) { SolanaLayer.backupWords() } else null
                    }
                    password.fill(' ')
                    busy = false
                    when (result) {
                        CopyCheck.Result.BAD_PASSWORD -> bad = true
                        CopyCheck.Result.LOCKED -> {
                            say(WalletOutcome.Failed(VaultLocked.WHY), null)
                            onClose()
                        }
                        CopyCheck.Result.OTHER_WALLET -> {
                            say(WalletOutcome.Done(Unit), ctx.getString(R.string.sol_copy_other))
                            onClose()
                        }
                        CopyCheck.Result.SAME -> {
                            val (name, place) = picked?.let { u -> withContext(Dispatchers.IO) { CopyPlace.read(ctx, u) } } ?: (null to null)
                            SolanaLayer.markCopied(name, place)
                            val label = CopyPlace.label(ctx, place)
                            say(WalletOutcome.Done(Unit), label?.let { ctx.getString(R.string.sol_copy_same_at, it) } ?: ctx.getString(R.string.sol_copy_same))
                            onClose()
                        }
                    }
                }
            },
            dismiss = stringResource(R.string.sol_cancel),
            onDismiss = onClose,
            enabled = text.isNotEmpty() && !busy,
        )
    }
}

/** Що з копією: де вона й що з нею можна зробити. */
@Composable
internal fun BackupSheet(
    status: BackupStatus,
    onCheck: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onRestoreWords: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val copy = status as? BackupStatus.Copy
    WalletSheet(
        title = stringResource(if (copy != null) R.string.sol_backup_status_copy else R.string.sol_backup_status_words),
        onDismiss = onDismiss,
    ) {
        if (copy != null) {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            WalletLine(CopyPlace.line(copy.atMs, CopyPlace.label(ctx, copy.place), locale))
            copy.name?.let { WalletLine(it) }
            WalletAction(stringResource(R.string.sol_copy_check), modifier = Modifier.fillMaxWidth(), accent = true, onClick = onCheck)
            WalletAction(stringResource(R.string.sol_copy_save_again), modifier = Modifier.fillMaxWidth(), onClick = onSave)
            WalletAction(stringResource(R.string.sol_copy_restore_short), modifier = Modifier.fillMaxWidth(), onClick = onRestore)
        } else {
            WalletAction(stringResource(R.string.sol_backup_row), modifier = Modifier.fillMaxWidth(), accent = true, onClick = onSave)
            WalletAction(stringResource(R.string.sol_restore), modifier = Modifier.fillMaxWidth(), onClick = onRestoreWords)
        }
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, hint: String, error: String?) {
    val c = LocalPhantomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PhantomRadius.control)
    val edge = when {
        error != null -> c.danger
        focused -> c.accentLine
        else -> c.lineStrong
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = PhantomText.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accentLine),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = hint
            if (error != null) error(error)
        },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomSize.input)
                    .clip(shape)
                    .background(c.surface)
                    .border(if (focused || error != null) 2.dp else 1.dp, edge, shape)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
            ) {
                if (value.isEmpty()) Text(text = hint, style = PhantomText.body, color = c.text3)
                inner()
            }
        },
    )
    if (error != null) Text(error, style = PhantomText.footnote, color = c.danger)
}
