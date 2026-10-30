package local.phantom.companion.navigation

import java.io.File
import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Досяжність першого запуску збірки 18: холодний старт без вибору мови веде
 * на екран мови РАНІШЕ за все інше (і раніше за короткий шлях на домівку);
 * дотик до мови ставить її й веде далі — до знайомства або в розмови; Профіль ›
 * Мова показує й міняє той самий вибір.
 *
 * Поведінку вибору (LocaleManager, мова помічника, другий запуск без екрана)
 * доводить `AppLanguageTest` у :core-data на Robolectric; Robolectric у :app
 * немає, тож тут — читання джерел, як у [ColdStartPathTest].
 */
class FirstRunLanguageReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(rel: String) = File(root, "app/src/main/java/local/phantom/companion/$rel").readText()

    private fun code(text: String) = text.lineSequence()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `the language route exists and is mounted`() {
        assertTrue("data object Language : Routes(\"language\")" in src("navigation/Routes.kt"))
        assertTrue("composable(Routes.Language.path)" in src("navigation/PhantomNavGraph.kt"))
    }

    @Test
    fun `a cold start without a pick goes to the language screen before anything else`() {
        val graph = src("navigation/PhantomNavGraph.kt")
        val boot = code(graph.substringAfter("private fun BootstrapDestination(").substringBefore("private fun OnboardingDestination("))
        val gate = boot.indexOf("AppLanguage.needsPick(context)")
        assertTrue("Bootstrap не питає, чи обрано мову", gate >= 0)
        assertTrue(
            "короткий шлях на домівку обганяє вибір мови",
            gate < boot.indexOf("LaunchRoute.lastWasHome(context)"),
        )
        val branch = boot.substring(gate).substringBefore("LaunchRoute.lastWasHome(context)")
        assertTrue("без вибору мови Bootstrap веде не на екран мови", "navController.navigate(Routes.Language.path)" in branch)
        assertTrue("екран мови лишає Bootstrap у стеку", "popUpTo(Routes.Bootstrap.path) { inclusive = true }" in branch)
    }

    @Test
    fun `a tap on a language sets it, then the identity setup or the chats follow`() {
        val graph = src("navigation/PhantomNavGraph.kt")
        val dest = code(graph.substringAfter("private fun LanguageDestination(").substringBefore("\n@Composable"))
        assertTrue("екран мови не показує LanguageStep", "LanguageStep(" in dest)
        val choose = dest.indexOf("AppLanguage.choose(")
        val next = dest.indexOf("navController.navigate(target)")
        assertTrue("вибір мови нікуди не записується", choose >= 0)
        assertTrue("після мови нікуди не веде", next >= 0)
        assertTrue("куди далі — не за профілями (знайомство / вибір профілю / розмови)", "LaunchRoute.target(" in dest)
        // Мова застосунку перестворює активність: стек мусить уже стояти на
        // наступному екрані, інакше після перестворення людина знову на мові.
        assertTrue("перехід після перестворення активності, а не до нього", choose < next)
        assertTrue("екран мови лишається під знайомством", "popUpTo(Routes.Language.path) { inclusive = true }" in dest)
    }

    @Test
    fun `sticky requests wait past the language screen too`() {
        val graph = src("navigation/PhantomNavGraph.kt")
        val settled = graph.substringAfter("suspend fun settled()").substringBefore("fun home()")
        assertTrue("відкриття розмови з нотифікації стрибає через екран мови", "Routes.Language.path" in settled)
    }

    /**
     * Огляд 08.10: до Android 13 мову брала лише MainActivity — тривога на
     * весь екран, сповіщення й служби (рядки з `applicationContext`) лишались
     * мовою телефона. Тепер — застосунок і КОЖНА активність із маніфесту.
     */
    @Test
    fun `below Android 13 the application and every activity take the pick at attach`() {
        val app = code(src("PhantomApp.kt"))
        assertTrue("PhantomApp не бере мову: сповіщення й служби — мовою телефона", "override fun attachBaseContext(" in app)
        assertTrue("PhantomApp не бере мову: сповіщення й служби — мовою телефона", "AppLanguage.wrap(" in app)

        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val activities = Regex("""<activity[^>]*?android:name="\.([\w.]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("у маніфесті не знайшлося активностей", activities.size >= 2)
        for (name in activities) {
            val body = code(src(name.replace('.', '/') + ".kt"))
            assertTrue("$name лишається мовою телефона до Android 13", "override fun attachBaseContext(" in body && "AppLanguage.wrap(" in body)
        }
    }

    /**
     * Служба до Android 13 має власний контекст, не контекст застосунку:
     * `getString` у `NavigationService`, `AgentMissionService`,
     * `FalsePositionService` (текст сповіщення) без обгортки лишався б мовою
     * телефона, хоч `PhantomApp` і активності вже говорять обраною. (Приймачі
     * отримують базовий контекст застосунку, тож їх покриває `PhantomApp`.)
     */
    @Test
    fun `below Android 13 every service of the app takes the pick at attach`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val services = Regex("""<service[^>]*?android:name="\.([\w.]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("у маніфесті не знайшлося служб: $services", services.size >= 7)
        for (name in services) {
            val body = code(src(name.replace('.', '/') + ".kt"))
            assertTrue("$name лишається мовою телефона до Android 13", "override fun attachBaseContext(" in body && "AppLanguage.wrap(" in body)
        }
    }

    @Test
    fun `Profile language reads and writes the same pick, English first`() {
        val profile = src("ui/home/ProfileSections.kt")
        val section = code(profile.substringAfter("private fun LanguageSection(").substringBefore("\n@Composable"))
        assertTrue("Профіль › Мова показує не той самий вибір", "AppLanguage.current(context)" in section)
        assertTrue("Профіль › Мова пише повз спільний вибір", "AppLanguage.choose(" in section)
        assertTrue("Профіль › Мова перелічує мови руками", "AppLanguage.offered" in section)
        // Огляд 08.10: після «Як у системі» дотик до показаної мови мусить її
        // поставити, а не мовчки нічого не робити.
        assertFalse("дотик до вже позначеної мови нічого не робить", "!= current" in section)
        assertFalse(
            "Профіль досі веде до системних налаштувань мови замість вибору в застосунку",
            "ACTION_LOCALE_SETTINGS" in code(profile),
        )
        assertEquals(AppLanguage.EN, AppLanguage.offered.first())
    }

    @Test
    fun `the Profile row shows the current pick by its own name and no flag`() {
        val tab = code(src("ui/home/ProfileSettings.kt"))
        assertTrue("рядок Мова в Профілі не показує поточний вибір", "languageValue(AppLanguage.current(context))" in tab)
        val value = tab.substringAfter("internal fun languageValue")
        assertTrue("рядок Мова без власної назви", "language.nativeName" in value)
        assertFalse("рядок Мова знову з прапором", ".flag" in value)
    }
}
