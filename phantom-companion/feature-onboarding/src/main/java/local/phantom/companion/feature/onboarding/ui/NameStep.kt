package local.phantom.companion.feature.onboarding.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.core.net.peer.PeerName.CAP_QR_NAME
import local.phantom.companion.feature.onboarding.R

@Composable
fun NameStep(
    initialDisplayName: String = "",
    restoreAvailable: Boolean = false,
    onRestore: () -> Unit = {},
    onPhotoPicked: (Uri) -> Unit = {},
    initialPhoto: Bitmap? = null,
    onSubmit: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialDisplayName) }
    var refused by remember { mutableStateOf(false) }
    var photo by remember(initialPhoto) { mutableStateOf(initialPhoto) }
    val trimmed = name.trim()
    val used = PeerName.length(trimmed)
    val isTooLong = refused || used > CAP_QR_NAME
    val isValid = used <= CAP_QR_NAME

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onPhotoPicked(uri)
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { decodePassPhoto(context, uri) }
                if (decoded != null) photo = decoded
            }
        }
    }
    val images = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    val unnamed = stringResource(R.string.onb_name_unnamed)
    val c = LocalPhantomColors.current

    OnboardingScaffold(
        contentFillsHeight = false,
        actions = {
            OnboardingPrimaryButton(
                label = stringResource(R.string.onb_start),
                enabled = isValid,
                onClick = { onSubmit(trimmed) },
                modifier = Modifier
                    .testTag("onb-start")
                    .fillMaxWidth(),
            )
            if (restoreAvailable) {
                Spacer(modifier = Modifier.height(PhantomSpace.s2))
                OnboardingRestoreButton(
                    label = stringResource(R.string.onb_restore),
                    onClick = onRestore,
                    modifier = Modifier
                        .testTag("onb-restore")
                        .fillMaxWidth(),
                )
            }
        },
    ) {
        Spacer(modifier = Modifier.height(PhantomSpace.s4))
        Text(
            text = stringResource(R.string.onb_name_title),
            style = PhantomText.display,
            color = c.text,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(PhantomSpace.s6))

        PassCard(
            typedName = name,
            unnamed = unnamed,
            photo = photo,
            onPhotoClick = { photoPicker.launch(images) },
        )

        Spacer(modifier = Modifier.height(PhantomSpace.s6))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onb_name_field_label).asSentence(),
                style = PhantomText.footnote,
                fontWeight = FontWeight.SemiBold,
                color = c.text2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$used/${CAP_QR_NAME}",
                style = PhantomText.footnote.copy(fontFeatureSettings = "tnum"),
                color = if (isTooLong) c.danger else c.text2,
                modifier = Modifier.testTag("onb-name-counter"),
            )
        }

        Spacer(modifier = Modifier.height(PhantomSpace.s2))

        NameField(
            value = name,
            onValueChange = { input ->
                val fit = PeerName.fit(input, CAP_QR_NAME)
                refused = fit.refused
                name = fit.text
            },
            onDone = { if (isValid) onSubmit(trimmed) },
            error = isTooLong || refused,
        )

        if (isTooLong || refused) {
            Spacer(modifier = Modifier.height(PhantomSpace.s2))
            Text(
                text = stringResource(
                    R.string.onb_name_hint_long,
                    CAP_QR_NAME,
                ),
                style = PhantomText.footnote,
                color = c.danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun passCardName(typed: String, unnamed: String): String =
    typed.trim().ifEmpty { unnamed }

internal fun passInitials(typed: String): String {
    val parts = typed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
    if (parts.isEmpty()) return "N"
    return parts.joinToString("") { PeerName.graphemes(it).firstOrNull().orEmpty().uppercase() }
}

@Composable
private fun PassCard(
    typedName: String,
    unnamed: String,
    photo: Bitmap?,
    onPhotoClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val trimmed = typedName.trim()
    val cardName = passCardName(typedName, unnamed)
    val addPhotoLabel = stringResource(R.string.onb_photo_add)
    val shape = RoundedCornerShape(PhantomRadius.card)
    val tone = c.person[Math.floorMod(trimmed.hashCode(), c.person.size)]

    Row(
        modifier = Modifier
            .testTag("onb-pass-card")
            .fillMaxWidth()
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.line, shape)
            .padding(PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .testTag("onb-pass-photo")
                .size(PhantomSize.avatarL)
                .clip(CircleShape)
                .background(tone.first)
                .clickable(role = Role.Button, onClickLabel = addPhotoLabel, onClick = onPhotoClick)
                .semantics { contentDescription = addPhotoLabel },
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .testTag("onb-pass-photo-img")
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            } else {
                Text(
                    text = passInitials(trimmed),
                    style = PhantomText.title2,
                    color = tone.second,
                    modifier = Modifier.testTag("onb-pass-initials"),
                )
            }
        }
        Spacer(modifier = Modifier.width(PhantomSpace.s4))
        Text(
            text = cardName,
            style = PhantomText.title1,
            color = if (trimmed.isEmpty()) c.text2 else c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .testTag("onb-pass-name")
                .weight(1f),
        )
    }
}

@Composable
private fun OnboardingRestoreButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PhantomText.headline,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun decodePassPhoto(context: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val big = maxOf(info.size.width, info.size.height)
        if (big > 512) {
            val k = 512f / big
            decoder.setTargetSize(
                (info.size.width * k).toInt().coerceAtLeast(1),
                (info.size.height * k).toInt().coerceAtLeast(1),
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    error: Boolean,
) {
    val c = LocalPhantomColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PhantomRadius.control)
    val edge = when {
        error -> c.danger
        focused -> c.accentLine
        else -> c.lineStrong
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .testTag("onb-name-input")
            .fillMaxWidth(),
        textStyle = PhantomText.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accentLine),
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomSize.input)
                    .clip(shape)
                    .background(c.raised)
                    .border(if (focused || error) 2.dp else 1.dp, edge, shape)
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.onb_name_placeholder),
                        style = PhantomText.body,
                        color = c.text2,
                    )
                }
                inner()
            }
        },
    )
}
