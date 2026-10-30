// GENERATED from docs/design/system/tokens.json — do not edit by hand.
// Target: phantom-companion/core-design/src/main/java/local/phantom/companion/core/design/tokens/PhantomTokens.kt
// Wire-up: PhantomTheme provides LocalPhantomColors (night/day) and MaterialTheme.colorScheme is DERIVED from it
// (see PHANTOM_DESIGN_SYSTEM.md §9.3). Fonts: res/font/manrope_* (must cover U+02BC — see §3.1).
package local.phantom.companion.core.design.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Immutable
data class PhantomColors(
    val canvas: Color,
    val surface: Color,
    val raised: Color,
    val overlay: Color,
    val line: Color,
    val lineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPress: Color,
    val accentText: Color,
    val accentLine: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val self: Color,
    val onSelf: Color,
    val onSelf2: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val actPin: Color,
    val actMute: Color,
    val actArchive: Color,
    val onAct: Color,
    val scrim: Color,
    val wall: Color,
    val wallDot: Color,
    val cover: Color,
    val coverLine: Color,
    val person: List<Pair<Color, Color>>, // fill to ink
    val isDark: Boolean,
)

val NightColors = PhantomColors(
    canvas = Color(0xFF111214),
    surface = Color(0xFF1C1D21),
    raised = Color(0xFF26272B),
    overlay = Color(0xFF2B2C31),
    line = Color(0xFF2C2D31),
    lineStrong = Color(0xFF3C3D42),
    text = Color(0xFFECEAE5),
    text2 = Color(0xFFA3A4AA),
    text3 = Color(0xFF949599),
    accent = Color(0xFFE2A048),
    accentHover = Color(0xFFE8AE60),
    accentPress = Color(0xFFCF8D36),
    accentText = Color(0xFFE8AE60),
    accentLine = Color(0xFFE2A048),
    accentSoft = Color(0xFF2A2620),
    onAccent = Color(0xFF1B1408),
    self = Color(0xFF43361F),
    onSelf = Color(0xFFECEAE5),
    onSelf2 = Color(0xFFC9B89A),
    success = Color(0xFF7FC79A),
    danger = Color(0xFFE8877A),
    warning = Color(0xFFE4C07A),
    actPin = Color(0xFF323C45),
    actMute = Color(0xFF2E2B35),
    actArchive = Color(0xFF36322C),
    onAct = Color(0xFFF4EFE8),
    scrim = Color(0x9E000000),
    wall = Color(0xFF111214),
    wallDot = Color(0x07ECEAE5),
    cover = Color(0xFF17181B),
    coverLine = Color(0xFF2E2F31),
    person = listOf(
        Color(0xFF966243) to Color(0xFFFFFFFF),
        Color(0xFF4D7A37) to Color(0xFFFFFFFF),
        Color(0xFF47729D) to Color(0xFFFFFFFF),
        Color(0xFF367976) to Color(0xFFFFFFFF),
        Color(0xFFA85376) to Color(0xFFFFFFFF),
        Color(0xFF7B5EB7) to Color(0xFFFFFFFF),
    ),
    isDark = true,
)

