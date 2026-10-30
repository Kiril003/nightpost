package local.phantom.companion.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.component.fillDialogRoom
import local.phantom.companion.core.design.component.rememberDialogRoom
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.motion.MotionAccess
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerProfile

/** Одне фото банера: байти, що їдуть людям, і їхній же малюнок для екрана. */
internal class BannerShot(val bytes: ByteArray, val bitmap: Bitmap, val fx: Int = 128, val fy: Int = 128) {
    val hash: String get() = PeerBanner.hashOf(bytes)
    fun at(x: Int, y: Int) = BannerShot(bytes, bitmap, x.coerceIn(0, 255), y.coerceIn(0, 255))
}

internal val BannerHeight = 210.dp
private const val BANNER_ASPECT = 390f / 210f
internal const val BANNER_VIDEO_MAX_MS = 15_000L
private const val BANNER_VIDEO_MAX_BYTES = 25L * 1024 * 1024

/** Стиснення фото банера: WebP до [PeerBanner.MAX_BYTES], довша сторона до 800 px. */
internal object BannerCompress {
    const val SIDE = 800
    private const val MIN_SIDE = 320
    private val QUALITIES = intArrayOf(80, 70, 60, 50, 40)

    fun fit(w: Int, h: Int, side: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= side) return w to h
        val k = side.toFloat() / long
        return maxOf(1, (w * k).roundToInt()) to maxOf(1, (h * k).roundToInt())
    }

    /** Null — не влізло навіть на найменшій стороні. */
    fun encode(src: Bitmap, cap: Int = PeerBanner.MAX_BYTES): ByteArray? {
        var side = SIDE
        while (side >= MIN_SIDE) {
            val (w, h) = fit(src.width, src.height, side)
            val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
            for (q in QUALITIES) {
                val out = ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                if (bmp.compress(format, q, out) && out.size() in 1..cap) {
                    val bytes = out.toByteArray()
                    if (PeerProfile.imageKind(bytes) != null) return bytes
                }
            }
            side = side * 3 / 4
        }
        return null
    }

    fun shot(src: Bitmap): BannerShot? {
        val bytes = encode(src) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return BannerShot(bytes, bmp)
    }
}

/**
 * Геометрія кадрування: фокус 0..255 так само, як [BiasAlignment] у
 * `ContentScale.Crop` банера — рамка в редакторі й банер на сторінці
 * показують ту саму частину фото.
 */
internal object BannerFocus {
    fun bias(f: Int): Float = f.coerceIn(0, 255) / 255f * 2f - 1f

    fun alignment(fx: Int, fy: Int): Alignment = BiasAlignment(bias(fx), bias(fy))

    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    /** Де лежить фото, вписане в [w]×[h]. */
    fun fitted(w: Float, h: Float, bw: Int, bh: Int): Box {
        val s = minOf(w / bw, h / bh)
        val dw = bw * s
        val dh = bh * s
        return Box((w - dw) / 2f, (h - dh) / 2f, dw, dh)
    }

    /** Рамка банера всередині вписаного фото. */
    fun frame(img: Box, fx: Int, fy: Int, aspect: Float = BANNER_ASPECT): Box {
        val fw: Float
        val fh: Float
        if (img.width / img.height > aspect) {
            fh = img.height
            fw = img.height * aspect
        } else {
            fw = img.width
            fh = img.width / aspect
        }
        return Box(img.left + (img.width - fw) * fx.coerceIn(0, 255) / 255f, img.top + (img.height - fh) * fy.coerceIn(0, 255) / 255f, fw, fh)
    }

    /** Палець у точці [p] — новий фокус: центр рамки йде за пальцем. */
    fun fromTouch(img: Box, p: Offset, aspect: Float = BANNER_ASPECT): Pair<Int, Int> {
        val f = frame(img, 0, 0, aspect)
        fun axis(pos: Float, start: Float, span: Float, size: Float): Int =
            if (span - size <= 0.5f) 128 else (((pos - start - size / 2f) / (span - size)).coerceIn(0f, 1f) * 255f).roundToInt()
        return axis(p.x, img.left, img.width, f.width) to axis(p.y, img.top, img.height, f.height)
    }

