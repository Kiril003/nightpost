package local.phantom.companion.core.design.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QH2 + R0.4 — pin the theme resolvers + the legacy-pref migration so a
 * future "rename SunriseWarm to Sunrise2" or "drop the sunrise-warm
 * SharedPrefs key" doesn't silently revert operators to the wrong
 * scheme on next boot, and the Living day-cycle boundaries can't drift.
 */
class ThemeModeTest {

    /* ---- resolveLivingTheme — фаза сонця → палітра ---- */

    @Test
    fun `Living poles are the D palettes, the other stops carry their own`() {
        assertEquals(AmberNight, resolveLivingTheme(DayPhase.Night))
        assertEquals(DayClear, resolveLivingTheme(DayPhase.Day))
        // Варіант B (28.10): сутінки темні, світанок, ранок і золота — світлі (LivingWebParityTest).
        for (p in listOf(DayPhase.Dawn, DayPhase.Morning, DayPhase.Golden, DayPhase.Dusk)) {
            assertEquals(p.name, p.colors(), resolveLivingTheme(p).palette)
        }
        assertTrue(resolveLivingTheme(DayPhase.Dusk).isDark)
    }

    @Test
    fun `operator acceptance - warm-light by day, dark by night, whatever the OS says`() {
        assertEquals(DayClear, resolveTheme(ThemeMode.Living, systemDark = true, phase = DayPhase.Day))
        assertEquals(AmberNight, resolveTheme(ThemeMode.Living, systemDark = false, phase = DayPhase.Night))
    }

    @Test
    fun `phase polarity matches the palette it resolves to`() {
        // Старий веб знає лише day/night і отримує dark фази — вона мусить
        // збігатися з тим, що рідний бік справді малює.
        for (p in DayPhase.entries) assertEquals(p.name, p.dark, resolveLivingTheme(p).isDark)
    }

    @Test
    fun `the resolved palette names its own phase back`() {
        for (p in DayPhase.entries) assertEquals(p.name, p, resolveLivingTheme(p).dayPhase())
        assertEquals(DayPhase.Day, SunriseWarm.dayPhase())
        assertEquals(DayPhase.Night, AmberNight.morphForNight(true).dayPhase())
        assertEquals(listOf("night", "dawn", "morning", "day", "golden", "dusk"), DayPhase.entries.map { it.web })
    }

    /* ---- resolveTheme ---- */

    @Test
    fun `Light mode always returns the day palette regardless of system and sun`() {
        for (p in DayPhase.entries) {
            assertEquals(DayClear, resolveTheme(ThemeMode.Light, systemDark = false, phase = p))
            assertEquals(DayClear, resolveTheme(ThemeMode.Light, systemDark = true, phase = p))
        }
    }

    @Test
    fun `Dark mode always returns AmberNight regardless of system and sun`() {
        for (p in DayPhase.entries) {
            assertEquals(AmberNight, resolveTheme(ThemeMode.Dark, systemDark = false, phase = p))
        }
    }

    @Test
    fun `System mode follows the system dark hint`() {
        assertEquals(DayClear, resolveTheme(ThemeMode.System, systemDark = false, phase = DayPhase.Night))
        assertEquals(AmberNight, resolveTheme(ThemeMode.System, systemDark = true, phase = DayPhase.Day))
    }

    /* ---- resolveThemeAt — нічне помʼякшення ---- */

    @Test
    fun `resolveThemeAt is resolveTheme at every hour - no deep-night fork`() {
        // The 22–06 morph dimmed ink and amber on old-spec screens only; the
        // v1.2 tokens stayed at full strength, so the two halves of the app
        // drew two inks every night. Since the bridge (03.10) it is gone.
        assertEquals(DayClear, resolveThemeAt(ThemeMode.Light, false, DayPhase.Night, 23))
        assertEquals(DayClear, resolveThemeAt(ThemeMode.Light, false, DayPhase.Night, 3))
        assertEquals(AmberNight, resolveThemeAt(ThemeMode.Dark, false, DayPhase.Day, 21))
        assertEquals(AmberNight, resolveThemeAt(ThemeMode.Dark, false, DayPhase.Day, 22))
        assertEquals(AmberNight, resolveThemeAt(ThemeMode.Living, false, DayPhase.Night, 2))
        assertEquals(resolveLivingTheme(DayPhase.Dusk), resolveThemeAt(ThemeMode.Living, false, DayPhase.Dusk, 21))
        // Годину за межами 0..23 зводимо, а не падаємо.
        assertEquals(resolveThemeAt(ThemeMode.Dark, false, DayPhase.Night, 1), resolveThemeAt(ThemeMode.Dark, false, DayPhase.Night, 25))
    }

    /* ---- parseStoredThemeMode ---- */

