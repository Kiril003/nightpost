package local.phantom.companion.core.data.locale

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Мова відповіді помічника — мова, яку людина обрала для Nightpost.
 *
 * Збірка 17: без вибору — українська (месенджер говорив лише нею). Збірка 18
 * (власник, 08.10): перший запуск питає мову, англійська — за замовчуванням і
 * перша в переліку. З 29.10 (власник: мова — за системою) «не обирала» = мова
 * телефона, якщо вона в нас є, інакше англійська; ніколи не українська на чужій. Рядки — з ресурсів самого
 * Android («Скасувати» / «Cancel»): у цього модуля власних немає, а механізм
 * той самий, що в :app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class ReplyLanguageTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun pick(tags: String) {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
    }

    @Before
    fun fresh() {
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a Ukrainian system without a pick answers in Ukrainian, like the screens`() {
        pick("")
        assertEquals("Скасувати", app.getString(android.R.string.cancel))
        assertEquals("Скасувати", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    @Config(qualifiers = "de")
    fun `a German system without a pick answers in English, never Ukrainian`() {
        pick("")
        assertEquals("Cancel", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    fun `Ukrainian stays Ukrainian when the person picked it for the app`() {
        pick("uk")
        assertEquals("Скасувати", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    fun `only the translated languages, everything else is the English default`() {
        assertEquals("en", ReplyLanguage.locale(null).language)
        assertEquals("en", ReplyLanguage.locale(Locale.ENGLISH).language)
        assertEquals("en", ReplyLanguage.locale(Locale.forLanguageTag("en-GB")).language)
        assertEquals("en", ReplyLanguage.locale(Locale.GERMAN).language)
        assertEquals("uk", ReplyLanguage.locale(Locale.forLanguageTag("uk-UA")).language)
    }
}
