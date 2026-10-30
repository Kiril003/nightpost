package local.phantom.companion.ui.messenger.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.layout.HideBottomChrome
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.data.applock.AppLockStore

internal fun formatPageCounter(currentPage: Int, totalPages: Int): String =
    "${currentPage.coerceAtLeast(1)} / ${totalPages.coerceAtLeast(1)}"

internal class PdfDocumentSession private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageCount: Int,
) : Closeable {
    private val lock = Any()
    private var closed = false

    fun renderPage(index: Int, maxSidePx: Int = PreviewStore.MAX_IMAGE_SIDE): Bitmap? = synchronized(lock) {
        if (closed || index !in 0 until pageCount) return null
        return try {
            renderer.openPage(index).use { page ->
                val rawW = page.width.coerceAtLeast(1)
                val rawH = page.height.coerceAtLeast(1)
                val scale = (maxSidePx.toFloat() / maxOf(rawW, rawH).toFloat()).coerceIn(1f, 3f)
                val (bmpW, bmpH) = targetSize(
                    (rawW * scale).roundToInt().coerceAtLeast(1),
                    (rawH * scale).roundToInt().coerceAtLeast(1),
                    maxSidePx,
                )
                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        fun open(file: File): PdfDocumentSession? {
            if (!file.isFile) return null
            var pfd: ParcelFileDescriptor? = null
            return try {
                checkPdfEnvelope(file)
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) ?: return null
                val renderer = PdfRenderer(pfd)
                val count = renderer.pageCount
                if (count <= 0) {
                    renderer.close()
                    pfd.close()
                    null
                } else {
                    PdfDocumentSession(pfd, renderer, count)
                }
            } catch (e: IOException) {
                runCatching { pfd?.close() }
                null
            } catch (e: SecurityException) {
                runCatching { pfd?.close() }
                null
            } catch (_: Exception) {
                runCatching { pfd?.close() }
                null
            }
        }
    }
}

class PreviewViewerActivity : ComponentActivity() {

    private var previewId: String = ""
    private var previewKind: String = ""
    private var cleaned = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = runCatching { AppLockStore.of(this).enabled }.getOrDefault(true)
        if (locked) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        previewId = intent?.getStringExtra(EXTRA_ID).orEmpty()
        previewKind = intent?.getStringExtra(EXTRA_KIND).orEmpty()
        val previewName = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val previewDir = File(cacheDir, "preview")

        setContent {
            PhantomTheme {
                PreviewViewerScreen(
                    dir = previewDir,
                    id = previewId,
                    kind = previewKind,
                    title = previewName,
                    onClose = { closeViewer() },
                )
            }
        }
    }

    private fun closeViewer() {
        cleanupPreviewFiles()
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            cleanupPreviewFiles()
        }
        super.onDestroy()
    }

    private fun cleanupPreviewFiles() {
        if (cleaned) return
        cleaned = true
        val id = previewId
        if (PreviewStore.ID_REGEX.matches(id)) {
            runCatching { (applicationContext as? PhantomApp)?.container?.previewStore?.delete(id) }
            runCatching { PreviewStore.deleteFiles(File(cacheDir, "preview"), id) }
        }
    }

    companion object {
        const val EXTRA_ID = "phantom.preview.id"
        const val EXTRA_KIND = "phantom.preview.kind"
        const val EXTRA_NAME = "phantom.preview.name"

        fun launch(context: Context, id: String, kind: String, name: String): Boolean = runCatching {
            val intent = Intent(context, PreviewViewerActivity::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_NAME, name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

@Composable
internal fun PreviewViewerScreen(
    dir: File,
    id: String,
    kind: String,
    title: String,
    onClose: () -> Unit,
) {
    val c = LocalPhantomColors.current
    HideBottomChrome()
    BackHandler(onBack = onClose)

    val validId = remember(id) { PreviewStore.ID_REGEX.matches(id) }
    val rawFile = remember(dir, id, validId) {
        if (validId) File(dir, id) else null
    }
    val jpgFile = remember(dir, id, validId) {
        if (validId) File(dir, "$id.jpg") else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .systemBarsPadding(),
    ) {
        when {
            !validId || rawFile == null -> PreviewFailureBody(onClose = onClose)
            kind == PreviewStore.KIND_PDF -> PdfPreviewBody(
                file = rawFile,
                title = title,
                onClose = onClose,
            )
            kind == PreviewStore.KIND_HEIC -> HeicPreviewBody(
                rawFile = rawFile,
                jpgFile = jpgFile,
                title = title,
                onClose = onClose,
            )
            else -> PreviewFailureBody(onClose = onClose)
        }
    }
}

@Composable
private fun PreviewTopBar(
    title: String,
    counter: String?,
    onClose: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PhantomSize.topbarPhone)
            .background(c.surface)
            .padding(end = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(PhantomSpace.touchMinAndroid)
                .clip(CircleShape)
                .clickable(
                    onClickLabel = stringResource(R.string.preview_close),
                    role = Role.Button,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(DesignR.drawable.lc_back),
                contentDescription = stringResource(R.string.preview_close),
                tint = c.text,
                modifier = Modifier.size(PhantomSize.iconL),
            )
        }
        Text(
            text = title,
            style = PhantomText.subhead,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (counter != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(PhantomRadius.chip))
                    .background(c.raised)
                    .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s1),
            ) {
                Text(
                    text = counter,
                    style = PhantomText.caption,
                    color = c.text2,
                )
            }
        }
    }
}

