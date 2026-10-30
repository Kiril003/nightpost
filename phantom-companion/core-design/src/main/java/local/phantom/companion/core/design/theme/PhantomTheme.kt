package local.phantom.companion.core.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import local.phantom.companion.core.design.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.createFontFamilyResolver
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomColors

/**
 * `PROTOTYPE_ETALON.html` `.msg.me` — the operator's seal is a **wash of the
 * accent over the page**, not a filled block: `rgba(amber,.2)→rgba(amber,.1)`
 * behind `1px solid rgba(amber,.28)`. Both alphas live here so the fill a
 * renderer paints and the fill [PhantomThemeSpec.userBubbleBg] measures ink
 * against can never be two different colours.
 */
const val USER_SEAL_ALPHA: Float = 0.15f
const val USER_SEAL_BORDER_ALPHA: Float = 0.28f

/**
 * How much amber an emphasised surface is allowed to put on its edge.
 *
 * The etalon's whole amber-border vocabulary is fractional: `.18` on the
 * proactive card, `.28` on the operator's seal, `.3` on the dossier, `.4` on
 * hover, `.45` on an active chip or tile. There is exactly one full-strength
 * amber border in the document and it is 2px around a 26px turn marker. So
 * this is the ceiling, not a default: amber at the edge of something large is
 * a signal only while it stays a fraction of itself.
 */
const val ACCENT_BORDER_ALPHA: Float = 0.45f

/**
 * How far a disabled control's ink recedes. Material's 0.38 puts the light
 * palettes at 2.2:1 — a label that is off should still be *readable* on a
 * screen held at arm's length in daylight. 0.55 holds the 3:1 floor on every
 * palette; `ContrastFirewallTest` proves it.
 */
const val DISABLED_INK_ALPHA: Float = 0.55f

const val CODE_TYPE_BLEND: Float = 0.38f
const val CODE_COMMENT_ALPHA: Float = 0.62f

/** See [PhantomThemeSpec.codeSyntax]. */
data class CodeSyntaxColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
)

/** The two inks anything drawn ON a filled accent may use: `onAccent` and «Ніч»'s own text. */
private val AccentInkDark = NightColors.onAccent
private val AccentInkLight = NightColors.text

/** Legible ink for text/icons drawn directly on this color, any theme. */
fun Color.onAccentInk(): Color {
    // `luminance() > 0.5` is a LINEAR-light test masquerading as a perceptual
    // one. Mid-amber (#E89B3C, DawnBloom's accent) measures 0.40 and was
    // handed the light ink — 2.05:1, unreadable. Ask which ink actually wins.
    val l = luminance()
    fun against(ink: Color): Float {
        val a = maxOf(l, ink.luminance())
        val b = minOf(l, ink.luminance())
        return (a + 0.05f) / (b + 0.05f)
    }
    return if (against(AccentInkDark) >= against(AccentInkLight)) AccentInkDark else AccentInkLight
}

fun Modifier.saturation(saturation: Float): Modifier = this.drawWithContent {
    val colorMatrix = ColorMatrix().apply { setToSaturation(saturation) }
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(colorMatrix) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
}

/**
 * The OLD-spec face of the theme, kept as a façade over the v1.2 tokens.
 *
 * Since the native theme bridge (03.10) every field of every palette below is
 * a [local.phantom.companion.core.design.tokens.PhantomColors] token — the
 * table lives beside [AmberNight]. ~200 screens still read
 * `LocalPhantomTheme.current.glassPanel` and friends; they now get «Ніч» /
 * «День» through this object instead of the brown/cream/lavender palettes
 * they were written against, so a screen that has not been migrated yet no
 * longer looks like a different product from one that has.
 *
 * New code reads [local.phantom.companion.core.design.tokens.LocalPhantomColors]
 * and [PhantomText] directly; this class only shrinks from here.
 */
