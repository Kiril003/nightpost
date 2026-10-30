package local.phantom.companion.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.util.Base64
import local.phantom.companion.core.design.theme.FONT_SCALE_DEFAULT
import local.phantom.companion.core.design.theme.FONT_SCALE_PREF_KEY
import local.phantom.companion.core.design.theme.THEME_MODE_PREF_KEY
import local.phantom.companion.core.design.theme.THEME_PREFS_FILE
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.core.design.theme.startupThemeMode
import local.phantom.companion.data.QuietHours
import local.phantom.companion.data.QuietHoursStore
import local.phantom.companion.data.linkpreview.LinkPreviewStore
import local.phantom.companion.ui.theme.SplashNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupSettingsRoundTripTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery".toCharArray()
    private val cheap = BackupFormat.KdfParams(m = 8_192, t = 1, p = 1)

    private val stores = listOf(THEME_PREFS_FILE, "phantom_quiet_hours_v1", "phantom_link_previews_v1")

    private fun wipe() = stores.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

    private fun assertTypedReadsHold() {
        SplashNight.sync(app, ThemeMode.Dark, resolvedDark = true)
        val quiet = QuietHoursStore(app).state.value
        assertTrue(quiet.enabled)
        assertEquals(LocalTime.of(22, 30), quiet.from)
        assertEquals(setOf("aa11", "bb22"), quiet.except)
        assertFalse(LinkPreviewStore(app).enabled.value)
        val theme = app.getSharedPreferences(THEME_PREFS_FILE, Context.MODE_PRIVATE)
        assertEquals(ThemeMode.Dark, startupThemeMode(theme.getString(THEME_MODE_PREF_KEY, null), theme.getString("ui_theme", null)))
        assertEquals(1.15f, theme.getFloat(FONT_SCALE_PREF_KEY, FONT_SCALE_DEFAULT), 0.001f)
    }

    @Test
    fun `backup then restore keeps every setting readable with its own type`() {
        wipe()
        app.getSharedPreferences(THEME_PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString(THEME_MODE_PREF_KEY, ThemeMode.Dark.name)
            .putFloat(FONT_SCALE_PREF_KEY, 1.15f)
            .putInt("app_night_mode_set", 2)
            .commit()
        QuietHoursStore(app).set(QuietHours(enabled = true, from = LocalTime.of(22, 30), except = setOf("aa11", "bb22")))
        LinkPreviewStore(app).set(false)

        val manifest = BackupManifest.collect(identity = null, claimKey = null, candidateSettings = BackupSettings.read(app))
        val sealed = ByteArrayOutputStream()
        BackupFormat.sealManifest(manifest, sealed, pass.copyOf(), cheap)
        val opened = BackupFormat.openManifest(ByteArrayInputStream(sealed.toByteArray()), pass.copyOf())
        assertNotNull(opened)

        wipe()
        BackupSettings.write(app, opened!!.settings)

        assertTypedReadsHold()
    }

    @Test
    fun `an old untyped manifest restores known keys typed and drops the rest`() {
        wipe()
        val b64 = Base64.getEncoder().withoutPadding()
        fun e(s: String) = b64.encodeToString(s.toByteArray(Charsets.UTF_8))
        val legacy = BackupManifest.SettingsSection(
            prefsB64 = mapOf(
                e(THEME_PREFS_FILE) to mapOf(
                    e(THEME_MODE_PREF_KEY) to e("Dark"),
                    e(FONT_SCALE_PREF_KEY) to e("1.15"),
                    e("app_night_mode_set") to e("2"),
                    e("zzz_unknown") to e("x"),
                ),
                e("phantom_quiet_hours_v1") to mapOf(
                    e("on") to e("true"),
                    e("from") to e("22:30"),
                    e("to") to e("07:00"),
                    e("except") to e("[aa11, bb22]"),
                ),
                e("phantom_link_previews_v1") to mapOf(e("make") to e("false")),
            ),
        )

        BackupSettings.write(app, legacy)

        val theme = app.getSharedPreferences(THEME_PREFS_FILE, Context.MODE_PRIVATE).all
        assertFalse(theme.containsKey("app_night_mode_set"))
        assertFalse(theme.containsKey("zzz_unknown"))
        assertTypedReadsHold()
    }
}
