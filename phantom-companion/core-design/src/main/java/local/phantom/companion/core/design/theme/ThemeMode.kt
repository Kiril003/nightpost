package local.phantom.companion.core.design.theme

/**
 * QH2 — operator-selected theme preference.
 *
 * Why an enum instead of `PhantomThemeSpec` directly: the spec is the
 * resolved colour palette, but the *preference* the operator stores
 * is "living" / "always light" / "always dark" / "follow the system".
 * Living needs the sun and System needs a runtime hint
 * (isSystemInDarkTheme), which a saved spec cannot carry forward
 * across boots and OS dark-mode toggles.
 *
 * [Living] is the DEFAULT — cream by day, warm dark by evening. This one
 * has been decided both ways, so both arguments stay here.
 *
 * FOR Dark, the position that shipped until 2026-08-24: AmberNight is the
 * product's face — near-black ground, amber signal accent, the approved
 * prototype one for one. Under Living, every operator between 09:00 and
 * 17:00 saw a cream notes app that shared nothing with the thing we
 * designed; an instrument does not change its skin by the clock.
 *
 * FOR Living, the owner's call on 2026-08-24 and what ships now: the phone
 * is held in daylight far more than the prototype was ever viewed in it, a
 * near-black panel under the sun is the legibility problem rather than the
 * answer to it, and the desktop build has been cream by day all along —
 * one product, one face. Dark stays one tap away for anyone who wants the
 * prototype's look around the clock.
 */
enum class ThemeMode {
    Living,
    Light,
    Dark,
    System,
}

/**
 * Під яким світлом стоїть людина — шість точок сонця для [ThemeMode.Living]
 * (варіант B, власник 28.10): ніч, світанок, ранок, полудень, золота година,
 * сутінки. Фазу рахує застосунок із висоти сонця (`LivingSun` в `:app`) через
 * [SunLook.at]; тут лише словник.
 *
 * [web] — рядок, яким фаза їде у веб-месенджер (`PhantomBridge.getThemePhase`).
 * [dark] — полярність: сутінки й ніч темні, решта світлі, рівно як
 * `themeOfPhase` у phantom-os/src/frontend/src/services/theme.ts (сторож —
 * LivingWebParityTest).
 */
enum class DayPhase(val web: String, val dark: Boolean) {
    Night("night", true),
    Dawn("dawn", false),
    Morning("morning", false),
    Day("day", false),
    Golden("golden", false),
    Dusk("dusk", true),
}

/** [ThemeMode.Living] на одній точці сонця, без переходу. */
fun resolveLivingTheme(phase: DayPhase): PhantomThemeSpec = resolveLivingTheme(SunLook.flat(phase))

/** [ThemeMode.Living] у кадрі сонця: полюси — канонічні Ніч і День, решта — палітра точки. */
fun resolveLivingTheme(look: SunLook): PhantomThemeSpec = when {
    look.isPole && look.from == DayPhase.Night -> AmberNight
    look.isPole -> DayClear
    else -> specFrom("sun-${look.phase.web}", look.colors).copy(palette = look.colors, sun = look)
}

/**
 * Pure resolver: given the operator's preference, the current
 * system-dark hint, and the phase of the day, return the colour spec the
 * renderer should use.
 *
 * - [ThemeMode.Living] → [resolveLivingTheme] of [phase] (the SUN, not the clock).
 * - [ThemeMode.Light]  → always [DayClear], the same day palette Living uses.
 * - [ThemeMode.Dark]   → always [AmberNight], regardless of system.
 * - [ThemeMode.System] → mirrors the OS-level dark-mode flag so a
 *   system-wide Night Mode toggle is reflected without entering
 *   PHANTOM Settings.
 */
fun resolveTheme(mode: ThemeMode, systemDark: Boolean, phase: DayPhase): PhantomThemeSpec =
    resolveTheme(mode, systemDark, SunLook.flat(phase))

fun resolveTheme(mode: ThemeMode, systemDark: Boolean, look: SunLook): PhantomThemeSpec =
    when (mode) {
        ThemeMode.Living -> resolveLivingTheme(look)
        ThemeMode.Light -> DayClear
        ThemeMode.Dark -> AmberNight
        ThemeMode.System -> if (systemDark) AmberNight else DayClear
    }

/**
 * The whole resolution in ONE place: [resolveTheme] plus the hour.
 *
 * The hour used to drive a deep-night comfort morph (22–06) that dimmed ink
 * and amber — on old-spec screens only, while the v1.2 tokens and the Material
 * scheme stayed at full strength. Since the native theme bridge
 * [PhantomThemeSpec.morphForNight] is the identity: «Ніч» is the night palette,
 * and there is no second, dimmer one for half the app. The signature stays so
 * the shell and the glance screen keep compiling.
 */
