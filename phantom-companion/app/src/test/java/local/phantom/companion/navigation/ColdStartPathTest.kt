package local.phantom.companion.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Холодний старт збірки 16: перший кадр — ескіз домівки, WebView прогрівається
 * одразу після нього, домівка не чекає Room, а веб не просвічує кремовим.
 *
 * Виміряно на A52 (debug, до правки): Bootstrap чекав Room 1,9 с, WebView
 * народжувався через 5,5 с після старту процесу, а під ним — прозорим — стояло
 * полотно Compose. Цей тест тримає всі три двері зачиненими: розвилку старту
 * (чиста функція), а решту — читанням вихідників, бо Compose і WebView у
 * юніт-тесті не живуть.
 *
 * GATE LAW: читає вихідники як файли, повз перевірку актуальності Gradle.
 */
class ColdStartPathTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(rel: String) = File(root, "app/src/main/java/local/phantom/companion/$rel").readText()

    @Test
    fun `розвилка старту - знайомство, вибір профілю, домівка`() {
        assertEquals(Routes.Onboarding.path, LaunchRoute.target(count = 0, anyActive = false))
        assertEquals(Routes.Launcher.path, LaunchRoute.target(count = 2, anyActive = false))
        assertEquals(Routes.Home.path, LaunchRoute.target(count = 1, anyActive = true))
    }

    @Test
    fun `минулий старт на домівці - Bootstrap іде туди одразу, з ескізом і прогрітим WebView`() {
        val graph = src("navigation/PhantomNavGraph.kt")
        val boot = graph.substringAfter("private fun BootstrapDestination(").substringBefore("private fun OnboardingDestination(")
        val shortcut = boot.substringAfter("if (homeLast) {").substringBefore("val profiles by")
        assertTrue("короткого шляху немає", "LaunchRoute.lastWasHome(context)" in boot)
        assertTrue("перший кадр короткого шляху — не ескіз домівки", "HomeSkeleton()" in shortcut)
        assertTrue("WebView не прогрівається", "WebMessengerPrewarm.start(activity)" in shortcut)
        val hook = shortcut.indexOf("WebMessengerPrewarm.afterNextDraw(")
        assertTrue("прогрів не на хуку першого кадру", hook in 0 until shortcut.indexOf("WebMessengerPrewarm.start"))
        assertTrue("прогрів знову чекає завісу (A52: +0,8 с до loadUrl)", "delay(" !in shortcut && "withFrameNanos" !in shortcut)
        assertTrue("домівка компонується раніше за прогрів", shortcut.indexOf("WebMessengerPrewarm.start") < shortcut.indexOf("navController.navigate(Routes.Home.path)"))
        assertTrue("короткий шлях чекає Room", "observeAll" !in shortcut)
        assertTrue("відповідь Room не запамʼятовується", "LaunchRoute.remember(context, target)" in boot)
        // Room перевіряє вибір фоном і виправляє розбіжність.
        val guard = graph.substringAfter("Перевірка короткого шляху").substringBefore("container.homeTabRequest.collect")
        assertTrue("LaunchRoute.target(list)" in guard)
        assertTrue("navController.navigate(target)" in guard)
    }

    @Test
    fun `смуги першого кадру - за полотном заставки, а не за темою системи`() {
        val create = src("MainActivity.kt").substringAfter("override fun onCreate(").substringBefore("setContent {")
        // Лише код: коментар над викликом сам називає «типовий enableEdgeToEdge()».
        val code = create.lineSequence().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }.toList()
        assertFalse("типовий enableEdgeToEdge() бере тему системи — світлий серпанок під кнопками", "enableEdgeToEdge()" in code)
        assertTrue("getColor(R.color.phantom_canvas)" in create)
        assertTrue("window.isNavigationBarContrastEnforced = false" in create)
    }

    @Test
    fun `домівка бере прогрітий WebView, малює його полотном теми й накриває ескізом до першого кадру вебу`() {
        val host = src("ui/messenger/WebMessengerHost.kt")
        assertTrue("домівка не бере прогрітий WebView", "WebMessengerPrewarm.take(context)" in host)
        assertFalse("WebView знову прозорий — під ним проступить полотно Compose", "Color.TRANSPARENT" in host)
        assertTrue("setBackgroundColor(canvas)" in host)
        assertTrue("ескізу поверх WebView немає", "MessengerSkeleton()" in host)
        assertTrue("ескіз не знімається за першим кадром вебу", "painted.value = true" in host)
        assertTrue("неузятий WebView тече з активністю", "WebMessengerPrewarm.dropFor(this)" in src("MainActivity.kt"))
    }

    @Test
    fun `замок застосунку відчиняється на своєму потоці зі стартом процесу`() {
        // A52 (debug f7197125): act:onStart → act:onResume ~0,45 с системної
        // заставки — зашифрований слот замка відкривався на головному потоці.
        val app = src("PhantomApp.kt")
        val create = app.substringAfter("override fun onCreate()").substringBefore("StartMarks.mark(\"app:onCreate:end\")")
        assertTrue("onCreate не прогріває замок", "warmAppLock()" in create)
        val warm = app.substringAfter("private fun warmAppLock()").substringBefore("\n    }")
        assertTrue("прогрів не відкриває той самий AppLockStore", "AppLockStore.of(this)" in warm)
        assertTrue("прогрів на головному потоці нічого не дає", "Thread(" in warm)
    }
}