data class PhantomThemeSpec(
    val id: String,
    /**
     * The brand amber. A FILL — the send button, the orb's core, a selected
     * chip, an edge at a fraction of itself. It is **not ink**: as a word on a
     * light palette it reads 1.6:1, and the light palettes are three of the
     * five. When the accent has to be read rather than seen, that is
     * [accentSharp], which every palette tunes to survive its own ground and
     * which `ContrastFirewallTest` measures as ink.
     */
    val primary: Color,
    val primarySoft: Color,
    val primaryDeep: Color,
    val coral: Color,
    val coralDeep: Color,
    val surfaceBase: Color,
    val surfaceDeep: Color,
    val glassPanel: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val glowPrimary: Color,
    val ink: Color,
    val inkSecondary: Color,
    /** The accent at ink weight — headings, kickers, a focused control. */
    val accentSharp: Color,
    val inkStrong: Color,
    // Phantom v3.dc.html additions:
    /** The phase's `--ok` token — done-state glyphs, positive readouts. */
    val success: Color,
    /**
     * Ink for the operator's own words, drawn on [userBubbleBg].
     * `ContrastFirewallTest` holds this pair at WCAG AA on every palette.
     */
    val userBubbleFg: Color,
    /** Палітра точки сонця ([SunLook]); null — Ніч чи День D за полярністю. */
    val palette: PhantomColors? = null,
    /** Кадр сонця, з якого ця палітра; null — ручна тема або полюс. */
    val sun: SunLook? = null,
) {
    /**
     * What the operator's seal actually resolves to on screen — the accent at
     * [USER_SEAL_ALPHA] over the page it sits on. Derived, never stored: the
     * stored token used to be a solid ink block from an older prototype that
     * nothing ever drew, so [userBubbleFg] was contrast-checked against a
     * fiction while the real fill went unmeasured.
     */
    val userBubbleBg: Color
        get() = primary.copy(alpha = USER_SEAL_ALPHA).compositeOver(surfaceDeep)

    /** A control that is off, not gone. See [DISABLED_INK_ALPHA]. */
    val inkDisabled: Color
        get() = ink.copy(alpha = DISABLED_INK_ALPHA)

    /**
     * Полярність палітри — з самої поверхні, не з id: DuskEmber теж темний.
     * Одне оголошення на застосунок, бо міст у веб питає те саме («day» чи
     * «night»), а друга копія цього порогу розійшлася б із першою.
     */
    val isDark: Boolean get() = surfaceBase.luminance() < DARK_SURFACE_LUMINANCE

    /**
     * The four hues a code listing is allowed. Derived from this palette's own
     * ink-weight tokens rather than stored, because the brief's literal
     * `#C39B5C / #9DCC9F / #E8B770` were tuned on the dark ground and land at
     * roughly 2:1 on the three light palettes — a syntax scheme that is a
     * decoration on one page and unreadable on the next.
     */
    val codeSyntax: CodeSyntaxColors
        get() = CodeSyntaxColors(
            keyword = accentSharp,
            type = androidx.compose.ui.graphics.lerp(accentSharp, inkStrong, CODE_TYPE_BLEND),
            string = success,
            comment = inkSecondary.copy(alpha = CODE_COMMENT_ALPHA),
        )

    /**
     * Was the deep-night comfort morph: from 22:00 to 06:00 it pulled ink and
     * amber toward the ground — but only on screens that read THIS spec. The
     * v1.2 tokens ([local.phantom.companion.core.design.tokens.LocalPhantomColors])
     * and the Material scheme stayed at full strength, so every night the old
     * and the new halves of the app drew two different inks side by side.
     * One palette, one ink: the symbol stays (callers compile), the morph is
     * gone. «Ніч» is already tuned for the dark.
     */
    @Suppress("UNUSED_PARAMETER")
    fun morphForNight(isNight: Boolean): PhantomThemeSpec = this
}

