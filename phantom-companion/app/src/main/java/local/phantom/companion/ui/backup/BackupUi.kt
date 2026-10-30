package local.phantom.companion.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.design.component.PrimaryButton
import local.phantom.companion.core.design.kit.SheetFrame
import local.phantom.companion.core.design.kit.StatusChip
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.data.backup.BackupFormat
import local.phantom.companion.data.backup.BackupResealWorker
import local.phantom.companion.data.backup.RestoreResult
import local.phantom.companion.data.backup.applyBackup
import local.phantom.companion.data.backup.sealBackup
import local.phantom.companion.data.backup.writeFirstBackup

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupLossList(modifier: Modifier = Modifier) {
    val items = listOf(
        stringResource(R.string.backup_loss_chats),
        stringResource(R.string.backup_loss_contacts),
        stringResource(R.string.backup_loss_profiles),
        stringResource(R.string.backup_loss_venues),
        stringResource(R.string.backup_loss_licences),
        stringResource(R.string.backup_loss_handle),
        stringResource(R.string.backup_loss_points),
        stringResource(R.string.backup_loss_wallet),
        stringResource(R.string.backup_loss_memory),
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        items.forEach { label ->
            StatusChip(label = label)
        }
    }
}

@Composable
fun BackupPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    error: String? = null,
    isVisible: Boolean = false,
    onToggleVisible: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PhantomRadius.control)
    val edge = when {
        error != null -> c.danger
        focused -> c.accentLine
        else -> c.lineStrong
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = PhantomText.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accentLine),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = hint
                    if (error != null) error(error)
                },
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PhantomSize.input)
                        .clip(shape)
                        .background(c.surface)
                        .border(if (focused || error != null) 2.dp else 1.dp, edge, shape)
                        .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(text = hint, style = PhantomText.body, color = c.text3)
                        }
                        inner()
                    }
                    if (onToggleVisible != null) {
                        Icon(
                            painter = painterResource(R.drawable.ph_eye),
                            contentDescription = stringResource(if (isVisible) R.string.backup_pass_hide else R.string.backup_pass_show),
                            tint = if (isVisible) c.accent else c.text3,
                            modifier = Modifier
                                .size(PhantomSize.iconS)
                                .clickable(role = Role.Button, onClick = onToggleVisible),
                        )
                    }
                }
            },
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(PhantomSpace.s1))
            Text(text = error, style = PhantomText.footnote, color = c.danger)
        }
    }
}

@Composable
fun BackupCreateSheet(
    onDismiss: () -> Unit,
    onBackupSaved: (dateFormatted: String) -> Unit,
) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val scope = rememberCoroutineScope()
    val c = LocalPhantomColors.current

    var pass by remember { mutableStateOf("") }
    var passConfirm by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    val isLengthOk = pass.length >= 12
    val isMatch = pass.isNotEmpty() && pass == passConfirm
    val isValid = isLengthOk && isMatch && !isSaving

    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isSaving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                val saved = writeFirstBackup(
                    open = { context.contentResolver.openOutputStream(uri, "wt") },
                    seal = { container.sealBackup(it, pass.toCharArray()) },
                    discard = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
                )
                if (saved) {
                    container.backupKeys.setBackupUri(uri.toString())
                    container.backupKeys.setLastBackupEpochMs(System.currentTimeMillis())
                    BackupResealWorker.schedule(context)
                }
                saved
            }
            isSaving = false
            saveFailed = !ok
            if (ok) {
                val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                onBackupSaved(df.format(java.util.Date()))
                onDismiss()
            }
        }
    }

    SheetFrame(
        title = stringResource(R.string.backup_sheet_title),
        closeLabel = stringResource(R.string.circle_close),
        onClose = onDismiss,
        secure = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        ) {
            Text(
                text = stringResource(R.string.backup_loss_title),
                style = PhantomText.callout,
                color = c.text,
            )
            Spacer(modifier = Modifier.height(PhantomSpace.s2))
            BackupLossList()

            Spacer(modifier = Modifier.height(PhantomSpace.s4))

            BackupPasswordField(
                value = pass,
                onValueChange = { pass = it },
                hint = stringResource(R.string.backup_pass_hint),
                error = if (pass.isNotEmpty() && !isLengthOk) stringResource(R.string.backup_pass_too_short) else null,
                isVisible = showPass,
                onToggleVisible = { showPass = !showPass },
            )

            Spacer(modifier = Modifier.height(PhantomSpace.s3))

            BackupPasswordField(
                value = passConfirm,
                onValueChange = { passConfirm = it },
                hint = stringResource(R.string.backup_pass_confirm_hint),
                error = if (passConfirm.isNotEmpty() && !isMatch) stringResource(R.string.backup_pass_mismatch) else null,
                isVisible = showPass,
                onToggleVisible = { showPass = !showPass },
            )

            Spacer(modifier = Modifier.height(PhantomSpace.s5))

            PrimaryButton(
                label = stringResource(R.string.backup_save_action),
                enabled = isValid,
                onClick = {
                    saveFailed = false
                    createLauncher.launch("nightpost.backup")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup-save-button"),
            )
            if (saveFailed) {
                Spacer(modifier = Modifier.height(PhantomSpace.s2))
                Text(text = stringResource(R.string.backup_create_err), style = PhantomText.footnote, color = c.danger)
            }
            Spacer(modifier = Modifier.height(PhantomSpace.s3))
        }
    }
}

