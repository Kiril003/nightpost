package local.phantom.companion.ui.messenger

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A52 (18.10): телефон [ru-UA, uk-UA], обрано українську — «Збережене» з мосту йшло англійською. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SavedTitleLocaleTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun noAppLocale() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun phone(tags: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) })

    @Test
    fun `Saved title follows the pick on a phone listing ru before uk`() {
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().putString("picked", "uk").commit()
        // На A52 WebView домішує ru-ресурси, і список [ru, uk] давав англійську; одна мова — єдиний захист.
        val wrapped = AppLanguage.wrap(phone("ru-UA,uk-UA"))
        assertEquals("uk", wrapped.resources.configuration.locales.toLanguageTags())
        assertEquals("Збережене", wrapped.getString(R.string.msgr_saved_title))
    }

    @Test
    fun `with the app locale set by the system the context is left to the system`() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("cs")
        val base = phone("cs")
        assertEquals(true, AppLanguage.wrap(base) === base)
        assertEquals("Uložené", base.getString(R.string.msgr_saved_title))
    }
}
