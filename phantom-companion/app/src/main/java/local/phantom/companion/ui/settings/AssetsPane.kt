package local.phantom.companion.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.R
import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.isFetchable
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * What PHANTOM has downloaded, grouped by the faculty each file gives him
 * rather than by the file. The operator is never asked to pick a model —
 * PHANTOM chooses and fetches; this is the ledger of what that produced.
 */
@Composable
internal fun AssetsBody(
    modelStates: Map<ModelType, ModelState>,
    onDownloadModel: (ModelType) -> Unit,
    onDeleteModel: (ModelType) -> Unit,
    onImportModel: (ModelType, Uri) -> Unit,
) {
    val context = LocalContext.current
    var pendingImport by remember { mutableStateOf<ModelType?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            val target = pendingImport
            if (uri != null && target != null) onImportModel(target, uri)
            pendingImport = null
        },
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        ModelType.values().groupBy(::modelFacultyRes).forEach { (facultyRes, types) ->
            SettingsKicker(label = stringResource(facultyRes))
            Spacer(modifier = Modifier.height(6.dp))
            types.forEach { type ->
                AssetRow(
                    state = modelStates[type] ?: ModelState(type),
                    onDownload = { onDownloadModel(type) },
                    onDelete = { onDeleteModel(type) },
                    onImport = {
                        pendingImport = type
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    onInfo = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(type.infoUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AssetRow(
    state: ModelState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
    onInfo: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val unwired = !state.type.wired
    val statusTone: Color = when {
        state.isPresent -> theme.success
        state.isDownloading -> theme.accentSharp
        else -> theme.inkSecondary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                PhantomBody(
                    text = state.type.displayName,
                    color = theme.ink,
                    fontWeight = FontWeight.Medium,
                )
                PhantomCaption(
                    text = assetStatusLine(state, unwired),
                    color = if (unwired) theme.inkSecondary else statusTone,
                )
            }
            if (!state.isDownloading) {
                Row {
                    if (state.isPresent) {
                        AssetAction(Icons.Outlined.Delete, R.string.settings_asset_drop, theme.coral, onDelete)
                    }
                    if (state.type.isFetchable && !state.isPresent && !unwired) {
                        AssetAction(
                            Icons.Outlined.Download, R.string.settings_asset_get,
                            theme.accentSharp, onDownload,
                        )
                    }
                    AssetAction(
                        Icons.Outlined.UploadFile, R.string.settings_asset_import,
                        theme.accentSharp, onImport,
                    )
                    AssetAction(
                        Icons.Outlined.Info, R.string.settings_asset_about,
                        theme.inkSecondary, onInfo,
                    )
                }
            }
        }

        if (state.isDownloading) {
            Spacer(modifier = Modifier.height(6.dp))
            val animated by animateFloatAsState(
                targetValue = state.progress,
                animationSpec = local.phantom.companion.core.design.motion.PhantomMotion.meter,
                label = "asset-progress",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = theme.accentSharp,
                trackColor = theme.glassPanel,
            )
        }
        state.error?.let { msg ->
            Spacer(modifier = Modifier.height(4.dp))
            PhantomCaption(text = msg, color = theme.coral)
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun AssetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    @StringRes label: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(PhantomTouchFloor)) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun assetStatusLine(state: ModelState, unwired: Boolean): String = when {
    unwired -> state.type.unwiredNote ?: stringResource(R.string.settings_asset_unwired)
    state.isPresent -> stringResource(R.string.settings_asset_ready)
    state.isDownloading ->
        stringResource(R.string.settings_asset_fetching, (state.progress * 100).toInt())
    else -> state.type.downloadBytes
        ?.let { stringResource(R.string.settings_asset_absent_sized, humanBytes(it)) }
        ?: stringResource(R.string.settings_asset_absent)
}

/**
 * Десятковими, як їх рахують сховища й самі люди, а не 2^20.
 *
 * Одиниця — з ресурсів, і не лише заради сторожа кириличних літералів:
 * тут був ще й `Locale.US`, тобто крапка в десятковій дробі для людини, яка
 * всюди бачить кому. Формат із ресурсу бере роздільник із локалі пристрою.
 */
@Composable
private fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L ->
        stringResource(R.string.settings_size_gb, bytes / 1_000_000_000.0f)
    else ->
        stringResource(R.string.settings_size_mb, ((bytes + 500_000L) / 1_000_000L).toInt())
}

/** Which faculty a model file gives PHANTOM — the only part of it the operator cares about. */
@StringRes
internal fun modelFacultyRes(type: ModelType): Int = when (type) {
    ModelType.VOSK, ModelType.MOONSHINE_UK -> R.string.model_role_hearing
    ModelType.SUPERTONIC, ModelType.SUPERTONIC_LITE -> R.string.model_role_voice
    ModelType.YAMNET -> R.string.model_role_sounds
    ModelType.EMBEDDING -> R.string.model_role_memory
    ModelType.EMBEDDING_MULTILINGUAL -> R.string.model_role_meaning
}

/**
 * SV-1 — VoiceSettingsScreen renders STT/TTS models with this row rather
 * than forking a copy of it. Назва — що модель дає людині ([modelLabelRes]),
 * а не імʼя рушія; дії — чорнилом другого рівня, бурштин на екрані один.
 */
@Composable
internal fun ModelRow(
    state: ModelState,
    hasDownloadUrl: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
    onInfo: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val unwired = !state.type.wired
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(modelLabelRes(state.type)), style = PhantomText.body, color = c.text)
                Text(
                    text = assetStatusLine(state, unwired),
                    style = PhantomText.footnote,
                    color = if (state.isPresent && !unwired) c.success else c.text3,
                )
            }
            if (!state.isDownloading) {
                Row {
                    if (state.isPresent) {
                        AssetAction(Icons.Outlined.Delete, R.string.settings_asset_drop, c.danger, onDelete)
                    }
                    AssetAction(Icons.Outlined.Info, R.string.settings_asset_about, c.text2, onInfo)
                    if (hasDownloadUrl && state.type.wired) {
                        AssetAction(Icons.Outlined.Download, R.string.settings_asset_get, c.text2, onDownload)
                    }
                    AssetAction(Icons.Outlined.UploadFile, R.string.settings_asset_import, c.text2, onImport)
                }
            }
        }

        if (state.isDownloading) {
            Spacer(modifier = Modifier.height(PhantomSpace.s2))
            val animated by animateFloatAsState(
                targetValue = state.progress,
                animationSpec = local.phantom.companion.core.design.motion.PhantomMotion.meter,
                label = "model-progress",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(PhantomSpace.s1),
                color = c.accent,
                trackColor = c.overlay,
            )
        }
        state.error?.let { msg ->
            Spacer(modifier = Modifier.height(PhantomSpace.s1))
            Text(text = msg, style = PhantomText.footnote, color = c.danger)
        }
    }
}

/** Людська назва моделі на екрані «Голос»; решта моделей зветься тим, що дає. */
@StringRes
internal fun modelLabelRes(type: ModelType): Int = when (type) {
    ModelType.VOSK -> R.string.model_label_hearing_full
    ModelType.MOONSHINE_UK -> R.string.model_label_hearing_light
    ModelType.SUPERTONIC -> R.string.model_label_voice
    ModelType.SUPERTONIC_LITE -> R.string.model_label_voice_lite
    else -> modelFacultyRes(type)
}
