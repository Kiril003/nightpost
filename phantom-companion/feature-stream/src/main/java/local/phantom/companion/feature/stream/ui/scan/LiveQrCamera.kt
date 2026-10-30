package local.phantom.companion.feature.stream.ui.scan

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.MultiFormatReader
import java.util.concurrent.Executors

/** Спільний видошукач живого QR-сканера для всіх екранів застосунку. */
@Composable
fun LiveQrCamera(
    modifier: Modifier = Modifier,
    resetKey: Int = 0,
    onScan: (String) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader() }
    val latch = remember { ScanLatch() }
    val handler by rememberUpdatedState(onScan)

    LaunchedEffect(resetKey) { latch.reset() }

    DisposableEffect(previewView) {
        val future = ProcessCameraProvider.getInstance(context)
        var bound: ProcessCameraProvider? = null
        var turn = 0
        var gone = false

        future.addListener(
            {
                if (gone) return@addListener
                val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                val preview = CameraPreview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        if (latch.closed) return@setAnalyzer
                        QrFrame.read(proxy, reader)?.let { text ->
                            latch.offer(text) { t -> handler(t) }
                        }
                    } finally {
                        proxy.close()
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    bound = provider
                    turn = CameraTurn.take()
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            gone = true
            if (CameraTurn.isLatest(turn)) runCatching { bound?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}