val DayColors = PhantomColors(
    canvas = Color(0xFFF6F1E7),
    surface = Color(0xFFFCFAF5),
    raised = Color(0xFFE9E0CF),
    overlay = Color(0xFFE3DACA),
    line = Color(0xFFD8D0C2),
    lineStrong = Color(0xFFC2B8A7),
    text = Color(0xFF1E1A15),
    text2 = Color(0xFF5B5247),
    text3 = Color(0xFF665F55),
    accent = Color(0xFFC27A16),
    accentHover = Color(0xFFCE8722),
    accentPress = Color(0xFFBD7614),
    accentText = Color(0xFF84510D),
    accentLine = Color(0xFFA76811),
    accentSoft = Color(0xFFF1E6D2),
    onAccent = Color(0xFF1E1A15),
    self = Color(0xFFF2DDB4),
    onSelf = Color(0xFF1E1A15),
    onSelf2 = Color(0xFF5B5247),
    success = Color(0xFF18703E),
    danger = Color(0xFFB3382C),
    warning = Color(0xFF7A5200),
    actPin = Color(0xFF34485A),
    actMute = Color(0xFF463F55),
    actArchive = Color(0xFF5E5850),
    onAct = Color(0xFFFFFFFF),
    scrim = Color(0x6B1B1611),
    wall = Color(0xFFF3EDE1),
    wallDot = Color(0x101E1A15),
    cover = Color(0xFFD8C6A2),
    coverLine = Color(0xFFBFAB84),
    person = listOf(
        Color(0xFF966243) to Color(0xFFFFFFFF),
        Color(0xFF4D7A37) to Color(0xFFFFFFFF),
        Color(0xFF47729D) to Color(0xFFFFFFFF),
        Color(0xFF367976) to Color(0xFFFFFFFF),
        Color(0xFFA85376) to Color(0xFFFFFFFF),
        Color(0xFF7B5EB7) to Color(0xFFFFFFFF),
    ),
    isDark = false,
)

val DuskColors = PhantomColors(
    canvas = Color(0xFF22150F),
    surface = Color(0xFF2E1D15),
    raised = Color(0xFF35221A),
    overlay = Color(0xFF402B21),
    line = Color(0xFF3A261D),
    lineStrong = Color(0xFF4A3227),
    text = Color(0xFFF5E6DA),
    text2 = Color(0xFFC8AB99),
    text3 = Color(0xFFB59886),
    accent = Color(0xFFE2A048),
    accentHover = Color(0xFFE8AE60),
    accentPress = Color(0xFFCF8D36),
    accentText = Color(0xFFE8AE60),
    accentLine = Color(0xFFE2A048),
    accentSoft = Color(0xFF3F2B1A),
    onAccent = Color(0xFF1B1408),
    self = Color(0xFF5A3322),
    onSelf = Color(0xFFF5E6DA),
    onSelf2 = Color(0xFFC8AB99),
    success = Color(0xFF7FC79A),
    danger = Color(0xFFE8877A),
    warning = Color(0xFFE4C07A),
    actPin = Color(0xFF484C51),
    actMute = Color(0xFF443B41),
    actArchive = Color(0xFF4C4238),
    onAct = Color(0xFFFFFFF4),
    scrim = Color(0x9E000000),
    wall = Color(0xFF22150F),
    wallDot = Color(0x07F5E6DA),
    cover = Color(0xFF27180F),
    coverLine = Color(0xFF432C20),
    person = listOf(
        Color(0xFF966243) to Color(0xFFFFFFFF),
        Color(0xFF4D7A37) to Color(0xFFFFFFFF),
        Color(0xFF47729D) to Color(0xFFFFFFFF),
        Color(0xFF367976) to Color(0xFFFFFFFF),
        Color(0xFFA85376) to Color(0xFFFFFFFF),
        Color(0xFF7B5EB7) to Color(0xFFFFFFFF),
    ),
    isDark = true,
)

val GoldenColors = PhantomColors(
    canvas = Color(0xFFF3DEB6),
    surface = Color(0xFFFAEDD2),
    raised = Color(0xFFEBD2A4),
    overlay = Color(0xFFE2C895),
    line = Color(0xFFE2CCA1),
    lineStrong = Color(0xFFD3BA8B),
    text = Color(0xFF22180A),
    text2 = Color(0xFF554125),
    text3 = Color(0xFF5C4729),
    accent = Color(0xFFC27A16),
    accentHover = Color(0xFFCE8722),
    accentPress = Color(0xFFBD7614),
    accentText = Color(0xFF723F04),
    accentLine = Color(0xFFA76811),
    accentSoft = Color(0xFFF0D8AA),
    onAccent = Color(0xFF22180A),
    self = Color(0xFFEACD99),
    onSelf = Color(0xFF22180A),
    onSelf2 = Color(0xFF554125),
    success = Color(0xFF18703E),
    danger = Color(0xFFA83329),
    warning = Color(0xFF7A5200),
    actPin = Color(0xFF2D3A42),
    actMute = Color(0xFF3F313D),
    actArchive = Color(0xFF574A38),
    onAct = Color(0xFFF8F1E7),
    scrim = Color(0x6B1B1611),
    wall = Color(0xFFF1DAB0),
    wallDot = Color(0x1022180A),
    cover = Color(0xFFD9B57A),
    coverLine = Color(0xFFBF985B),
    person = listOf(
        Color(0xFF966243) to Color(0xFFFFFFFF),
        Color(0xFF4D7A37) to Color(0xFFFFFFFF),
        Color(0xFF47729D) to Color(0xFFFFFFFF),
        Color(0xFF367976) to Color(0xFFFFFFFF),
        Color(0xFFA85376) to Color(0xFFFFFFFF),
        Color(0xFF7B5EB7) to Color(0xFFFFFFFF),
    ),
    isDark = false,
)

