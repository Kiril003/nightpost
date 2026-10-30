package local.phantom.companion.core.data.locale

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Перший запуск збірки 18 (власник, 08.10): вибір мови на самому початку,
 * англійська перша й уже обрана, далі — інші мови, які застосунок СПРАВДІ має.
 * Вибір ставить мову застосунку (Android 13+ — LocaleManager), мову відповіді
 * помічника і запамʼятовується: другий запуск екрана вибору не показує.
 *
 * Система тут українська — щоб «англійська за замовчуванням» не збігалась із
 * мовою системи випадково.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class AppLanguageTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun appLocales(): LocaleList = app.getSystemService(LocaleManager::class.java).applicationLocales

    @Before
    fun freshInstall() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `English first and preselected, then the other translated languages, each with a flag`() {
        assertEquals(listOf("en", "uk", "cs"), AppLanguage.offered.map { it.tag })
        assertEquals(AppLanguage.EN, AppLanguage.DEFAULT)
        assertEquals("English", AppLanguage.EN.nativeName)
        assertEquals("Українська", AppLanguage.UK.nativeName)
        assertEquals("Čeština", AppLanguage.CS.nativeName)
        // Прапор — пара регіональних індикаторів (U+1F1E6…U+1F1FF), а не літери.
        for (l in AppLanguage.offered) {
            val cps = l.flag.codePoints().toArray()
            assertEquals("${l.tag}: прапор — рівно два індикатори", 2, cps.size)
            assertTrue("${l.tag}: не регіональний індикатор", cps.all { it in 0x1F1E6..0x1F1FF })
        }
    }

    @Test
    fun `a fresh install asks for the language and preselects the phone's own`() {
        // Власник 29.10: мова — за системою. Телефон український → українська.
        assertTrue("перший запуск мусить спитати мову", AppLanguage.needsPick(app))
        assertEquals(AppLanguage.UK, AppLanguage.firstRunGuess(app))
        assertEquals(AppLanguage.UK, AppLanguage.current(app))
        assertEquals("Скасувати", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    @Config(qualifiers = "ja")
    fun `a fresh install on a phone in a language we lack preselects English`() {
        assertEquals(AppLanguage.EN, AppLanguage.firstRunGuess(app))
        assertEquals(AppLanguage.EN, AppLanguage.current(app))
    }

    @Test
    fun `As on phone in Profile drops the app language, follows the phone and skips the first screen`() {
        AppLanguage.choose(app, AppLanguage.CS)
        assertFalse(AppLanguage.followsPhone(app))
        AppLanguage.followPhone(app)
        assertTrue(appLocales().isEmpty)
        assertTrue(AppLanguage.followsPhone(app))
        assertFalse("«Як у телефоні» — теж вибір", AppLanguage.needsPick(app))
        assertEquals(AppLanguage.UK, AppLanguage.current(app))
        AppLanguage.choose(app, AppLanguage.EN)
        assertFalse(AppLanguage.followsPhone(app))
    }

    @Test
    fun `picking Ukrainian sets the app locale and the assistant language, and the second launch skips the picker`() {
        AppLanguage.choose(app, AppLanguage.UK)
        assertEquals("uk", appLocales().toLanguageTags())
        assertFalse("другий запуск знову питає мову", AppLanguage.needsPick(app))
        assertEquals(AppLanguage.UK, AppLanguage.current(app))
        assertEquals("Скасувати", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    fun `picking English on a Ukrainian phone answers in English`() {
        AppLanguage.choose(app, AppLanguage.EN)
        assertEquals("en", appLocales().toLanguageTags())
        assertEquals(AppLanguage.EN, AppLanguage.current(app))
        assertEquals("Cancel", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    fun `picking Czech sets the app locale and the assistant language, and a Czech phone on System default follows it`() {
        AppLanguage.choose(app, AppLanguage.CS)
        assertEquals("cs", appLocales().toLanguageTags())
        assertFalse(AppLanguage.needsPick(app))
        assertEquals(AppLanguage.CS, AppLanguage.current(app))
        assertEquals("Zrušit", ReplyLanguage.context(app).getString(android.R.string.cancel))
        assertEquals("Zrušit", AppLanguage.localized(app, AppLanguage.CS).getString(android.R.string.cancel))
        assertEquals(AppLanguage.CS, AppLanguage.of(Locale.forLanguageTag("cs-CZ")))
    }

    @Test
    fun `a language already picked in Profile before build 18 counts as a pick`() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("uk")
        assertFalse(AppLanguage.needsPick(app))
        assertEquals(AppLanguage.UK, AppLanguage.current(app))
    }

    /**
     * Огляд 08.10: людина обрала англійську на першому екрані, потім у
     * «Налаштування › Застосунки › Nightpost › Мова» поставила «Як у системі».
     * Рідні екрани тоді українські (мова телефона), тож і помічник, і
     * месенджер, і Профіль › Мова мусять казати те саме — а не англійську з
     * давнього вибору, якої вже ніде не видно. І Профіль мусить уміти
     * повернути англійську.
     */
    @Test
    fun `System default in Android's app-language settings is followed everywhere, and Profile can switch back`() {
        AppLanguage.choose(app, AppLanguage.EN)
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()

        assertFalse("«Як у системі» — теж вибір, перший екран не вертається", AppLanguage.needsPick(app))
        assertEquals("рідні екрани українські, а Профіль › Мова каже інше", AppLanguage.UK, AppLanguage.current(app))
        assertEquals("Скасувати", ReplyLanguage.context(app).getString(android.R.string.cancel))

        AppLanguage.choose(app, AppLanguage.EN)
        assertEquals("en", appLocales().toLanguageTags())
        assertEquals(AppLanguage.EN, AppLanguage.current(app))
    }

    @Test
    fun `the picker's own words come in the preselected language whatever the system says`() {
        assertEquals("Cancel", AppLanguage.localized(app, AppLanguage.EN).getString(android.R.string.cancel))
        assertEquals("Скасувати", AppLanguage.localized(app, AppLanguage.UK).getString(android.R.string.cancel))
    }

    @Test
    fun `Android 13+ with phone languages ru then uk and Ukrainian picked narrows the context to uk alone`() {
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().putString("picked", "uk").commit()
        val phone = app.createConfigurationContext(
            android.content.res.Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("ru-UA,uk-UA")) },
        )

        val wrapped = AppLanguage.wrap(phone)

        assertEquals("uk", wrapped.resources.configuration.locales.toLanguageTags())
        assertEquals("Скасувати", wrapped.getString(android.R.string.cancel))
    }

    @Test
    fun `nothing picked leaves the phone context as it is`() {
        val phone = app.createConfigurationContext(
            android.content.res.Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("ru-UA,uk-UA")) },
        )
        assertTrue(AppLanguage.wrap(phone) === phone)
    }
}