// ── The bridge: every old-spec field is a v1.2 token ────────────────────
//
// | spec field     | token        | spec field   | token       |
// |----------------|--------------|--------------|-------------|
// | primary        | accent       | glassBorder  | line        |
// | primarySoft    | accentPress  | glassHighl.  | Transparent |
// | primaryDeep    | accentText   | glowPrimary  | Transparent |
// | coral          | danger       | ink          | text        |
// | coralDeep      | danger       | inkSecondary | text2       |
// | surfaceBase    | canvas       | accentSharp  | accentText  |
// | surfaceDeep    | surface      | inkStrong    | text        |
// | glassPanel     | raised       | success      | success     |
// | userBubbleFg   | text         |              |             |
//
// The glow and the lit-from-above highlight have no token on purpose: v1.2
// is flat («no glow, no gradient», PHANTOM_DESIGN_SYSTEM.md §9.3.4). They are
// Transparent rather than deleted so the ~200 screens that still draw them
// compile and draw nothing.
//
// PhantomThemeAuditTest and PhantomThemeBridgeTest pin this table.

/** «Ніч» — the product's face. */
val AmberNight = PhantomThemeSpec(
    id = "amber-night",
    primary = NightColors.accent,
    primarySoft = NightColors.accentPress,
    primaryDeep = NightColors.accentText,
    coral = NightColors.danger,
    coralDeep = NightColors.danger,
    surfaceBase = NightColors.canvas,
    surfaceDeep = NightColors.surface,
    glassPanel = NightColors.raised,
    glassBorder = NightColors.line,
    glassHighlight = Color.Transparent,
    glowPrimary = Color.Transparent,
    ink = NightColors.text,
    inkSecondary = NightColors.text2,
    accentSharp = NightColors.accentText,
    inkStrong = NightColors.text,
    success = NightColors.success,
    userBubbleFg = NightColors.text,
)

/** The bridge table above over any token set — the sun stops use it. */
fun specFrom(id: String, c: PhantomColors) = PhantomThemeSpec(
    id = id,
    primary = c.accent,
    primarySoft = c.accentPress,
    primaryDeep = c.accentText,
    coral = c.danger,
    coralDeep = c.danger,
    surfaceBase = c.canvas,
    surfaceDeep = c.surface,
    glassPanel = c.raised,
    glassBorder = c.line,
    glassHighlight = Color.Transparent,
    glowPrimary = Color.Transparent,
    ink = c.text,
    inkSecondary = c.text2,
    accentSharp = c.accentText,
    inkStrong = c.text,
    success = c.success,
    userBubbleFg = c.text,
)

/** «День» — the same table over [DayColors]. */
val DayClear = PhantomThemeSpec(
    id = "day-clear",
    primary = DayColors.accent,
    primarySoft = DayColors.accentPress,
    primaryDeep = DayColors.accentText,
    coral = DayColors.danger,
    coralDeep = DayColors.danger,
    surfaceBase = DayColors.canvas,
    surfaceDeep = DayColors.surface,
    glassPanel = DayColors.raised,
    glassBorder = DayColors.line,
    glassHighlight = Color.Transparent,
    glowPrimary = Color.Transparent,
    ink = DayColors.text,
    inkSecondary = DayColors.text2,
    accentSharp = DayColors.accentText,
    inkStrong = DayColors.text,
    success = DayColors.success,
    userBubbleFg = DayColors.text,
)

// The other three names are ALIASES with their own ids — not palettes. The id
// is what `dayPhase()` matches on (DawnBloom → "dawn", DuskEmber → "dusk") and
// what the web messenger is told, so an alias sharing its parent's id would
// make every day palette report dawn. Colours are the parent's, one for one.

/** Pre-QH2 name of the light palette; `WalletActivity` and old prefs still say it. */
val SunriseWarm = DayClear.copy(id = "sunrise-warm")

/** Dawn — the day palette under the dawn phase id. */
val DawnBloom = DayClear.copy(id = "dawn-bloom")

/** Dusk — the night palette under the dusk phase id. */
val DuskEmber = AmberNight.copy(id = "dusk-ember")

val LocalPhantomTheme = compositionLocalOf { AmberNight }

