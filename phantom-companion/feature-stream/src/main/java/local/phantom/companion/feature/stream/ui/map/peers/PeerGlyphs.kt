package local.phantom.companion.feature.stream.ui.map.peers

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.theme.PhantomPalette
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkBox
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPalette
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPen
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPlate
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkTone

/**
 * Фігурка людини на спільній коробці родини. Звірений — суцільне тіло,
 * незвірений — контур: різницю несе ФОРМА, силует у обох станів один.
 */
internal object PeerBody {

    /** Багатокутник, а не диск: контурний стан мусить бути кільцем. */
    private val HEAD: FloatArray = ring(54f, 26f, 15f, points = 20)

    /** Вирізи пахв і паху — те, від чого силует читається людиною на 36dp. */
    private val BODY: FloatArray = floatArrayOf(
        54f, 44f,
        70f, 48f,
        80f, 58f,
        84f, 92f,
        74f, 94f,
        70f, 70f,
        72f, 96f,
        72f, 118f,
        58f, 118f,
        54f, 98f,
        50f, 118f,
        36f, 118f,
        36f, 96f,
        38f, 70f,
        34f, 94f,
        24f, 92f,
        28f, 58f,
        38f, 48f,
    )

    /** Двокрайовий закон плашки: чорнило гине на нічній землі, світле — на денній. */
    private const val HALO_W = MarkBox.INK_W * 2.8f

    fun solid(): MarkPen.() -> Unit = {
        poly(MarkTone.BONE, *BODY)
        clipped(BODY) { plate(MarkTone.SHADE, 54f, 40f, 40f, 84f) }
        edge(*BODY)
        poly(MarkTone.BONE, *HEAD)
        clipped(HEAD) { plate(MarkTone.SHADE, 54f, 8f, 34f, 38f) }
        edge(*HEAD)
    }

    fun outline(): MarkPen.() -> Unit = {
        halo(BODY)
        halo(HEAD)
        edge(*BODY)
        edge(*HEAD)
    }

    /** Замикає ламану вручну: [MarkPen.line] малює відкритий шлях. */
    private fun MarkPen.halo(pts: FloatArray) =
        line(MarkTone.BONE, HALO_W, *pts, pts[0], pts[1])

    private fun ring(cx: Float, cy: Float, r: Float, points: Int): FloatArray {
        val out = FloatArray(points * 2)
        for (i in 0 until points) {
            val a = Math.toRadians(-90.0 + i * 360.0 / points)
            out[i * 2] = cx + (r * kotlin.math.cos(a)).toFloat()
            out[i * 2 + 1] = cy + (r * kotlin.math.sin(a)).toFloat()
        }
        return out
    }
}

/**
 * Один тон на всіх людей: різницю вже несе форма. [PLATE] тема-інваріантний і
 * дорівнює денному токену — нічний #A78BFA на кістяній плашці дає 2.37:1,
 * денний #6D28D9 — 6.18:1. Від землі фігурку відділяє кістка з чорнилом.
 */
private object PeerTones {
    val SIGNAL_NIGHT = PhantomPalette.PeerSignalNight.toArgb()
    val SIGNAL_DAY = PhantomPalette.PeerSignalDay.toArgb()
    val PLATE = PhantomPalette.PeerPlate.toArgb()
}

internal object PeerPlate {

    val ANCHOR_SLACK_DP: Float = MarkPlate.ANCHOR_SLACK_DP

    fun imageId(verified: Boolean, isDark: Boolean): String =
        "peer-${if (verified) "solid" else "outline"}-${if (isDark) "night" else "day"}"

    fun figure(verified: Boolean, isDark: Boolean, density: Float): Bitmap = MarkPlate.bake(
        MarkPalette.role(PeerTones.SIGNAL_NIGHT, PeerTones.SIGNAL_DAY, PeerTones.PLATE, isDark),
        density,
        if (verified) PeerBody.solid() else PeerBody.outline(),
    )
}