/**
 * До Android 13 мови застосунку в системі немає: вибір лежить у власних
 * налаштуваннях, а активність бере його через [AppLanguage.wrap] у
 * `attachBaseContext`. Система англійська — вибір українська.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class AppLanguageBelow13Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val processLocale: Locale = Locale.getDefault()

    @Before
    fun freshInstall() {
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun restoreProcessLocale() {
        Locale.setDefault(processLocale)
    }

    /**
     * Огляд 08.10: до Android 13 мову брала лише MainActivity. Сповіщення,
     * служби й рядки з `applicationContext`, `Locale.getDefault()` (дати,
     * геокодер, перевірка мови в AppContainer) і інші активності лишались
     * мовою телефона. Вибір мусить дійти до всього процесу.
     */
    @Test
    fun `the pick reaches the whole process, not only the activity`() {
        AppLanguage.choose(app, AppLanguage.UK)

        assertEquals("Locale.getDefault() лишився мовою телефона", "uk", Locale.getDefault().language)
        assertEquals("рядки з applicationContext лишились мовою телефона", "Скасувати", app.getString(android.R.string.cancel))
    }

    @Test
    fun `the Czech pick reaches the whole process too`() {
        AppLanguage.choose(app, AppLanguage.CS)

        assertEquals("cs", Locale.getDefault().language)
        assertEquals("Zrušit", app.getString(android.R.string.cancel))
        assertEquals(AppLanguage.CS, AppLanguage.current(app))
        assertEquals("Zrušit", AppLanguage.wrap(app).getString(android.R.string.cancel))
    }

    @Test
    fun `a cold start takes the saved pick for the whole process`() {
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().putString("picked", "uk").commit()

        // Те, що роблять PhantomApp.attachBaseContext і кожна активність.
        val wrapped = AppLanguage.wrap(app)

        assertEquals("Скасувати", wrapped.getString(android.R.string.cancel))
        assertEquals("Locale.getDefault() на холодному старті — мова телефона", "uk", Locale.getDefault().language)
    }

    @Test
    fun `the pick is remembered and wraps the activity context`() {
        assertTrue(AppLanguage.needsPick(app))
        assertEquals("Cancel", AppLanguage.wrap(app).getString(android.R.string.cancel))

        AppLanguage.choose(app, AppLanguage.UK)

        assertFalse(AppLanguage.needsPick(app))
        assertEquals(AppLanguage.UK, AppLanguage.current(app))
        assertEquals("Скасувати", AppLanguage.wrap(app).getString(android.R.string.cancel))
        assertEquals("Скасувати", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }
}

/**
 * «Як у системі» на телефоні, чиєї мови в застосунку немає (німецька): Android
 * бере `res/values/` — англійську, тож і Профіль, і помічник, і месенджер
 * мусять казати англійську, а не якусь іншу «за замовчуванням».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class AppLanguageUnofferedPhoneTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun freshInstall() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        app.getSharedPreferences("phantom_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `System default on a phone in a language the app lacks reports the language of the default resources`() {
        assertTrue("свіже встановлення мусить спитати мову", AppLanguage.needsPick(app))
        assertEquals(AppLanguage.EN, AppLanguage.current(app))

        AppLanguage.choose(app, AppLanguage.EN)
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()

        assertFalse(AppLanguage.needsPick(app))
        assertEquals("рідні екрани — res/values/, англійська", AppLanguage.EN, AppLanguage.current(app))
        assertEquals("Cancel", ReplyLanguage.context(app).getString(android.R.string.cancel))
    }

    @Test
    fun `following a German phone stays English while values-de is partial`() {
        AppLanguage.followPhone(app)
        assertEquals("en", AppLanguage.wrap(app).resources.configuration.locales.get(0).language)
    }

    @Test
    fun `a partly translated wave language never reaches native screens`() {
        assertEquals(null, AppLanguage.phoneWave(listOf(Locale.GERMANY)))
        assertEquals(null, AppLanguage.phoneWave(listOf(Locale.forLanguageTag("pt-BR"), Locale("uk"))))
    }

    @Test
    fun `the phone's first ready wave language wins, ours before it stays ours`() {
        val ready = setOf("de", "pt")
        assertEquals(Locale.GERMANY, AppLanguage.phoneWave(listOf(Locale.GERMANY, Locale("uk")), ready))
        assertEquals("pt", AppLanguage.phoneWave(listOf(Locale.forLanguageTag("pt-BR")), ready)?.language)
        assertEquals(null, AppLanguage.phoneWave(listOf(Locale("ru", "UA"), Locale("uk", "UA"), Locale.GERMANY), ready))
        assertEquals(null, AppLanguage.phoneWave(listOf(Locale.JAPAN), ready))
    }
}