val DawnColors = PhantomColors(
    canvas = Color(0xFFF7E3D8),
    surface = Color(0xFFFDF3EE),
    raised = Color(0xFFF0D8CB),
    overlay = Color(0xFFE8CCBD),
    line = Color(0xFFE9D2C6),
    lineStrong = Color(0xFFDABEAF),
    text = Color(0xFF24170F),
    text2 = Color(0xFF5E4438),
    text3 = Color(0xFF674D41),
    accent = Color(0xFFC27A16),
    accentHover = Color(0xFFCE8722),
    accentPress = Color(0xFFBD7614),
    accentText = Color(0xFF80430C),
    accentLine = Color(0xFFAE6E14),
    accentSoft = Color(0xFFF5DCCB),
    onAccent = Color(0xFF24170F),
    self = Color(0xFFF2CCB8),
    onSelf = Color(0xFF24170F),
    onSelf2 = Color(0xFF5E4438),
    success = Color(0xFF18703E),
    danger = Color(0xFFAE362B),
    warning = Color(0xFF7A5200),
    actPin = Color(0xFF34485A),
    actMute = Color(0xFF463F55),
    actArchive = Color(0xFF5E5850),
    onAct = Color(0xFFFFFFFF),
    scrim = Color(0x6B1B1611),
    wall = Color(0xFFF5E0D4),
    wallDot = Color(0x1024170F),
    cover = Color(0xFFDDBBA6),
    coverLine = Color(0xFFC69F88),
    person = listOf(
        Color(0xFF966243) to Color(0xFFFFFFFF),
        Color(0xFF4D7A37) to Color(0xFFFFFFFF),
        Color(0xFF47729D) to Color(0xFFFFFFFF),
        Color(0xFF367976) to Color(0xFFFFFFFF),
        Color(0xFFA85376) to Color(0xFFFFFFFF),
        Color(0xFF7B5EB7) to Color(0xFFFFFFFF),
    ),
    isDark = false,
)

val MorningColors = PhantomColors(
    canvas = Color(0xFFEDF0EA),
    surface = Color(0xFFF8FAF6),
    raised = Color(0xFFE2E7DE),
    overlay = Color(0xFFD7DDD2),
    line = Color(0xFFD8DED4),
    lineStrong = Color(0xFFC7CEC2),
    text = Color(0xFF161A16),
    text2 = Color(0xFF485049),
    text3 = Color(0xFF535B53),
    accent = Color(0xFFC27A16),
    accentHover = Color(0xFFCE8722),
    accentPress = Color(0xFFBD7614),
    accentText = Color(0xFF7F4C0A),
    accentLine = Color(0xFFB77315),
    accentSoft = Color(0xFFF0E8D7),
    onAccent = Color(0xFF1E1A15),
    self = Color(0xFFDBE4D6),
    onSelf = Color(0xFF161A16),
    onSelf2 = Color(0xFF485049),
    success = Color(0xFF18703E),
    danger = Color(0xFFB3382C),
    warning = Color(0xFF7A5200),
    actPin = Color(0xFF34485A),
    actMute = Color(0xFF463F55),
    actArchive = Color(0xFF5E5850),
    onAct = Color(0xFFFFFFFF),
    scrim = Color(0x6B1B1611),
    wall = Color(0xFFEAEEE7),
    wallDot = Color(0x10161A16),
    cover = Color(0xFFC9D0BF),
    coverLine = Color(0xFFAEB8A2),
    person = listOf(
        Color(0xFF966243) to Color(0xFFFFFFFF),
        Color(0xFF4D7A37) to Color(0xFFFFFFFF),
        Color(0xFF47729D) to Color(0xFFFFFFFF),
        Color(0xFF367976) to Color(0xFFFFFFFF),
        Color(0xFFA85376) to Color(0xFFFFFFFF),
        Color(0xFF7B5EB7) to Color(0xFFFFFFFF),
    ),
    isDark = false,
)