private sealed class RestoreOutcome {
    object WrongPass : RestoreOutcome()
    object NotOurFile : RestoreOutcome()
    object NotEmpty : RestoreOutcome()
    object Unreadable : RestoreOutcome()
    data class Error(val reason: RestoreResult.Reason) : RestoreOutcome()
    data class Success(val result: RestoreResult.Success) : RestoreOutcome()
}

@Composable
fun BackupRestoreSheet(
    uri: Uri,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { PhantomApp.container() }
    val scope = rememberCoroutineScope()
    val c = LocalPhantomColors.current

    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    SheetFrame(
        title = stringResource(R.string.backup_restore_title),
        closeLabel = stringResource(R.string.circle_close),
        onClose = onDismiss,
        secure = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
        ) {
            BackupPasswordField(
                value = pass,
                onValueChange = {
                    pass = it
                    errorText = null
                },
                hint = stringResource(R.string.backup_restore_pass_hint),
                error = errorText,
                isVisible = showPass,
                onToggleVisible = { showPass = !showPass },
            )

            Spacer(modifier = Modifier.height(PhantomSpace.s5))

            PrimaryButton(
                label = stringResource(R.string.backup_restore_action),
                enabled = pass.isNotEmpty() && !isRestoring,
                onClick = {
                    scope.launch {
                        isRestoring = true
                        errorText = null
                        val outcome = withContext(Dispatchers.IO) {
                            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                                ?: return@withContext RestoreOutcome.Unreadable
                            BackupFormat.readHeader(ByteArrayInputStream(bytes)) ?: return@withContext RestoreOutcome.NotOurFile
                            val opened = BackupFormat.openForRestore(bytes, pass.toCharArray())
                                ?: return@withContext RestoreOutcome.WrongPass
                            // Без права писати тижневе оновлення не відбудеться — тоді копію не обіцяємо.
                            val writable = runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }.isSuccess
                            when (val res = container.applyBackup(opened, uri.toString().takeIf { writable })) {
                                is RestoreResult.NotEmpty -> RestoreOutcome.NotEmpty
                                is RestoreResult.Error -> RestoreOutcome.Error(res.reason)
                                is RestoreResult.Success -> {
                                    if (writable) BackupResealWorker.schedule(context)
                                    RestoreOutcome.Success(res)
                                }
                            }
                        }
                        isRestoring = false
                        when (outcome) {
                            is RestoreOutcome.WrongPass -> errorText = context.getString(R.string.backup_restore_err_wrong_pass)
                            is RestoreOutcome.NotOurFile -> errorText = context.getString(R.string.backup_restore_err_not_our_file)
                            is RestoreOutcome.NotEmpty -> errorText = context.getString(R.string.backup_restore_err_not_empty)
                            is RestoreOutcome.Unreadable -> errorText = context.getString(R.string.backup_restore_err_read)
                            is RestoreOutcome.Error -> errorText = context.getString(
                                when (outcome.reason) {
                                    RestoreResult.Reason.INVALID -> R.string.backup_restore_err_invalid
                                    RestoreResult.Reason.WRITE -> R.string.backup_restore_err_failed
                                },
                            )
                            is RestoreOutcome.Success -> onSuccess()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup-restore-button"),
            )
            Spacer(modifier = Modifier.height(PhantomSpace.s3))
        }
    }
}