@Composable
fun PhantomTheme(
    spec: PhantomThemeSpec = AmberNight,
    /** ТВІД T4 — operator's global text-size dial; see [phantomTypography]. */
    fontScale: Float = FONT_SCALE_DEFAULT,
    /** Профіль › Вигляд › Акцент ([PhantomAccents] id). */
    accent: String = ACCENT_DEFAULT,
    content: @Composable () -> Unit,
) {
    val colors = (spec.palette ?: if (spec.isDark) NightColors else DayColors).withAccent(accent)
    val target = spec.withAccent(colors)
    // LUX-6 — a day-phase boundary (dawn→day→dusk→night) crossfades over
    // 600ms instead of snapping: every colour lerps to the next spec while
    // the id switches immediately (id-keyed consumers stay correct).
    val spec = animateSpecColors(target)

    // Polarity from the actual surface, not the id — DuskEmber is a dark
    // palette too. The v1.2 token set follows it, and the Material scheme is
    // DERIVED from that token set (§9.3), never written out a second time:
    // 11 files open a stock AlertDialog, 5 a ModalBottomSheet, 2 a
    // DropdownMenu, and every one of them drew lavender-on-cream because the
    // scheme carried 7 of its 36 roles and Material invented the other 29.
    val materialColors = phantomColorScheme(colors)
    // Μ5 — the whole tree reads the operator's animator scale from one place.
    // The operator's text-size dial, applied ONCE, to the density every `sp`
    // in the tree is resolved against.
    //
    // It used to be multiplied in three places — the Material roles, the four
    // PhantomTextRoles, and nowhere else — so the 600-odd screens that write
    // `fontSize = 13.sp` directly ignored the dial completely. Somebody who
    // turns text up because he cannot read 11sp got 11sp on almost the whole
    // app. Density is where Compose intends this to live, and from here a raw
    // `.sp` obeys it too.
    val scale = fontScale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
    val base = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(
        density = base.density,
        fontScale = base.fontScale * scale,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPhantomTheme provides spec,
        LocalPhantomColors provides colors,
        LocalPhantomFontScale provides scale,
        androidx.compose.ui.platform.LocalDensity provides scaledDensity,
        local.phantom.companion.core.design.motion.LocalMotionScale provides
            local.phantom.companion.core.design.motion.rememberSystemMotionScale(),
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = phantomTypography(),
            content = content,
        )
    }
}

/**
 * LUX-6 — lerps every colour of the active spec toward [target] over
 * 600ms. Steady state is exactly [target] (the animations settle), so
 * PhantomThemeAuditTest's pinned values still hold.
 */
@Composable
private fun animateSpecColors(target: PhantomThemeSpec): PhantomThemeSpec {
    val spec600 = androidx.compose.animation.core.tween<Color>(durationMillis = 600)

    @Composable
    fun c(value: Color, label: String): Color =
        androidx.compose.animation.animateColorAsState(value, spec600, label = label).value

    return target.copy(
        primary = c(target.primary, "th-primary"),
        primarySoft = c(target.primarySoft, "th-primarySoft"),
        primaryDeep = c(target.primaryDeep, "th-primaryDeep"),
        coral = c(target.coral, "th-coral"),
        coralDeep = c(target.coralDeep, "th-coralDeep"),
        surfaceBase = c(target.surfaceBase, "th-surfaceBase"),
        surfaceDeep = c(target.surfaceDeep, "th-surfaceDeep"),
        glassPanel = c(target.glassPanel, "th-glassPanel"),
        glassBorder = c(target.glassBorder, "th-glassBorder"),
        glassHighlight = c(target.glassHighlight, "th-glassHighlight"),
        glowPrimary = c(target.glowPrimary, "th-glowPrimary"),
        ink = c(target.ink, "th-ink"),
        inkSecondary = c(target.inkSecondary, "th-inkSecondary"),
        accentSharp = c(target.accentSharp, "th-accentSharp"),
        inkStrong = c(target.inkStrong, "th-inkStrong"),
        success = c(target.success, "th-success"),
        userBubbleFg = c(target.userBubbleFg, "th-userBubbleFg"),
    )
}