    @Test
    fun `порожній вибір — це Living, бо денну палітру вміють обидві половини`() {
        // ТРЕТЄ рішення про той самий рядок, і кожне лишається тут, бо кожне
        // було правильним у свій день.
        //
        // 24.08 власник: свіже встановлення відкривається Living.
        // 12.09 відкочено на Dark. Підстава була справжня: рідні екрани йшли
        //       за сонцем, а веб-месенджер у тому самому APK мав ЛИШЕ нічну
        //       палітру — «за часом доби» означало два різні застосунки в
        //       одному вікні. Попередній варіант цього тесту прямо вимагав:
        //       почервоніти, якщо хтось поверне Living, НЕ ПОВЕРНУВШИ денної
        //       палітри вебові.
        // 15.09 умову того тесту виконано, і саме тому його переписано, а не
        //       обійдено. Перевірено в дереві веба: `styles/dayTheme.ts` і
        //       `:root[data-theme='day']` у `styles/messenger.css` — денна
        //       палітра існує; `WebMessengerHost` пише `container.webTheme` з
        //       розвʼязаної палітри композиції, а бандл читає її через
        //       `PhantomBridge.getTheme()` і `window.__phantomTheme`. Обидві
        //       половини світлішають разом.
        //       Власник 15.09: «застосунок зараз не як хамелеон по темі
        //       впродовж доби».
        assertEquals(ThemeMode.Living, parseStoredThemeMode(null))
        assertEquals(ThemeMode.Living, parseStoredThemeMode(""))
    }

    @Test
    fun `свіже встановлення опівдні світлішає, а вночі темніє`() {
        val fresh = parseStoredThemeMode(null)
        assertEquals(DayClear, resolveTheme(fresh, systemDark = false, phase = DayPhase.Day))
        // Системна ніч не переважує добу: Living іде за сонцем, не за ОС.
        assertEquals(DayClear, resolveTheme(fresh, systemDark = true, phase = DayPhase.Day))
        assertEquals(AmberNight, resolveTheme(fresh, systemDark = false, phase = DayPhase.Night))
    }

    @Test
    fun `ніч лишається досяжною одним вибором`() {
        assertEquals(AmberNight, resolveTheme(ThemeMode.Dark, systemDark = false, phase = DayPhase.Day))
        assertEquals(ThemeMode.Dark, parseStoredThemeMode("Dark"))
    }

    @Test
    fun `денна палітра лишається досяжною вибором`() {
        assertEquals(DayClear, resolveTheme(ThemeMode.Light, systemDark = true, phase = DayPhase.Night))
        assertEquals(DayClear, resolveTheme(ThemeMode.Living, systemDark = false, phase = DayPhase.Day))
    }

    @Test
    fun `enum name round-trips`() {
        assertEquals(ThemeMode.Living, parseStoredThemeMode("Living"))
        assertEquals(ThemeMode.Light, parseStoredThemeMode("Light"))
        assertEquals(ThemeMode.Dark, parseStoredThemeMode("Dark"))
        assertEquals(ThemeMode.System, parseStoredThemeMode("System"))
    }

    @Test
    fun `legacy spec ids migrate to corresponding mode`() {
        // Operators upgrading from the pre-QH2 build had `ui_theme`
        // = the spec id. Their explicit choice must carry across the
        // upgrade — surprise theme reverts kill trust.
        assertEquals(ThemeMode.Light, parseStoredThemeMode("sunrise-warm"))
        assertEquals(ThemeMode.Dark, parseStoredThemeMode("amber-night"))
    }

    @Test
    fun `unknown stored value falls back to the default`() {
        // Типове значення 15.09 — Living (див. тест про порожній вибір вище).
        // Непізнаний рядок = «нічого зрозумілого не обирали», отже те саме.
        assertEquals(ThemeMode.Living, parseStoredThemeMode("garbage"))
        assertEquals(ThemeMode.Living, parseStoredThemeMode("solar-eclipse-99"))
    }

    /* ---- startupThemeMode — the boot path ---- */

    @Test
    fun `a deliberate choice survives a restart`() {
        // `setThemeMode` persists exactly `mode.name`; boot reads it back
        // through here. Every mode round-trips, Dark included — the point of
        // dropping the Living default is that it must not drag anyone off a
        // theme they picked by hand.
        for (mode in ThemeMode.values()) {
            assertEquals(mode, startupThemeMode(mode.name, null))
        }
    }

    @Test
    fun `nothing stored at all opens Living`() {
        assertEquals(ThemeMode.Living, startupThemeMode(null, null))
    }

    @Test
    fun `stored Dark is never rewritten on the way in`() {
        // The Living→Dark migration is gone, and no Dark→Living one replaced
        // it: on disk a deliberate Dark and a migrated one are the same four
        // bytes, so nothing here may touch a stored value. Boot reads, it
        // does not write.
        assertEquals(ThemeMode.Dark, startupThemeMode(ThemeMode.Dark.name, null))
        assertEquals(ThemeMode.Dark, startupThemeMode(ThemeMode.Dark.name, "sunrise-warm"))
    }

    @Test
    fun `the new key wins over the pre-QH2 one`() {
        assertEquals(ThemeMode.Dark, startupThemeMode("Dark", "sunrise-warm"))
        // Legacy value is read only when the new key is absent.
        assertEquals(ThemeMode.Light, startupThemeMode(null, "sunrise-warm"))
    }

    @Test
    fun `pref key is the well-known string apps in the wild observe`() {
        // Don't rename casually — operators' SharedPreferences carry
        // values under this key.
        assertEquals("ui_theme_mode", THEME_MODE_PREF_KEY)
    }
}