/** Sun windows: elevation° to stop, linear between neighbours; a repeated degree is the polarity flip. */
object SunWindows {
    val rising: List<Pair<Double, String>> = listOf(-8.0 to "night", -3.0 to "dusk", -3.0 to "dawn", 10.0 to "dawn", 14.0 to "morning", 21.0 to "morning", 25.0 to "day")
    val setting: List<Pair<Double, String>> = listOf(-14.0 to "night", -8.0 to "dusk", 0.0 to "dusk", 0.0 to "golden", 12.0 to "day")
    const val WINTER_MARGIN: Double = 5.0
}

/** Appearance › Accent: only these roles change; the first entry is the palette's own. */
@Immutable
data class AccentTone(
    val accent: Color,
    val accentHover: Color,
    val accentPress: Color,
    val accentText: Color,
    val accentLine: Color,
    val onAccent: Color,
)

@Immutable
data class PhantomAccent(val id: String, val night: AccentTone, val day: AccentTone)

val PhantomAccents = listOf(
    PhantomAccent(
        "amber",
        AccentTone(accent = Color(0xFFE2A048), accentHover = Color(0xFFE8AE60), accentPress = Color(0xFFCF8D36), accentText = Color(0xFFE8AE60), accentLine = Color(0xFFE2A048), onAccent = Color(0xFF1B1408)),
        AccentTone(accent = Color(0xFFC27A16), accentHover = Color(0xFFCE8722), accentPress = Color(0xFFBD7614), accentText = Color(0xFF84510D), accentLine = Color(0xFFA76811), onAccent = Color(0xFF1E1A15)),
    ),
    PhantomAccent(
        "sage",
        AccentTone(accent = Color(0xFF8DC4A2), accentHover = Color(0xFFA3D1B4), accentPress = Color(0xFF76B08C), accentText = Color(0xFF97CBAB), accentLine = Color(0xFF8DC4A2), onAccent = Color(0xFF0E1A12)),
        AccentTone(accent = Color(0xFF2E6A4A), accentHover = Color(0xFF275C40), accentPress = Color(0xFF214F37), accentText = Color(0xFF285F42), accentLine = Color(0xFF2E6A4A), onAccent = Color(0xFFF6FBF7)),
    ),
    PhantomAccent(
        "sea",
        AccentTone(accent = Color(0xFF86BEDC), accentHover = Color(0xFF9DCBE4), accentPress = Color(0xFF6FAACB), accentText = Color(0xFF90C4E0), accentLine = Color(0xFF86BEDC), onAccent = Color(0xFF0B1820)),
        AccentTone(accent = Color(0xFF1E5F88), accentHover = Color(0xFF1A5277), accentPress = Color(0xFF164667), accentText = Color(0xFF1B5A80), accentLine = Color(0xFF1E5F88), onAccent = Color(0xFFF5FAFD)),
    ),
    PhantomAccent(
        "iris",
        AccentTone(accent = Color(0xFFADA8EC), accentHover = Color(0xFFBFBBF1), accentPress = Color(0xFF9792DE), accentText = Color(0xFFB5B0EE), accentLine = Color(0xFFADA8EC), onAccent = Color(0xFF15132A)),
        AccentTone(accent = Color(0xFF4E489E), accentHover = Color(0xFF433E8C), accentPress = Color(0xFF39347A), accentText = Color(0xFF4A4498), accentLine = Color(0xFF4E489E), onAccent = Color(0xFFF8F7FD)),
    ),
    PhantomAccent(
        "rose",
        AccentTone(accent = Color(0xFFE8A0A0), accentHover = Color(0xFFEEB3B3), accentPress = Color(0xFFDA8888), accentText = Color(0xFFEBA8A8), accentLine = Color(0xFFE8A0A0), onAccent = Color(0xFF2A1212)),
        AccentTone(accent = Color(0xFF9C3A43), accentHover = Color(0xFF8A3139), accentPress = Color(0xFF792A31), accentText = Color(0xFF94363F), accentLine = Color(0xFF9C3A43), onAccent = Color(0xFFFFF8F8)),
    ),
    PhantomAccent(
        "graphite",
        AccentTone(accent = Color(0xFFD9D4CC), accentHover = Color(0xFFE6E2DB), accentPress = Color(0xFFC4BFB7), accentText = Color(0xFFD9D4CC), accentLine = Color(0xFFD9D4CC), onAccent = Color(0xFF141210)),
        AccentTone(accent = Color(0xFF3D3934), accentHover = Color(0xFF33302B), accentPress = Color(0xFF2A2723), accentText = Color(0xFF3D3934), accentLine = Color(0xFF3D3934), onAccent = Color(0xFFFBF8F3)),
    ),
)