    /** Масштаб TextureView, щоб відео заповнило банер без полів (crop). */
    fun cropScale(w: Float, h: Float, vw: Int, vh: Int): Pair<Float, Float> {
        if (w <= 0f || h <= 0f || vw <= 0 || vh <= 0) return 1f to 1f
        val k = maxOf(w / vw, h / vh)
        return (vw * k / w) to (vh * k / h)
    }
}

/** Свій банер на сторінці «Я»: гортається пальцем, сам не крутиться. */
@Composable
internal fun OwnBanner(
    shots: List<BannerShot>,
    video: File?,
    videoMs: Long,
    onOpen: () -> Unit,
    shared: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val pager = rememberPagerState { shots.size }
    Box(
        Modifier
            .testTag("you-banner")
            .fillMaxWidth()
            .height(BannerHeight)
            .background(c.raised),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { shots[it].hash }) { i ->
            val s = shots[i]
            Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.profile_banner_photo_n, i + 1),
                contentScale = ContentScale.Crop,
                alignment = BannerFocus.alignment(s.fx, s.fy),
                modifier = Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onOpen),
            )
        }
        if (video != null) {
            val still = shots.firstOrNull()
            // Новий кліп лягає в той самий файл: без ключа плеєр крутив би старий.
            key(video.path, video.lastModified()) { BannerVideo(video, still?.fx ?: 128, still?.fy ?: 128, Modifier.fillMaxSize()) }
            val label = stringResource(R.string.profile_banner_photo_n, 1)
            Box(Modifier.fillMaxSize().semantics { contentDescription = label }.clickable(role = Role.Button, onClick = onOpen))
        }
        overlay()
        if (video != null) {
            OnPhotoPill(Modifier.align(Alignment.BottomStart).padding(PhantomSpace.s3)) {
                Icon(painterResource(R.drawable.ph_video), null, tint = c.onAct, modifier = Modifier.size(PhantomSize.iconS))
                if (videoMs > 0) Text("0:%02d".format((videoMs + 500) / 1000), style = PhantomText.footnote, color = c.onAct)
            }
            if (!shared) {
                OnPhotoPill(Modifier.align(Alignment.BottomEnd).padding(PhantomSpace.s3).testTag("you-banner-first-frame")) {
                    Icon(painterResource(R.drawable.ph_eye), null, tint = c.onAct, modifier = Modifier.size(PhantomSize.iconS))
                    Text(stringResource(R.string.profile_banner_first_frame), style = PhantomText.footnote, color = c.onAct, maxLines = 1)
                }
            }
        } else if (shots.size > 1) {
            BannerDots(shots.size, pager.currentPage, Modifier.align(Alignment.BottomEnd).padding(PhantomSpace.s3))
        }
    }
}

@Composable
private fun OnPhotoPill(modifier: Modifier, content: @Composable () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(c.scrim)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Крапки внизу справа: активна ширшає з 6 до 16 dp. */
@Composable
internal fun BannerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .testTag("you-banner-dots")
            .clip(CircleShape)
            .background(c.scrim)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val w by animateFloatAsState(if (i == current) 16f else 6f, PhantomMotion.stateChange, label = "dot")
            Box(
                Modifier
                    .width(w.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .graphicsLayer { alpha = if (i == current) 1f else 0.85f }
                    .background(c.onAct),
            )
        }
    }
}

/**
 * Петля відео лише на своїй сторінці. Під нею вже лежить перший кадр, тож
 * відео проявляється за 300 мс без стрибка. Без руху й в економії батареї
 * не грає, у фоні — на паузі.
 */