fun resolveThemeAt(
    mode: ThemeMode,
    systemDark: Boolean,
    phase: DayPhase,
    hourOfDay: Int,
): PhantomThemeSpec = resolveThemeAt(mode, systemDark, SunLook.flat(phase), hourOfDay)

fun resolveThemeAt(
    mode: ThemeMode,
    systemDark: Boolean,
    look: SunLook,
    hourOfDay: Int,
): PhantomThemeSpec {
    val h = ((hourOfDay % 24) + 24) % 24
    val base = resolveTheme(mode, systemDark, look)
    return base.morphForNight((h >= 22 || h < 6) && base.id == AmberNight.id)
}

/**
 * Фаза, яку несе вже РОЗВʼЯЗАНА палітра — для веба, що бачить лише палітру.
 * Ручні режими дають дві фази (Light → день, Dark → ніч), Living — шість.
 */
fun PhantomThemeSpec.dayPhase(): DayPhase = sun?.phase ?: when (id) {
    DawnBloom.id -> DayPhase.Dawn
    DuskEmber.id -> DayPhase.Dusk
    else -> if (isDark) DayPhase.Night else DayPhase.Day
}

/**
 * The SharedPreferences FILE the two keys below live in.
 *
 * It sits beside them on purpose (brief 03 defect #6): the name was written out
 * as a bare literal at three separate call sites, and a typo in any one of them
 * does not fail — it silently opens a *different, empty* prefs file. The
 * operator's theme would simply appear not to persist, with nothing to catch it
 * at compile time and nothing in the logs.
 */
const val THEME_PREFS_FILE: String = "phantom_theme_prefs"

/** Storage key used by SharedPreferences. */
const val THEME_MODE_PREF_KEY: String = "ui_theme_mode"

/** ТВІД T4 — storage key for the operator's text-size dial (a Float in
 *  [FONT_SCALE_MIN]..[FONT_SCALE_MAX]; see `phantomTypography`). */
const val FONT_SCALE_PREF_KEY: String = "ui_font_scale"

/**
 * Storage value migration:
 * - new format is the enum name (`Living` / `Light` / `Dark` / `System`)
 * - legacy values were the spec id (`sunrise-warm` / `amber-night`)
 *
 * @return the saved mode, or [ThemeMode.Living] (the default) when
 *   nothing is stored yet OR when the stored value is unrecognised
 *   garbage. Operators who explicitly chose Light/Dark/System keep
 *   their choice.
 */
fun parseStoredThemeMode(stored: String?): ThemeMode = when (stored) {
    // 11.09 тут стояла НІЧ, і стояла тоді правильно: «тему лучше одну на все
    // приложение» — рідні екрани йшли за сонцем і вдень світлішали, а
    // веб-месенджер у тому самому застосунку вмів лише нічну палітру. Одна
    // тема на застосунок — та, яку вміють ОБИДВІ половини.
    //
    // 15.09 причина відпала, і це перевірено, а не припущено: у веба тепер є
    // повна денна палітра (`styles/dayTheme.ts`, `:root[data-theme='day']` у
    // messenger.css), і міст її вже носить — `WebMessengerHost` пише
    // `container.webTheme` з розвʼязаної палітри композиції, а бандл читає її
    // через `getTheme()` і `window.__phantomTheme`. Обидві половини світлішають
    // разом, отже заперечення «денна половина не засвітиться» більше не
    // справджується.
    //
    // Власник 15.09: «застосунок зараз не як хамелеон по темі впродовж доби».
    // Повертаємо [Living]: світанок, день, присмерк, ніч. Ніч лишається одним
    // дотиком у Налаштуваннях для тих, хто хоче її цілодобово.
    null, "" -> ThemeMode.Living
    ThemeMode.Living.name -> ThemeMode.Living
    ThemeMode.Light.name -> ThemeMode.Light
    ThemeMode.Dark.name -> ThemeMode.Dark
    ThemeMode.System.name -> ThemeMode.System
    "sunrise-warm" -> ThemeMode.Light
    "amber-night" -> ThemeMode.Dark
    // Непізнаний рядок — це «людина нічого зрозумілого не обирала», тобто те
    // саме, що порожньо.
    else -> ThemeMode.Living
}

/**
 * What the app opens in: the QH2 key wins, the pre-QH2 `ui_theme` spec id is
 * the fallback, an empty store is the default.
 *
 * Pure on purpose — the caller does the SharedPreferences I/O and nothing
 * else, so the boot path (including which key wins) is provable off-device.
 */
fun startupThemeMode(storedMode: String?, legacyStored: String?): ThemeMode =
    parseStoredThemeMode(storedMode ?: legacyStored)
