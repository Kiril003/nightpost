package local.phantom.companion.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Міст рідної теми: старий `PhantomThemeSpec`, Material-схема й типографіка
 * Material беруть ОДНЕ джерело — токени v1.2 (`tokens/PhantomTokens.kt`).
 *
 * До мосту ~200 екранів, що читають `LocalPhantomTheme`, малювали коричневу ніч
 * і кремовий день, а стоковий AlertDialog — бузковий, бо схема несла 7 ролей із
 * 36. Усе нижче червоне на старому коді: AmberNight був #110E0B, а не «Ніч»
 * #0A0C10; `labelLarge` — JetBrains Mono з 4sp розрідження; Playfair лежав у
 * `res/font`.
 */
class PhantomThemeBridgeTest {

    /** The bridge table, as data: spec field → the token it must be. */
    private fun table(c: PhantomColors): List<Triple<String, (PhantomThemeSpec) -> Color, Color>> = listOf(
        Triple("primary", { s: PhantomThemeSpec -> s.primary }, c.accent),
        Triple("primarySoft", { s: PhantomThemeSpec -> s.primarySoft }, c.accentPress),
        Triple("primaryDeep", { s: PhantomThemeSpec -> s.primaryDeep }, c.accentText),
        Triple("coral", { s: PhantomThemeSpec -> s.coral }, c.danger),
        Triple("coralDeep", { s: PhantomThemeSpec -> s.coralDeep }, c.danger),
        Triple("surfaceBase", { s: PhantomThemeSpec -> s.surfaceBase }, c.canvas),
        Triple("surfaceDeep", { s: PhantomThemeSpec -> s.surfaceDeep }, c.surface),
        Triple("glassPanel", { s: PhantomThemeSpec -> s.glassPanel }, c.raised),
        Triple("glassBorder", { s: PhantomThemeSpec -> s.glassBorder }, c.line),
        Triple("glassHighlight", { s: PhantomThemeSpec -> s.glassHighlight }, Color.Transparent),
        Triple("glowPrimary", { s: PhantomThemeSpec -> s.glowPrimary }, Color.Transparent),
        Triple("ink", { s: PhantomThemeSpec -> s.ink }, c.text),
        Triple("inkSecondary", { s: PhantomThemeSpec -> s.inkSecondary }, c.text2),
        Triple("accentSharp", { s: PhantomThemeSpec -> s.accentSharp }, c.accentText),
        Triple("inkStrong", { s: PhantomThemeSpec -> s.inkStrong }, c.text),
        Triple("success", { s: PhantomThemeSpec -> s.success }, c.success),
        Triple("userBubbleFg", { s: PhantomThemeSpec -> s.userBubbleFg }, c.text),
    )

    /* (a) every palette is the token table, and the ids stay apart */

    @Test
    fun `every field of every palette is its v1_2 token`() {
        val palettes = listOf(
            AmberNight to NightColors,
            DuskEmber to NightColors,
            DayClear to DayColors,
            SunriseWarm to DayColors,
            DawnBloom to DayColors,
        )
        for ((spec, tokens) in palettes) {
            val rows = table(tokens)
            assertEquals("the table must cover all 17 colour fields", 17, rows.size)
            for ((field, read, token) in rows) {
                assertEquals("${spec.id}.$field", token, read(spec))
            }
        }
    }

    @Test
    fun `aliases keep their own ids - the web phase is read from the id`() {
        val ids = listOf(AmberNight, DuskEmber, DayClear, SunriseWarm, DawnBloom).map { it.id }
        assertEquals("five palettes, five ids: $ids", 5, ids.toSet().size)
        assertEquals(DayPhase.Dawn, DawnBloom.dayPhase())
        assertEquals(DayPhase.Dusk, DuskEmber.dayPhase())
        assertEquals(DayPhase.Day, DayClear.dayPhase())
        assertEquals(DayPhase.Day, SunriseWarm.dayPhase())
        assertEquals(DayPhase.Night, AmberNight.dayPhase())
    }

    /* (b) the Material scheme is derived from the same tokens */

    @Test
    fun `the Material scheme is derived from the tokens, night and day`() {
        for (c in listOf(NightColors, DayColors)) {
            val s = phantomColorScheme(c)
            val tag = if (c.isDark) "night" else "day"
            assertEquals("$tag surfaceContainer (sheets, cards)", c.raised, s.surfaceContainer)
            assertEquals("$tag surfaceContainerHigh (AlertDialog)", c.overlay, s.surfaceContainerHigh)
            assertEquals("$tag outline", c.lineStrong, s.outline)
            assertEquals("$tag outlineVariant", c.line, s.outlineVariant)
            assertEquals("$tag primary", c.accent, s.primary)
            assertEquals("$tag onPrimary", c.onAccent, s.onPrimary)
            assertEquals("$tag primaryContainer", c.accentSoft, s.primaryContainer)
            assertEquals("$tag error", c.danger, s.error)
            assertEquals("$tag background", c.canvas, s.background)
            // Deviation from §9.3, on purpose: MainActivity's root Surface paints
            // the app ground from colorScheme.surface, and the ground is canvas.
            assertEquals("$tag surface", c.canvas, s.surface)
            assertEquals("$tag onSurface", c.text, s.onSurface)
            assertEquals("$tag onSurfaceVariant", c.text2, s.onSurfaceVariant)
            assertEquals("$tag surfaceTint (no M3 tonal wash)", Color.Transparent, s.surfaceTint)
            assertEquals("$tag scrim", c.scrim, s.scrim)
        }
    }