@Composable
private fun BannerVideo(file: File, fx: Int, fy: Int, modifier: Modifier) {
    val context = LocalContext.current
    val reduced = MotionAccess.reduced(LocalMotionScale.current)
    val saver = remember { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true }
    if (reduced || saver) return
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, MotionTokens.enterQuart, label = "video")
    val holder = remember { arrayOfNulls<MediaPlayer>(1) }
    val surface = remember { arrayOfNulls<Surface>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> runCatching { holder[0]?.pause() }
                Lifecycle.Event.ON_RESUME -> runCatching { holder[0]?.start() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            runCatching { holder[0]?.release() }
            holder[0] = null
            surface[0]?.release()
            surface[0] = null
        }
    }
    AndroidView(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val view = this@apply
                        holder[0] = runCatching {
                            MediaPlayer().apply {
                                setDataSource(file.path)
                                setSurface(Surface(st).also { surface[0] = it })
                                isLooping = true
                                setVolume(0f, 0f)
                                setOnVideoSizeChangedListener { _, vw, vh ->
                                    val (sx, sy) = BannerFocus.cropScale(view.width.toFloat(), view.height.toFloat(), vw, vh)
                                    view.setTransform(Matrix().apply { setScale(sx, sy, view.width * fx / 255f, view.height * fy / 255f) })
                                }
                                setOnInfoListener { _, what, _ ->
                                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) view.postDelayed({ shown = true }, 300)
                                    false
                                }
                                setOnErrorListener { _, _, _ -> shown = false; true }
                                setOnPreparedListener { it.start() }
                                prepareAsync()
                            }
                        }.getOrNull()
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { holder[0]?.release() }
                        holder[0] = null
                        surface[0]?.release()
                        surface[0] = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

/** Перший кадр і тривалість відео; null — не відео, довше [BANNER_VIDEO_MAX_MS] чи не читається. */
/** Петля до 8 МБ їде людям на прохання; більша лишається на своїй сторінці, людям — перший кадр. */
internal fun bannerVideoTravels(bytes: Long): Boolean = bytes in 1..PeerProfile.MAX_BANNER_VIDEO_BYTES.toLong()

internal class PickedVideo(val file: File, val still: BannerShot, val ms: Long)

internal fun videoMs(file: File): Long = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            release()
        }
    }
}.getOrDefault(0L)

private sealed interface VideoPick {
    data class Ok(val v: PickedVideo) : VideoPick
    data object TooLong : VideoPick
    data object Failed : VideoPick
}

private fun pickVideo(context: Context, uri: Uri): VideoPick {
    val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    if (size > BANNER_VIDEO_MAX_BYTES) return VideoPick.TooLong
    val dst = File(context.cacheDir, "banner-pick-${System.nanoTime()}")
    val copied = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } } != null
    }.getOrDefault(false)
    if (!copied) return VideoPick.Failed
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(dst.path)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (ms > BANNER_VIDEO_MAX_MS) {
            dst.delete()
            VideoPick.TooLong
        } else {
            val frame = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val still = frame?.let { BannerCompress.shot(it) }
            if (still == null) {
                dst.delete()
                VideoPick.Failed
            } else {
                VideoPick.Ok(PickedVideo(dst, still, ms))
            }
        }
    } catch (_: Exception) {
        dst.delete()
        VideoPick.Failed
    } finally {
        runCatching { r.release() }
    }
}

private fun pickPhoto(context: Context, uri: Uri): BannerShot? = runCatching {
    val src = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        val (w, h) = BannerCompress.fit(info.size.width, info.size.height, BannerCompress.SIDE)
        d.setTargetSize(w, h)
        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
    }
    BannerCompress.shot(src)
}.getOrNull()

/**
 * Редактор банера: до шести фото з фокусом і порядком, або одне відео.
 * Піднімається знизу за 300 мс; «Зберегти» — єдина бурштинова дія.
 */
@Composable
internal fun BannerEditor(
    initial: List<BannerShot>,
    initialVideo: PickedVideo?,
    saving: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<BannerShot>, PickedVideo?) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onDismiss)
        val cutout = editorWindowFromCorner()
        Box(Modifier.fillDialogRoom()) {
            Box(Modifier.padding(start = cutout.first, end = cutout.second)) {
                BannerEditorBody(initial, initialVideo, saving, failed, onDismiss, onSave)
            }
        }
    }
}

/**
 * Вікно редактора від лівого верхнього кута екрана, через виріз камери. Інакше в
 * горизонталі система зсуває його вбік від вирізу, а Compose міряє на всю ширину:
 * правий край і «Зберегти» опинялися за екраном (T71). Повертає відступи від вирізу.
 */
