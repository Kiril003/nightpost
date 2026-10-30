package local.phantom.companion.ui.home

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.component.PhantomSlider
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.withAccent
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomAccents
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.data.appearance.Appearance
import local.phantom.companion.data.appearance.AppearancePhotos
import local.phantom.companion.data.appearance.AppearanceStore
import local.phantom.companion.data.appearance.Wallpaper

private val ACCENT_NAMES = mapOf(
    "amber" to R.string.appearance_accent_amber,
    "sage" to R.string.appearance_accent_sage,
    "sea" to R.string.appearance_accent_sea,
    "iris" to R.string.appearance_accent_iris,
    "rose" to R.string.appearance_accent_rose,
    "graphite" to R.string.appearance_accent_graphite,
)

private val WALLPAPER_NAMES = mapOf(
    "contour" to R.string.appearance_wp_contour,
    "sand" to R.string.appearance_wp_sand,
    "sage" to R.string.appearance_wp_sage,
    "slate" to R.string.appearance_wp_slate,
    "clay" to R.string.appearance_wp_clay,
    "dusk" to R.string.appearance_wp_dusk,
    "stone" to R.string.appearance_wp_stone,
)

/** Вбудовані текстури їдуть у бандлі месенджера (`public/wallpapers`), тож рідний бік бере ті самі файли. */
internal fun builtinAsset(id: String) = "webmsgr/wallpapers/$id.webp"

@Composable
private fun rememberWallpaper(store: AppearanceStore, w: Wallpaper): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, w) {
        value = withContext(Dispatchers.IO) { loadWallpaper(context, store, w) }
    }.value
}

private fun loadWallpaper(context: Context, store: AppearanceStore, w: Wallpaper): ImageBitmap? = runCatching {
    val id = w.id
    val file = w.file?.let { store.file(it) }
    when {
        w.kind == Wallpaper.BUILTIN && id != null -> context.assets.open(builtinAsset(id)).use { BitmapFactory.decodeStream(it) }
        w.kind == Wallpaper.PHOTO && file != null -> BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        else -> null
    }?.asImageBitmap()
}.getOrNull()

/** Фокус екрана: розмова в обраних кольорах, із тлом, приглушенням і розмиттям для [dark]. */
@Composable
internal fun LookPreview(store: AppearanceStore, look: Appearance, dark: Boolean) {
    val sun = LocalPhantomTheme.current.palette?.takeIf { it.isDark == dark }
    val c = (sun ?: if (dark) NightColors else DayColors).withAccent(look.accent)
    val image = rememberWallpaper(store, look.wallpaper)
    val shape = RoundedCornerShape(PhantomRadius.card)
    val pad = if (look.bubbles == Appearance.COMPACT) PhantomSpace.s2 to 5.dp else PhantomSpace.s3 to PhantomSpace.s2
    Box(
        Modifier
            .fillMaxWidth()
            .padding(PhantomSpace.s3)
            .height(196.dp)
            .clip(shape)
            .background(c.canvas)
            .border(1.dp, LocalPhantomColors.current.line, shape)
            .semantics { contentDescription = "appearance-preview" },
    ) {
        if (image != null) {
            val blur = look.blur.of(dark).dp
            if (look.wallpaper.kind == Wallpaper.BUILTIN) {
                val brush = remember(image) { ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)) }
                Box(Modifier.fillMaxSize().blur(blur).background(brush))
            } else {
                Image(image, null, Modifier.fillMaxSize().blur(blur), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(c.canvas.copy(alpha = Appearance.scrim(look.dim.of(dark), look.wallpaper.kind))))
        }
        Column(
            Modifier.fillMaxSize().padding(PhantomSpace.s3),
            verticalArrangement = Arrangement.spacedBy(if (look.bubbles == Appearance.COMPACT) PhantomSpace.s1 else PhantomSpace.s2, Alignment.Bottom),
        ) {
            PreviewBubble(stringResource(R.string.appearance_preview_them), c.surface, c.text, false, pad)
            PreviewBubble(stringResource(R.string.appearance_preview_me), c.self, c.onSelf, true, pad)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(36.dp).clip(CircleShape).background(c.surface))
                Spacer(Modifier.size(PhantomSpace.s2))
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent))
            }
        }
    }
}

@Composable
private fun PreviewBubble(text: String, fill: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color, self: Boolean, pad: Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (self) Arrangement.End else Arrangement.Start) {
        Text(
            text = text,
            style = PhantomText.body,
            color = ink,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(PhantomRadius.bubble))
                .background(fill)
                .padding(horizontal = pad.first, vertical = pad.second),
        )
    }
}