val LocalPhantomColors = staticCompositionLocalOf { NightColors }

/** Pass FontFamily(Font(R.font.manrope_regular, FontWeight.Normal), …) from core-design. */
class PhantomType(sans: FontFamily) {
    val display = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = -0.01.em)
    val title1 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.em)
    val title2 = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.em)
    val headline = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.em)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.em)
    val callout = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.em)
    val subhead = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.em)
    val footnote = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.em)
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em)
}

object PhantomSpace {
    val s0 = 0.dp
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
    val s16 = 64.dp
    val s0_5 = 2.dp
    val gutterPhone = 16.dp
    val gutterDesktop = 24.dp
    val rowV = 10.dp
    val touchMin = 44.dp
    val touchMinAndroid = 48.dp
}

object PhantomRadius {
    val chip = 8.dp
    val control = 12.dp
    val card = 20.dp
    val sheet = 28.dp
    val composer = 24.dp
    val bubble = 16.dp
    val bubbleTail = 8.dp
    // full → CircleShape
}

object PhantomSize {
    val iconS = 16.dp
    val iconM = 20.dp
    val iconL = 24.dp
    val iconStroke = 1.75f
    val avatarXs = 24.dp
    val avatarS = 32.dp
    val avatarM = 40.dp
    val avatarL = 52.dp
    val avatarXl = 88.dp
    val topbarPhone = 56.dp
    val topbarDesktop = 52.dp
    val bottomnavPhone = 64.dp
    val listrow = 72.dp
    val listrowCompact = 60.dp
    val buttonL = 48.dp
    val buttonM = 40.dp
    val buttonS = 32.dp
    val input = 48.dp
    val composerMin = 44.dp
    val bubbleMaxDesktop = 560.dp
    val feedMaxDesktop = 760.dp
    val sidebarDesktop = 340.dp
}

object PhantomMotion {
    const val instantMs = 90
    const val quickMs = 150
    const val standardMs = 240
    const val emphasizedMs = 360
    const val exitMs = 180
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    const val SHEET_DAMPING = 0.9f
    const val SHEET_STIFFNESS = 420f
    const val PRESS_DAMPING = 0.90f
    const val PRESS_STIFFNESS = 700f
    const val LAYER_DAMPING = 0.86f
    const val LAYER_STIFFNESS = 400f
    const val FLIGHT_DAMPING = 0.78f
    const val FLIGHT_STIFFNESS = 260f
    const val SNAP_DAMPING = 0.75f
    const val SNAP_STIFFNESS = 180f
    const val BREATH_DAMPING = 0.85f
    const val BREATH_STIFFNESS = 120f
}
