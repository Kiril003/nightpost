package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мова, обрана на першому екрані (або в Профілі › Мова), доходить і до
 * веб-месенджера: до скриптів сторінки рідний бік кладе її туди, звідки
 * бандл бере мову ще до першого кадру (`localStorage["phantom_language"]`
 * → `<html lang>` і рядки `useTranslation`).
 */
class WebMessengerLanguageTest {

    private val mono: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "phantom-os/src/frontend/src").isDirectory }

    private fun web(rel: String) = File(mono, "phantom-os/src/frontend/src/$rel").readText()

    private fun app(rel: String) =
        File(mono, "phantom-companion/app/src/main/java/local/phantom/companion/$rel").readText()

    @Test
    fun `the script writes the picked language where the bundle reads it`() {
        for (tag in WEB_LOCALES) {
            val js = WebMessengerAssets.languageScript(tag)
            assertTrue(js, "localStorage.setItem('phantom_language','$tag')" in js)
            assertTrue(js, "setAttribute('lang','$tag')" in js)
        }
        for (l in AppLanguage.entries) assertTrue(l.tag in WEB_LOCALES)
        assertTrue(runCatching { WebMessengerAssets.languageScript("x');alert(1)//") }.isFailure)
    }

    @Test
    fun `the storage key is the bundle's own`() {
        val store = web("stores/settingsStore.ts")
        assertTrue(
            "бандл читає мову з іншого ключа — рідний бік пише в порожнечу",
            Regex("""LANGUAGE_STORAGE_KEY\s*=\s*'phantom_language'""").containsMatchIn(store),
        )
        val boot = web("services/settingsBootstrap.ts")
        assertTrue("бандл більше не бере мову зі сховища до першого кадру", "localStorage.getItem(LANGUAGE_STORAGE_KEY)" in boot)
    }

    @Test
    fun `every language offered natively has a web catalogue`() {
        val locales = web("i18n/locales.ts")
        fun list(name: String) = Regex("""$name\s*=\s*\[([^\]]*)\]""").find(locales)?.groupValues?.get(1)
            ?.let { Regex("""'([A-Za-z-]+)'""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            ?: error("$name у вебі не знайдено")
        assertEquals(list("EAGER_LOCALES").toSet(), AppLanguage.offered.map { it.tag }.toSet())
        assertEquals(list("EAGER_LOCALES") + list("LAZY_LOCALES"), WEB_LOCALES)
    }

    /** Лід 29.10: телефон мовою, якої рідний бік не має — рідні екрани англійські, месенджер мовою телефона; ніколи українською. */
    @Test
    fun `a phone in a wave language opens the messenger in it, else English, never Ukrainian`() {
        fun l(tag: String) = java.util.Locale.forLanguageTag(tag)
        assertEquals("de", webLanguageFor(null, listOf(l("de-AT"), l("en-US"))))
        assertEquals("es", webLanguageFor(null, listOf(l("es-MX"))))
        assertEquals("pt-BR", webLanguageFor(null, listOf(l("pt-PT"))))
        assertEquals("id", webLanguageFor(null, listOf(l("in-ID"))))
        assertEquals("en", webLanguageFor(null, listOf(l("ru-RU"), l("ja-JP"))))
        assertEquals("en", webLanguageFor(null, emptyList()))
        assertEquals("cs", webLanguageFor(AppLanguage.CS, listOf(l("de-DE"))))
        for (tag in listOf("de", "es", "fr", "pl", "tr", "it", "id", "pt-BR", "ru", "ja")) {
            assertTrue(tag, webLanguageFor(null, listOf(l(tag))) != "uk")
        }
        assertTrue(AppLanguage.noneOfOurs(listOf(l("de-DE"), l("es-ES"))))
        assertTrue(!AppLanguage.noneOfOurs(listOf(l("de-DE"), l("uk-UA"))))
    }

    @Test
    fun `the messenger WebView gets the script before the page loads`() {
        val host = app("ui/messenger/WebMessengerHost.kt")
        val make = host.substringAfter("internal fun createMessengerWeb(")
        val script = make.indexOf("WebMessengerAssets.languageScript(")
        assertTrue("WebView месенджера не отримує мову Nightpost", script >= 0)
        assertTrue("мова — не поточний вибір", "webLanguage(context)" in make.substring(script, script + 200))
        assertTrue("мову ставлять після завантаження сторінки", script < make.indexOf("loadUrl(WebMessengerAssets.ENTRY)"))
        assertTrue("не скрипт до сторінки", "addDocumentStartJavaScript" in make.substring(script - 200, script))
    }

    /**
     * 09.10: власник обрав English — розмови українські. Скрипт до сторінки
     * пече мову в момент, коли WebView створено; зміна мови в Профілі на вже
     * відкриту сторінку не доходила, а `ui_language` у сторі вебу не ставив ніхто.
     * Тепер мова йде двома шляхами, як тема: міст на завантаженні й поштовх на зміну.
     */
    @Test
    fun `the host pushes the native pick into the open page whenever it changes`() {
        val host = app("ui/messenger/WebMessengerHost.kt").substringBefore("internal fun createMessengerWeb(")
        val effect = host.indexOf("LaunchedEffect(webView, language)")
        assertTrue("хост не штовхає мову в сторінку на зміну вибору", effect >= 0)
        assertTrue("поштовх не мовою вебу", "webLanguagePushJs(language)" in host.substring(effect, effect + 200))
        val pick = host.substring(0, effect).substringAfterLast("val language")
        assertTrue("мова поштовху — не вибір Nightpost", "webLanguage(context)" in pick)
    }

    @Test
    fun `the bridge tells the page the native pick on load`() {
        val bridge = app("ui/messenger/WebMessengerBridge.kt")
        assertTrue(
            "міст не має getLanguage для вебу",
            Regex("""@JavascriptInterface\s+fun getLanguage\(\): String""").containsMatchIn(bridge),
        )
        val body = bridge.substringAfter("fun getLanguage(): String").take(200)
        assertTrue("getLanguage віддає не вибір Nightpost", "webLanguage(" in body)
        for (l in AppLanguage.offered) assertEquals(l.tag, webLanguageOf(l))
    }

    @Test
    fun `the push calls the sink the bundle declares and the entry arms it`() {
        assertEquals("window.__phantomLanguage && window.__phantomLanguage('en');", webLanguagePushJs("en"))
        assertEquals("window.__phantomLanguage && window.__phantomLanguage('uk');", webLanguagePushJs("uk"))
        assertEquals("window.__phantomLanguage && window.__phantomLanguage('cs');", webLanguagePushJs("cs"))
        val sink = web("services/nativeLanguage.ts")
        assertTrue("бандл не оголошує приймача мови", "w.$WEB_LANGUAGE_SINK = " in sink)
        assertTrue("бандл не питає мову в моста", "getLanguage" in sink && "LANGUAGE_SETTING_KEY" in sink)
        assertTrue("вхід бандла не вмикає мову рідного боку", "startNativeLanguageWatch()" in web("main.tsx"))
    }

    /** Джерело — не те, що їде в APK: приймач мусить бути в запечатаному бандлі. */
    @Test
    fun `the sealed phone bundle carries the language sink`() {
        val bundle = File(mono, "phantom-companion/app/src/main/assets/webmsgr")
        val js = bundle.walkTopDown().filter { it.isFile && it.extension == "js" }.toList()
        assertTrue("бандла немає: $bundle", js.isNotEmpty())
        assertTrue(
            "бандл у APK старший за міст мови — запусти sync_web_messenger.sh",
            js.any { WEB_LANGUAGE_SINK in it.readText() && "getLanguage" in it.readText() },
        )
    }

    /**
     * Профіль › Мова — лише список (власник 11.10: без пояснювальної прози).
     * Колишній `language_body` («месенджер лишається українським») ніде не
     * показувався і застарів: месенджер тепер іде за вибраною мовою.
     */
    @Test
    fun `the language screen carries no stale explanation`() {
        val res = File(mono, "phantom-companion/app/src/main/res")
        val left = res.listFiles().orEmpty().filter { it.name.startsWith("values") }
            .flatMap { d -> d.listFiles().orEmpty().filter { "name=\"language_body\"" in it.readText() }.map { "${d.name}/${it.name}" } }
        assertTrue("language_body лишився: $left", left.isEmpty())
    }

    /**
     * Огляд 08.10 (відхилено, сторож лишається): «вибір мови в самому
     * месенджері мовчки перетирається». Усередині APK такого вибору немає:
     * `LanguagePicker` живе лише в `SettingsPanel`, а той — лише за
     * `DeskRouter`, якого телефонна збірка не має (`__PHONE_BUILD__ ?
     * DeskNothing`). І `/settings` (звідки `ui_language` міг би переписати
     * `localStorage` в `applyUISettings`) міст телефона не обслуговує — він
     * відповідає лише на шляхи під `/messenger`. Єдине джерело мови на телефоні —
     * рідний вибір. Цей тест червоніє, щойно хоч одне з цього зміниться.
     */
    @Test
    fun `inside the APK nothing on the web side can override the native language`() {
        val src = File(mono, "phantom-os/src/frontend/src")
        val files = src.walkTopDown()
            .filter { it.isFile && (it.extension == "ts" || it.extension == "tsx") }
            .filterNot { "__tests__" in it.path || ".test." in it.name }
            .toList()
        val setters = files.filter { "setLanguage(" in codeOnly(it.readText()) && !it.path.endsWith("stores/settingsStore.ts") }
        assertEquals("мову в месенджері ставить хтось, крім SettingsPanel і дзеркала рідного вибору", listOf("SettingsPanel.tsx", "nativeLanguage.ts"), setters.map { it.name }.sorted())
        assertTrue("веб ставить мову повз міст телефона", "bridge.setLanguage(locale)" in web("services/nativeLanguage.ts"))
        val panelUsers = files.filter { Regex("""import\(\s*'[./]*components/settings/SettingsPanel'\s*\)|from\s+'[./]*components/settings/SettingsPanel'""").containsMatchIn(it.readText()) }
        assertEquals("SettingsPanel досяжний не лише з DeskRouter", listOf("DeskRouter.tsx"), panelUsers.map { it.name })
        assertTrue(
            "телефонна збірка тягне DeskRouter — вибір мови вебу є в APK",
            Regex("""const DeskRouter\s*=\s*__PHONE_BUILD__\s*\?\s*DeskNothing""").containsMatchIn(web("app/App.tsx")),
        )
        val bridge = app("ui/messenger/WebMessengerBridge.kt").substringAfter("private suspend fun route(")
        assertTrue(
            "міст телефона відповідає не лише на /messenger/* — /settings може переписати мову",
            "seg[0] != \"messenger\") return fail(404" in bridge.substring(0, 2000),
        )
    }

    private fun codeOnly(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .map { line -> line.substringBefore(" //").let { if (it.trimStart().startsWith("//")) "" else it } }
        .joinToString("\n")
}