    @Test
    fun `no Material role is left at its baseline purple`() {
        // M3 baseline: primary #6750A4 family, surfaces #FEF7FF / #141218 family.
        for (c in listOf(NightColors, DayColors)) {
            val s = phantomColorScheme(c)
            val tokens = buildSet {
                addAll(
                    listOf(
                        c.canvas, c.surface, c.raised, c.overlay, c.line, c.lineStrong, c.text,
                        c.text2, c.text3, c.accent, c.accentText, c.accentSoft, c.onAccent,
                        c.danger, c.onAct, c.scrim, Color.Transparent,
                    ),
                )
            }
            val roles = mapOf(
                "primary" to s.primary, "onPrimary" to s.onPrimary,
                "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
                "inversePrimary" to s.inversePrimary,
                "secondary" to s.secondary, "onSecondary" to s.onSecondary,
                "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
                "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
                "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
                "background" to s.background, "onBackground" to s.onBackground,
                "surface" to s.surface, "onSurface" to s.onSurface,
                "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
                "surfaceTint" to s.surfaceTint,
                "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
                "error" to s.error, "onError" to s.onError,
                "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer,
                "outline" to s.outline, "outlineVariant" to s.outlineVariant, "scrim" to s.scrim,
                "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
                "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainerLowest" to s.surfaceContainerLowest,
            )
            val strays = roles.filterValues { it !in tokens }.keys
            assertTrue("${if (c.isDark) "night" else "day"}: roles not on a token: $strays", strays.isEmpty())
        }
    }

    /* (c) no Material label slot is mono or tracked */

    private fun trackingEm(style: TextStyle): Float {
        val ls = style.letterSpacing
        return when {
            ls.isEm -> ls.value
            ls.isSp -> ls.value / style.fontSize.value
            else -> 0f
        }
    }

    @Test
    fun `label slots are Manrope with no letter-spacing beyond the caption's`() {
        val t = phantomTypography()
        for ((slot, style) in listOf("labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall)) {
            assertEquals("$slot family", PhantomSans, style.fontFamily)
            assertTrue(
                "$slot is tracked at ${trackingEm(style)}em — v1.2 has no letter-spaced labels",
                trackingEm(style) <= 0.01f + 1e-4f,
            )
        }
        // Every M3 button label is labelLarge: it is `subhead`, not a mono kicker.
        assertEquals(PhantomText.subhead.fontSize, t.labelLarge.fontSize)
        assertEquals(PhantomText.subhead.fontWeight, t.labelLarge.fontWeight)
    }

    @Test
    fun `no Material slot speaks mono`() {
        val t = phantomTypography()
        val slots = listOf(
            t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium,
            t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium,
            t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall,
        )
        slots.forEachIndexed { i, s -> assertEquals("slot #$i", PhantomSans, s.fontFamily) }
    }

    /* (d) the serif is gone, the crown speaks Manrope */

    @Test
    fun `the editorial crown is Manrope title2 and Playfair is out of the APK`() {
        val crown = editorialTitleStyle()
        assertEquals(PhantomSans, crown.fontFamily)
        assertEquals(PhantomText.title2.fontSize, crown.fontSize)
        assertEquals(PhantomText.title2.fontWeight, crown.fontWeight)

        val fonts = R.font::class.java.fields.map { it.name }.toSet()
        assertFalse("playfair_display.ttf is still bundled", "playfair_display" in fonts)
        for (cut in listOf("jetbrains_mono_light", "jetbrains_mono_medium", "jetbrains_mono_bold")) {
            assertFalse("$cut.ttf is still bundled — §3.1 keeps JetBrains Mono 400 only", cut in fonts)
        }
        assertTrue("code blocks still need JetBrains Mono 400", "jetbrains_mono_regular" in fonts)
    }

    /* the night morph no longer forks the old half of the app from the new */

    @Test
    fun `deep night draws the same palette as the evening`() {
        assertEquals(AmberNight, AmberNight.morphForNight(true))
        assertEquals(AmberNight, resolveThemeAt(ThemeMode.Dark, false, DayPhase.Night, 23))
        assertEquals(AmberNight, resolveThemeAt(ThemeMode.Living, false, DayPhase.Night, 3))
    }
}