/**
 * The Material scheme, DERIVED from a v1.2 token set — PHANTOM_DESIGN_SYSTEM.md
 * §9.3. Every role a stock M3 component can reach is set here; the ones §9.3
 * does not name come from the nearest token rather than from Material's
 * baseline purple, which is where the lavender AlertDialog came from.
 *
 * One deviation from §9.3, on purpose: `surface` is `canvas`, not `surface`.
 * `MainActivity`'s root `Surface(Modifier.fillMaxSize())` paints the whole
 * app's ground from `colorScheme.surface`, and the ground is `canvas`. The old
 * mapping (`surfaceDeep`) was the deepest ground for the same reason.
 *
 * Pure and `internal` so `PhantomThemeBridgeTest` can read it on the JVM.
 */
internal fun phantomColorScheme(c: PhantomColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.text,
        inversePrimary = c.accentText,
        secondary = c.text2,
        onSecondary = c.canvas,
        secondaryContainer = c.raised,
        onSecondaryContainer = c.text,
        tertiary = c.text2,
        onTertiary = c.canvas,
        tertiaryContainer = c.raised,
        onTertiaryContainer = c.text,
        background = c.canvas,
        onBackground = c.text,
        surface = c.canvas,
        onSurface = c.text,
        surfaceVariant = c.raised,
        onSurfaceVariant = c.text2,
        surfaceTint = Color.Transparent,
        inverseSurface = c.text,
        inverseOnSurface = c.canvas,
        error = c.danger,
        onError = c.onAct,
        errorContainer = c.raised,
        onErrorContainer = c.danger,
        outline = c.lineStrong,
        outlineVariant = c.line,
        scrim = c.scrim,
        surfaceBright = c.overlay,
        surfaceDim = c.canvas,
        surfaceContainerLowest = c.canvas,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.raised,
        // M3's AlertDialog container. A dialog sits one step above a sheet.
        surfaceContainerHigh = c.overlay,
        surfaceContainerHighest = c.overlay,
    )
}

/**
 * One family: **Manrope** for every word the app draws (PHANTOM_DESIGN_SYSTEM.md
 * §3.1). JetBrains Mono survives at one weight, for code blocks only.
 *
 * The Material slots follow §9.3 where nothing pins them otherwise:
 *
 *   displayLarge   36sp ExtraBold lsp -0.5sp   (pinned by TypographyComplianceTest)
 *   displayMedium  32sp ExtraBold lsp -0.4sp   (pinned)
 *   displaySmall   = display  28/34 Bold
 *   headlineLarge  20sp Bold      lsp -0.3sp   (pinned)
 *   headlineMedium 18sp Bold                   (pinned)
 *   headlineSmall  = title2   18/24 SemiBold   (M3 AlertDialog's title slot)
 *   titleLarge     = title1   22/28 Bold
 *   titleMedium    = title2   18/24 SemiBold
 *   titleSmall     = headline 16/22 SemiBold
 *   bodyLarge      16sp Regular                (pinned)
 *   bodyMedium     14sp Regular                (pinned)
 *   bodySmall      = footnote 13/18 Regular
 *   labelLarge     = subhead  14/20 Medium, no tracking  (every M3 button)
 *   labelMedium    = caption  12/16 Medium, +0.01em
 *   labelSmall     = caption  12/16 Medium, +0.01em
 *
 * No slot is mono and none is tracked. labelLarge used to be JetBrains Mono at
 * 4sp of tracking, which is why every stock TextButton read «с к а с у в а т и».
 */
// Manrope (OFL, assets/licences/OFL-Manrope.txt) — мова форми 26.09, спільна з месенджером.
val PhantomSans: FontFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/**
 * Every weight the app actually draws.
 *
 * Six .ttf files, and TypefaceCompat parses each one the first time a piece of
 * text in that weight is laid out — ON THE MAIN THREAD, because a resource
 * Font is Blocking by default. That is what «Input dispatching timed out» on a
 * cold launch is made of: the operator's first taps queue up while the thread
 * sits inside StringFactory.newStringFromBytes. [warmTypefaces] parses them
 * once, off that thread, before the first frame asks.
 *
 * Resolving a weight the family does not declare is harmless — it maps to the
 * nearest — but missing one that IS declared leaves that file for the main
 * thread to find later.
 */