@Composable
internal fun AccentSwatches(selected: String, dark: Boolean, onSelect: (String) -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        Modifier.fillMaxWidth().padding(PhantomSpace.s3).selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PhantomAccents.forEach { accent ->
            val on = accent.id == selected
            val tone = if (dark) accent.night else accent.day
            val name = stringResource(ACCENT_NAMES.getValue(accent.id))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(accent.id) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(if (on) 2.dp else 0.dp, if (on) c.text else tone.accent, CircleShape)
                        .padding(if (on) 4.dp else 0.dp)
                        .clip(CircleShape)
                        .background(tone.accent),
                )
            }
        }
    }
}

/** Загальне тло: без тла, шість спокійних текстур, своє фото з галереї (системний вибір, без дозволу на памʼять). */
@Composable
internal fun WallpaperChoices(store: AppearanceStore, look: Appearance) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val set: (Wallpaper) -> Unit = { w -> scope.launch(Dispatchers.IO) { store.update { it.copy(wallpaper = w, wallpaperPicked = true) } } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val jpeg = bytes?.let { AppearancePhotos.jpeg(it) } ?: return@withContext false
                store.putPhoto(AppearanceStore.Target.Everywhere, jpeg)
                true
            }
            failed = !ok
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(PhantomSpace.s3).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        WallpaperTile(stringResource(R.string.appearance_wallpaper_none), look.wallpaper.kind == Wallpaper.NONE, { set(Wallpaper.None) }) {
            Box(Modifier.fillMaxSize().background(c.canvas))
        }
        Wallpaper.BUILTINS.forEach { id ->
            val w = Wallpaper.builtin(id)
            WallpaperTile(stringResource(WALLPAPER_NAMES.getValue(id)), look.wallpaper == w, { set(w) }) {
                val image = rememberWallpaper(store, w)
                if (image != null) {
                    val brush = remember(image) { ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)) }
                    Box(Modifier.fillMaxSize().background(brush))
                } else {
                    Box(Modifier.fillMaxSize().background(c.overlay))
                }
            }
        }
        WallpaperTile(
            stringResource(R.string.appearance_wallpaper_gallery),
            look.wallpaper.kind == Wallpaper.PHOTO,
            { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) {
            val image = if (look.wallpaper.kind == Wallpaper.PHOTO) rememberWallpaper(store, look.wallpaper) else null
            if (image != null) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(c.overlay), contentAlignment = Alignment.Center) {
                    Icon(painterResource(DesignR.drawable.lc_appearance), null, tint = c.text2, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
    if (failed) Explainer(stringResource(R.string.appearance_photo_failed))
}

@Composable
private fun WallpaperTile(label: String, on: Boolean, onClick: () -> Unit, fill: @Composable () -> Unit) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.control)
    Column(
        Modifier
            .widthIn(min = 54.dp)
            .clip(shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(bottom = PhantomSpace.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 54.dp, height = 76.dp).clip(shape).border(if (on) 2.dp else 1.dp, if (on) c.accentLine else c.line, shape)) {
            fill()
        }
        Spacer(Modifier.height(PhantomSpace.s1))
        Text(label, style = PhantomText.caption, color = if (on) c.text else c.text2, maxLines = 1, softWrap = false)
    }
}

/** Приглушення й розмиття тла — окремо для Дня й Ночі; пишемо, коли палець відпустив повзунок. */
@Composable
internal fun ShadeControls(store: AppearanceStore, look: Appearance, dark: Boolean, onDark: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    Segmented(
        options = listOf(false to stringResource(R.string.appearance_day), true to stringResource(R.string.appearance_night)),
        selected = dark,
        onSelect = onDark,
    )
    ShadeSlider(stringResource(R.string.appearance_dim), look.dim.of(dark), Appearance.DIM_MAX, "$dark-dim") { v ->
        scope.launch(Dispatchers.IO) { store.update { it.copy(dim = it.dim.with(dark, v)) } }
    }
    ShadeSlider(stringResource(R.string.appearance_blur), look.blur.of(dark), Appearance.BLUR_MAX, "$dark-blur") { v ->
        scope.launch(Dispatchers.IO) { store.update { it.copy(blur = it.blur.with(dark, v)) } }
    }
}

@Composable
private fun ShadeSlider(label: String, value: Int, max: Int, key: String, onDone: (Int) -> Unit) {
    val c = LocalPhantomColors.current
    var draft by remember(key, value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s1)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = PhantomText.body, color = c.text)
            Spacer(Modifier.weight(1f))
            Text("${draft.toInt()}", style = PhantomText.body, color = c.text2)
        }
        PhantomSlider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onDone(draft.toInt()) },
            valueRange = 0f..max.toFloat(),
        )
    }
}

@Composable
internal fun DensityChoice(selected: String, onSelect: (String) -> Unit) = Segmented(
    options = listOf(
        Appearance.COMFORTABLE to stringResource(R.string.appearance_comfortable),
        Appearance.COMPACT to stringResource(R.string.appearance_compact),
    ),
    selected = selected,
    onSelect = onSelect,
)