@Composable
private fun PdfPreviewBody(
    file: File,
    title: String,
    onClose: () -> Unit,
) {
    val c = LocalPhantomColors.current
    var session by remember(file) { mutableStateOf<PdfDocumentSession?>(null) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        val opened = PdfDocumentSession.open(file)
        if (opened == null) {
            failed = true
        } else {
            session = opened
        }
        onDispose {
            opened?.close()
            session = null
        }
    }

    if (failed) {
        PreviewFailureBody(onClose = onClose)
        return
    }

    val activeSession = session ?: return
    val listState = rememberLazyListState()
    val currentPage by remember(activeSession.pageCount) {
        derivedStateOf {
            (listState.firstVisibleItemIndex + 1).coerceIn(1, activeSession.pageCount)
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        PreviewTopBar(
            title = title,
            counter = formatPageCounter(currentPage, activeSession.pageCount),
            onClose = onClose,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        if (nextScale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PhantomSpace.s3),
                verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
            ) {
                items(
                    count = activeSession.pageCount,
                    key = { index -> index },
                ) { pageIndex ->
                    PdfPageItem(
                        session = activeSession,
                        pageIndex = pageIndex,
                        onPageError = { failed = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    session: PdfDocumentSession,
    pageIndex: Int,
    onPageError: () -> Unit,
) {
    val c = LocalPhantomColors.current
    var bitmap by remember(session, pageIndex) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(session, pageIndex) {
        onDispose {
            val old = bitmap
            bitmap = null
            if (old != null) {
                Handler(Looper.getMainLooper()).post {
                    if (!old.isRecycled) old.recycle()
                }
            }
        }
    }

    LaunchedEffect(session, pageIndex) {
        val rendered = withContext(Dispatchers.IO) {
            session.renderPage(pageIndex)
        }
        if (rendered == null) {
            onPageError()
        } else {
            bitmap = rendered
        }
    }

    val currentBitmap = bitmap
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            val ratio = currentBitmap.width.toFloat().coerceAtLeast(1f) /
                currentBitmap.height.toFloat().coerceAtLeast(1f)
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = formatPageCounter(pageIndex + 1, session.pageCount),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
            )
        } else {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f),
            )
        }
    }
}

@Composable
private fun HeicPreviewBody(
    rawFile: File,
    jpgFile: File?,
    title: String,
    onClose: () -> Unit,
) {
    var bitmap by remember(rawFile) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(rawFile) { mutableStateOf(false) }

    DisposableEffect(rawFile) {
        onDispose {
            val old = bitmap
            bitmap = null
            if (old != null) {
                Handler(Looper.getMainLooper()).post {
                    if (!old.isRecycled) old.recycle()
                }
            }
        }
    }

    LaunchedEffect(rawFile, jpgFile) {
        val decoded = withContext(Dispatchers.IO) {
            decodeHeicBitmap(rawFile, jpgFile)
        }
        if (decoded == null) {
            failed = true
        } else {
            bitmap = decoded
        }
    }

    if (failed) {
        PreviewFailureBody(onClose = onClose)
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        PreviewTopBar(
            title = title,
            counter = null,
            onClose = onClose,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        if (nextScale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val currentBitmap = bitmap
            if (currentBitmap != null && !currentBitmap.isRecycled) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun decodeHeicBitmap(rawFile: File, jpgFile: File?): Bitmap? {
    val candidate = when {
        jpgFile != null && jpgFile.isFile && jpgFile.length() > 0L -> jpgFile
        rawFile.isFile && looksLikeHeic(rawFile) -> rawFile
        else -> return null
    }
    return try {
        val source = ImageDecoder.createSource(candidate) ?: return null
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            if (heicTooLarge(info.size.width, info.size.height)) throw IOException("heic too large")
            val (targetW, targetH) = targetSize(info.size.width, info.size.height, PreviewStore.MAX_IMAGE_SIDE)
            decoder.setTargetSize(targetW, targetH)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun PreviewFailureBody(onClose: () -> Unit) {
    val c = LocalPhantomColors.current
    val closeLabel = stringResource(R.string.preview_close)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .padding(PhantomSpace.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.preview_cant_open),
            style = PhantomText.body,
            color = c.text,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s4))
        Box(
            modifier = Modifier
                .heightIn(min = PhantomSize.buttonL)
                .clip(CircleShape)
                .background(c.overlay)
                .clickable(
                    onClickLabel = closeLabel,
                    role = Role.Button,
                    onClick = onClose,
                )
                .padding(horizontal = PhantomSpace.s6, vertical = PhantomSpace.s3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = closeLabel,
                style = PhantomText.headline,
                color = c.text,
            )
        }
    }
}