val PRELOADED_WEIGHTS: List<FontWeight> = listOf(
    FontWeight.Light,
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold,
    FontWeight.ExtraBold,
)

val PhantomText: local.phantom.companion.core.design.tokens.PhantomType =
    local.phantom.companion.core.design.tokens.PhantomType(PhantomSans)

/**
 * Code only — a code block in an assistant answer (§3.1: «JetBrains Mono 400,
 * never for times, labels or headings»). One cut: the Light, Medium and Bold
 * files and the Playfair serif are gone with the voices that used them.
 */
val PhantomMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
)

/**
 * Parses every typeface off the main thread.
 *
 * Called from Application.onCreate rather than from the theme: a
 * LaunchedEffect inside the composition runs AFTER the first frame has already
 * been laid out, which is the exact frame that pays for the parse.
 *
 * The cache TypefaceCompat keeps is process-wide, so warming through any
 * resolver turns the later main-thread load into a lookup.
 */
fun warmTypefaces(context: android.content.Context) {
    val resolver = createFontFamilyResolver(context)
    for (family in listOf(PhantomSans, PhantomMono)) {
        for (weight in PRELOADED_WEIGHTS) {
            runCatching { resolver.resolve(family, weight) }
        }
    }
}

/**
 * Legible bounds for the operator's type-size preference. Below 0.85
 * the 12sp caption roles stop being readable at arm's length;
 * above 1.30 the display headlines start wrapping the greeting on a
 * narrow phone. [FONT_SCALE_DEFAULT] is the tuned baseline every pinned
 * value in TypographyComplianceTest measures.
 */
const val FONT_SCALE_MIN: Float = 0.85f
const val FONT_SCALE_MAX: Float = 1.30f
const val FONT_SCALE_DEFAULT: Float = 1.0f

/**
 * The dial, where [PhantomTextRoles] can reach it. [phantomTypography] scales
 * MaterialTheme's roles, but PhantomKicker / PhantomCaption / PhantomDataText /
 * PhantomProse set their own `sp` and never saw it — so the four voices the app
 * actually speaks in, at 74 call sites, were deaf to a setting the ViewModel
 * describes as moving "the whole UI". Provided already clamped.
 */
val LocalPhantomFontScale = compositionLocalOf { FONT_SCALE_DEFAULT }

/**
 * ТВІД T4 — the operator's global text-size dial (Settings → APPEARANCE,
 * matching the reference «Body/Heading size» sliders). [scale] multiplies
 * every role's size and line-height uniformly, so the whole system grows
 * together and the type hierarchy is preserved; tracking (letter-spacing)
 * is an optical constant and is left unscaled. `scale = 1f` (the default)
 * yields exactly the values pinned by TypographyComplianceTest.
 */
internal fun phantomTypography(scale: Float = FONT_SCALE_DEFAULT): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography()
    val sans = PhantomSans
    val k = scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
    fun androidx.compose.ui.unit.TextUnit.s() = this * k
    /** A v1.2 role at the dial: size and line box scale, tracking does not. */
    fun androidx.compose.ui.text.TextStyle.at() =
        copy(fontSize = fontSize.s(), lineHeight = lineHeight.s())
    return base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = sans,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 36.sp.s(),
            letterSpacing = (-0.5).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = sans,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp.s(),
            letterSpacing = (-0.4).sp,
        ),
        displaySmall = PhantomText.display.at(),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp.s(),
            letterSpacing = (-0.3).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp.s(),
        ),
        headlineSmall = PhantomText.title2.at(),
        titleLarge = PhantomText.title1.at(),
        titleMedium = PhantomText.title2.at(),
        titleSmall = PhantomText.headline.at(),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp.s(),
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp.s(),
        ),
        bodySmall = PhantomText.footnote.at(),
        labelLarge = PhantomText.subhead.at(),
        labelMedium = PhantomText.caption.at(),
        labelSmall = PhantomText.caption.at(),
    )
}

private const val DARK_SURFACE_LUMINANCE = 0.5f