@Composable
private fun editorWindowFromCorner(): Pair<Dp, Dp> {
    val view = LocalView.current
    val density = LocalDensity.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val a = window.attributes
        val cut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (a.gravity != Gravity.TOP or Gravity.START || a.layoutInDisplayCutoutMode != cut) {
            a.gravity = Gravity.TOP or Gravity.START
            a.layoutInDisplayCutoutMode = cut
            window.attributes = a
        }
    }
    val room = rememberDialogRoom()
    val safe = view.rootWindowInsets?.displayCutout
    val left = (safe?.safeInsetLeft ?: 0) - (room?.left ?: 0)
    val right = (safe?.safeInsetRight ?: 0) - (room?.let { view.rootView.width - it.left - it.width } ?: 0)
    return with(density) { left.coerceAtLeast(0).toDp() to right.coerceAtLeast(0).toDp() }
}

/** Тіло редактора без вікна: його ж знімає тест кадрів. */
@Composable
internal fun BannerEditorBody(
    initial: List<BannerShot>,
    initialVideo: PickedVideo?,
    saving: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<BannerShot>, PickedVideo?) -> Unit,
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = MotionAccess.reduced(LocalMotionScale.current)
    val shots = remember { mutableStateListOf<BannerShot>().apply { addAll(if (initialVideo != null) emptyList() else initial) } }
    var video by remember { mutableStateOf(initialVideo) }
    var videoMode by remember { mutableStateOf(initialVideo != null) }
    var sel by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fullFlash by remember { mutableStateOf(false) }
    val rise = remember { Animatable(if (reduced) 0f else 1f) }
    LaunchedEffect(Unit) { if (reduced) rise.snapTo(0f) else rise.animateTo(0f, MotionTokens.expandQuart) }
    LaunchedEffect(fullFlash) { if (fullFlash) { delay(600); fullFlash = false } }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PeerProfile.MAX_BANNER_ITEMS)) { uris ->
        val room = PeerProfile.MAX_BANNER_ITEMS - shots.size
        if (uris.isEmpty() || room <= 0) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val got = withContext(Dispatchers.IO) { uris.take(room).mapNotNull { pickPhoto(context, it) } }
            note = if (got.size < uris.take(room).size) R.string.profile_photo_failed else null
            got.forEach { s -> if (shots.none { it.hash == s.hash }) shots.add(s) }
            if (shots.isNotEmpty()) sel = sel.coerceIn(0, shots.size - 1)
            busy = false
        }
    }
    val clips = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { pickVideo(context, uri) }) {
                is VideoPick.Ok -> {
                    video = r.v
                    note = null
                }
                VideoPick.TooLong -> note = R.string.profile_banner_video_long
                VideoPick.Failed -> note = R.string.profile_photo_failed
            }
            busy = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        // T52: кадр бере вільну висоту, а не 240 dp з порожнім пів екрана під ним.
        val cropH = (maxHeight * 0.5f).coerceIn(160.dp, 440.dp)
        Column(
            Modifier
                .testTag("banner-editor")
                .fillMaxSize()
                .graphicsLayer { translationY = rise.value * h }
                .background(c.canvas),
        ) {
            Row(
                Modifier.fillMaxWidth().height(PhantomSize.topbarPhone).padding(horizontal = PhantomSpace.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val close = stringResource(R.string.profile_banner_close)
                Box(
                    Modifier
                        .size(PhantomSpace.touchMinAndroid)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(DesignR.drawable.lc_close), null, tint = c.text, modifier = Modifier.size(PhantomSize.iconL)) }
                Text(stringResource(R.string.profile_banner), style = PhantomText.title2, color = c.text, modifier = Modifier.padding(start = PhantomSpace.s2))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ModeSwitch(videoMode) { videoMode = it }
                val shown = if (videoMode) video?.still else shots.getOrNull(sel)
                val pick = {
                    if (videoMode) {
                        clips.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    } else {
                        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                FocusCrop(shown, cropH, Modifier.padding(top = PhantomSpace.s3), onPick = if (busy) null else ({ pick() }), video = videoMode) { fx, fy ->
                    if (videoMode) video = video?.let { PickedVideo(it.file, it.still.at(fx, fy), it.ms) }
                    else if (sel in shots.indices) shots[sel] = shots[sel].at(fx, fy)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.gutterPhone).padding(top = PhantomSpace.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (videoMode) R.string.profile_banner_video else R.string.profile_banner_photos),
                        style = PhantomText.headline,
                        color = c.text,
                    )
                    if (!videoMode) {
                        val full = shots.size >= PeerProfile.MAX_BANNER_ITEMS
                        val count by animateFloatAsState(if (fullFlash) 1f else 0f, PhantomMotion.stateChange, label = "full")
                        Text(
                            stringResource(R.string.profile_banner_count, shots.size, PeerProfile.MAX_BANNER_ITEMS),
                            style = PhantomText.footnote,
                            color = if (count > 0.5f) c.text else c.text3,
                            modifier = Modifier.padding(start = PhantomSpace.s2).testTag("banner-count"),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val canPick = !busy && (videoMode || shots.size < PeerProfile.MAX_BANNER_ITEMS)
                    Row(
                        Modifier
                            .testTag("banner-from-phone")
                            .heightIn(min = PhantomSpace.touchMinAndroid)
                            .clip(CircleShape)
                            .background(c.surface)
                            .clickable(enabled = canPick, role = Role.Button) { pick() }
                            .padding(horizontal = PhantomSpace.s4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
                    ) {
                        val tint = if (canPick) c.text else c.text3
                        Icon(painterResource(if (videoMode) R.drawable.ph_video else R.drawable.ph_image), null, tint = tint, modifier = Modifier.size(PhantomSize.iconM))
                        Text(stringResource(R.string.profile_banner_from_phone), style = PhantomText.subhead, color = tint)
                    }
                }
                if (!videoMode) {
                    ThumbRow(
                        shots = shots,
                        sel = sel,
                        onSelect = { sel = it },
                        onRemove = { i ->
                            shots.removeAt(i)
                            sel = sel.coerceIn(0, maxOf(0, shots.size - 1))
                        },
                        onMove = { from, to ->
                            val s = shots.removeAt(from)
                            shots.add(to, s)
                            sel = to
                        },
                        onFull = { fullFlash = true },
                    )
                } else if (video?.let { bannerVideoTravels(it.file.length()) } == false) {
                    Text(
                        stringResource(R.string.profile_banner_video_local),
                        style = PhantomText.footnote,
                        color = c.text2,
                        modifier = Modifier.padding(horizontal = PhantomSpace.gutterPhone, vertical = PhantomSpace.s2),
                    )
                }
            }
            val shownNote = note ?: if (failed) R.string.profile_banner_failed else null
            if (shownNote != null) {
                Text(stringResource(shownNote), style = PhantomText.footnote, color = c.warning, modifier = Modifier.testTag("banner-note").padding(horizontal = PhantomSpace.gutterPhone).padding(top = PhantomSpace.s2))
            }
            val ready = !busy && !saving
            Box(
                Modifier
                    .testTag("banner-save")
                    .padding(PhantomSpace.gutterPhone)
                    .fillMaxWidth()
                    .height(PhantomSize.buttonL)
                    .graphicsLayer { alpha = if (ready) 1f else 0.6f }
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable(enabled = ready, role = Role.Button) {
                        if (videoMode && video != null) onSave(emptyList(), video) else onSave(shots.toList(), null)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(if (saving) R.string.profile_banner_saving else R.string.profile_banner_save), style = PhantomText.headline, color = c.onAccent)
            }
        }
    }
}

@Composable
private fun ModeSwitch(video: Boolean, onPick: (Boolean) -> Unit) {
    val c = LocalPhantomColors.current
    val at by animateFloatAsState(if (video) 1f else 0f, PhantomMotion.stateChange, label = "mode")
    BoxWithConstraints(
        Modifier
            .padding(horizontal = PhantomSpace.gutterPhone)
            .fillMaxWidth()
            .height(PhantomSpace.touchMinAndroid)
            .clip(CircleShape)
            .background(c.surface),
    ) {
        val half = maxWidth / 2
        Box(
            Modifier
                .offset(x = half * at)
                .width(half)
                .height(PhantomSpace.touchMinAndroid)
                .padding(3.dp)
                .clip(CircleShape)
                .background(c.raised),
        )
        Row(Modifier.fillMaxSize()) {
            for ((isVideo, label, icon) in listOf(
                Triple(false, R.string.profile_banner_photos, R.drawable.ph_image),
                Triple(true, R.string.profile_banner_video, R.drawable.ph_video),
            )) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(PhantomSpace.touchMinAndroid)
                        .clip(CircleShape)
                        .clickable(role = Role.Tab) { onPick(isVideo) }
                        .testTag(if (isVideo) "banner-mode-video" else "banner-mode-photos"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val on = isVideo == video
                    Icon(painterResource(icon), null, tint = if (on) c.text else c.text2, modifier = Modifier.size(PhantomSize.iconS))
                    Text(stringResource(label), style = PhantomText.subhead, color = if (on) c.text else c.text2, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** Фото цілком, рамка банера за фокусом; палець веде рамку, а поза нею — притемнено. */
@Composable
private fun FocusCrop(shot: BannerShot?, height: Dp, modifier: Modifier, onPick: (() -> Unit)? = null, video: Boolean = false, onFocus: (Int, Int) -> Unit) {
    val c = LocalPhantomColors.current
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .testTag("banner-crop")
            .fillMaxWidth()
            .height(height)
            .background(c.surface),
    ) {
        if (shot == null) {
            // T51: порожній кадр — сам двері до галереї, а не сіра пляма без дії.
            if (onPick != null) {
                Column(
                    Modifier
                        .testTag("banner-empty-pick")
                        .fillMaxSize()
                        .clickable(role = Role.Button, onClick = onPick),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(if (video) R.drawable.ph_video else R.drawable.ph_image), null, tint = c.text2, modifier = Modifier.size(PhantomSize.iconL))
                    Text(
                        stringResource(R.string.profile_banner_from_phone),
                        style = PhantomText.headline,
                        color = c.text,
                        modifier = Modifier.padding(top = PhantomSpace.s2),
                    )
                }
            }
            return@BoxWithConstraints
        }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val img = BannerFocus.fitted(w, h, shot.bitmap.width, shot.bitmap.height)
        val frame = BannerFocus.frame(img, shot.fx, shot.fy)
        val latest = remember { arrayOf(onFocus) }
        latest[0] = onFocus
        Image(
            bitmap = shot.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(shot.hash) {
                    detectDragGestures(
                        onDragStart = { p -> BannerFocus.fromTouch(img, p).let { (x, y) -> latest[0](x, y) } },
                    ) { change, _ -> BannerFocus.fromTouch(img, change.position).let { (x, y) -> latest[0](x, y) } }
                },
        )
        with(density) {
            // Притемнення поза рамкою — чотири смуги scrim, без розмиття.
            Box(Modifier.offset(img.left.toDp(), img.top.toDp()).size(img.width.toDp(), (frame.top - img.top).coerceAtLeast(0f).toDp()).background(c.scrim))
            Box(Modifier.offset(img.left.toDp(), (frame.top + frame.height).toDp()).size(img.width.toDp(), (img.top + img.height - frame.top - frame.height).coerceAtLeast(0f).toDp()).background(c.scrim))
            Box(Modifier.offset(img.left.toDp(), frame.top.toDp()).size((frame.left - img.left).coerceAtLeast(0f).toDp(), frame.height.toDp()).background(c.scrim))
            Box(Modifier.offset((frame.left + frame.width).toDp(), frame.top.toDp()).size((img.left + img.width - frame.left - frame.width).coerceAtLeast(0f).toDp(), frame.height.toDp()).background(c.scrim))
            Box(Modifier.testTag("banner-frame").offset(frame.left.toDp(), frame.top.toDp()).size(frame.width.toDp(), frame.height.toDp()).border(2.dp, c.onAct))
            Box(
                Modifier
                    .offset((frame.left + frame.width / 2f).toDp() - 10.dp, (frame.top + frame.height / 2f).toDp() - 10.dp)
                    .size(20.dp)
                    .border(2.dp, c.onAct, CircleShape),
            )
        }
        OnPhotoPill(Modifier.align(Alignment.TopStart).padding(PhantomSpace.s2)) {
            Icon(painterResource(R.drawable.ph_focus), null, tint = c.onAct, modifier = Modifier.size(PhantomSize.iconS))
            Text(stringResource(R.string.profile_banner_focus), style = PhantomText.footnote, color = c.onAct)
        }
    }
}

private val ThumbSize = 48.dp
private val ThumbGapMax = 8.dp

@Composable
private fun ThumbRow(
    shots: List<BannerShot>,
    sel: Int,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onFull: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val density = LocalDensity.current
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDx by remember { mutableStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = PhantomSpace.s2)) {
        // Сім клітинок по 48 dp мусять влізти й у 390: зазор стискається, а не дотик.
        val slots = PeerProfile.MAX_BANNER_ITEMS + 1
        val gap = ((maxWidth - PhantomSpace.gutterPhone * 2 - ThumbSize * slots) / (slots - 1)).coerceIn(2.dp, ThumbGapMax)
        val step = with(density) { (ThumbSize + gap).toPx() }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PhantomSpace.gutterPhone).padding(top = PhantomSpace.s3),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            shots.forEachIndexed { i, s ->
                val lifted = i == dragFrom
                Box(
                    Modifier
                        .testTag("banner-thumb-$i")
                        .size(ThumbSize)
                        .graphicsLayer {
                            translationX = if (lifted) dragDx else 0f
                            scaleX = if (lifted) 1.04f else 1f
                            scaleY = if (lifted) 1.04f else 1f
                        }
                        .clip(PhantomRadii.SmallShape)
                        .then(if (i == sel || lifted) Modifier.border(2.dp, c.text, PhantomRadii.SmallShape) else Modifier)
                        .pointerInput(s.hash, shots.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragFrom = i; dragDx = 0f },
                                onDrag = { change, d -> change.consume(); dragDx += d.x },
                                onDragEnd = {
                                    val to = (i + (dragDx / step).roundToInt()).coerceIn(0, shots.size - 1)
                                    dragFrom = -1
                                    dragDx = 0f
                                    if (to != i) onMove(i, to)
                                },
                                onDragCancel = { dragFrom = -1; dragDx = 0f },
                            )
                        }
                        .clickable(role = Role.Button) { onSelect(i) },
                ) {
                    Image(s.bitmap.asImageBitmap(), stringResource(R.string.profile_banner_photo_n, i + 1), contentScale = ContentScale.Crop, alignment = BannerFocus.alignment(s.fx, s.fy), modifier = Modifier.fillMaxSize())
                    Text(
                        "${i + 1}",
                        style = PhantomText.caption,
                        color = c.onAct,
                        modifier = Modifier.align(Alignment.TopStart).padding(3.dp).clip(CircleShape).background(c.scrim).padding(horizontal = 5.dp),
                    )
                    Icon(painterResource(R.drawable.ph_grip), null, tint = c.onAct, modifier = Modifier.align(Alignment.BottomEnd).size(PhantomSize.iconS))
                    if (i == sel) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(c.scrim),
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(DesignR.drawable.lc_close), null, tint = c.onAct, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
            if (shots.size >= PeerProfile.MAX_BANNER_ITEMS) {
                Box(
                    Modifier
                        .testTag("banner-slot-full")
                        .size(ThumbSize)
                        .clip(PhantomRadii.SmallShape)
                        .background(c.surface)
                        .clickable(role = Role.Button, onClick = onFull),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.profile_banner_max), style = PhantomText.caption, color = c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                }
            }
        }
        if (sel in shots.indices && dragFrom < 0) {
            // Хрестик малий, а дотик — 48 dp, з центром на ньому.
            val remove = stringResource(R.string.profile_photo_remove)
            val x = PhantomSpace.gutterPhone + (ThumbSize + gap) * sel + ThumbSize - 12.dp - PhantomSpace.touchMinAndroid / 2
            Box(
                Modifier
                    .testTag("banner-remove")
                    .offset(x = x, y = PhantomSpace.s3 + 12.dp - PhantomSpace.touchMinAndroid / 2)
                    .size(PhantomSpace.touchMinAndroid)
                    .clickable(role = Role.Button) { onRemove(sel) }
                    .semantics { contentDescription = remove },
            )
        }
    }
}

/** Знак профілю з мосту; невідомий — дім. */
@androidx.annotation.DrawableRes
internal fun glyphIcon(glyph: String?): Int = when (glyph) {
    "work" -> R.drawable.ph_glyph_work
    "shop" -> R.drawable.ph_glyph_shop
    "book" -> R.drawable.ph_glyph_book
    "heart" -> R.drawable.ph_glyph_heart
    "plane" -> R.drawable.ph_glyph_plane
    else -> R.drawable.ph_glyph_home
}

internal val BannerAvatarLift: Dp = 46.dp
