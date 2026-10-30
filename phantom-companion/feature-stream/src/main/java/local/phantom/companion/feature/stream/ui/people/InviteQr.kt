package local.phantom.companion.feature.stream.ui.people

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import local.phantom.companion.core.design.tokens.PhantomRadius
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder

/**
 * The operator's invite as something a second phone can simply look at.
 *
 * Adding a person meant moving 200 characters between two devices by hand,
 * which on a phone means a messenger — and a code that travels through a
 * messenger has been somewhere. A camera pointed at a screen is the shortest
 * path there is, and it is the one every other app has taught people already.
 *
 * Drawn as squares on a Canvas rather than rasterised into a Bitmap: the code
 * is redrawn on every theme change and every rotation, and a bitmap at that
 * cadence is a bitmap allocated at that cadence. It also means the modules
 * land on exact pixel boundaries at any size, which is what a scanner needs.
 */
@Composable
fun InviteQr(
    code: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    // The matrix is the expensive part and depends on nothing but the text.
    val matrix = remember(code) { encode(code) }
    // Capped rather than full-width: at the card's width the code stood taller
    // than the space above the composer and was simply cut off at the bottom —
    // a picture of a QR that no scanner on earth can read. This size is still
    // far more than the ~4 px per module a camera needs.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MAX_SIDE)
            .aspectRatio(1f)
            // Біле поле під кодом — не хром, а вимога сканера; кут — токен v1.2.
            .background(background, RoundedCornerShape(PhantomRadius.control)),
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = background, size = this.size)
                val modules = matrix.width
                // The quiet zone is measured in MODULES, not in dp. Four each
                // side is what the standard asks for and what a scanner looks
                // for; a fixed dp margin is below that at any size the code
                // grows to, and on a dark screen a code with a thin white
                // border is one another phone's camera declines to see.
                val grid = modules + QUIET_MODULES * 2
                // Floor to whole pixels and centre the remainder: a fractional
                // module width makes neighbouring squares bleed into each
                // other, and a scanner reads that as a different code.
                val cell = kotlin.math.floor(size.width / grid)
                val inset = (size.width - cell * grid) / 2f + cell * QUIET_MODULES
                // Run-length along each row: a QR is mostly horizontal runs, so
                // one rect per run instead of one per module. Drawn per module
                // this issued ~1,600 draw calls a frame — and at the pixel
                // resolution it started life at, over a hundred thousand, which
                // is an ANR with a picture of a QR code on it.
                for (y in 0 until modules) {
                    var x = 0
                    while (x < modules) {
                        if (matrix.get(x, y).toInt() != 1) {
                            x++
                            continue
                        }
                        var end = x
                        while (end + 1 < modules && matrix.get(end + 1, y).toInt() == 1) end++
                        drawRect(
                            color = foreground,
                            topLeft = Offset(inset + x * cell, inset + y * cell),
                            size = Size(cell * (end - x + 1), cell),
                        )
                        x = end + 1
                    }
                }
            }
        }
    }
}

/**
 * Null rather than a throw: an invite that cannot be drawn is a missing
 * picture, and the screen still has the code in text under it. A crash here
 * would take the whole People screen with it.
 */
private fun encode(code: String): ByteMatrix? = runCatching {
    // Encoder, not QRCodeWriter: the writer scales the code up to a requested
    // pixel size, and what comes back is a bitmap-shaped matrix rather than the
    // ~57×57 grid of actual modules. Drawing THAT square by square is a hundred
    // thousand draw calls; this is the grid itself, and the Canvas scales it.
    Encoder.encode(
        code,
        // M keeps the module count down on a code this long; a phone screen is
        // clean, lit and undamaged, so the redundancy that protects a printed
        // sticker only costs density here.
        ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
    ).matrix
}.getOrNull()

/**
 * The white border the QR standard requires, in modules. Below four, scanners
 * start refusing — and «the code is on the screen but their phone will not take
 * it» is indistinguishable from «this app does not work».
 */
private const val QUIET_MODULES = 4

/** Big enough for a camera across a table, small enough to fit on a screen. */
private val MAX_SIDE = 240.dp
