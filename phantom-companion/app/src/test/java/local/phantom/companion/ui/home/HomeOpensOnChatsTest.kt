package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Застосунок відкривається списком розмов (збірка 17, рішення власника 05.10:
 * «цей екран важливіший за звʼязок?» — про домівку помічника 02.10).
 *
 * Ланцюжок від старту до першого кадру: Bootstrap → Home → [HomeScreen] з
 * `initialTab = CHATS` → вкладка «Розмови», у якій живе WebView месенджера →
 * корінь вебу на телефоні — список розмов з полем «Спитати Nightpost» над ним.
 * Гаманець — четверта вкладка з тією самою поверхнею, що й WalletActivity.
 * `:app` нічого не рендерить (немає compose-ui-test), тож звіряємо джерело й
 * артефакт — бандл, що справді їде в APK.
 */
class HomeOpensOnChatsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun file(path: String) = File(root, path)
    private fun src(path: String) = file("app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `перша вкладка - Розмови, гаманець - своя вкладка`() {
        val bar = src("ui/home/HomeBottomBar.kt")
        val tabs = Regex("""TabSpec\(HomeTab\.(\w+), [^,]+, R\.string\.(\w+)\)""").findAll(bar)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            listOf("CHATS" to "home_tab_chats", "MAP" to "home_tab_map", "WALLET" to "home_tab_wallet", "PROFILE" to "home_tab_profile"),
            tabs,
        )
        assertFalse("перша вкладка знову помічник", "home_tab_phantom" in bar)
        assertTrue("initialTab: HomeTab = HomeTab.CHATS" in src("ui/home/HomeScreen.kt"))
        listOf("values-uk" to listOf("Розмови", "Мапа", "Гаманець", "Я"), "values-cs" to listOf("Konverzace", "Mapa", "Peněženka", "Vy"), "values" to listOf("Chats", "Map", "Wallet", "You")).forEach { (dir, words) ->
            val xml = file("app/src/main/res/$dir/strings_home.xml").readText()
            listOf("chats", "map", "wallet", "profile").zip(words).forEach { (key, word) ->
                assertTrue("$dir: home_tab_$key", "<string name=\"home_tab_$key\">$word</string>" in xml)
            }
        }
    }

    @Test
    fun `перша вкладка не повертає назву Сьогодні`() {
        listOf("values-uk" to "Сьогодні", "values" to "Today", "values-cs" to "Dnes").forEach { (dir, old) ->
            assertFalse("$dir: стара назва першої вкладки", "<string name=\"home_tab_chats\">$old</string>" in file("app/src/main/res/$dir/strings_home.xml").readText())
        }
    }

    @Test
    fun `вкладка Гаманець малює справжній гаманець, а Профіль його більше не веде`() {
        val home = src("ui/home/HomeScreen.kt")
        assertTrue("вкладка WALLET не малює гаманця", Regex("""HomeTab\.WALLET ->[\s\S]{0,300}walletEmbed\?\.Pane\(\)""").containsMatchIn(home))
        // Двері гаманця реєструють результат вікна MWA — лише полем, до STARTED.
        assertTrue(Regex("""val walletEmbed = local\.phantom\.companion\.core\.solana\.WalletEmbed\(this\)""").containsMatchIn(src("MainActivity.kt")))
        val wallet = file("core-solana/src/main/java/local/phantom/companion/core/solana/WalletActivity.kt").readText()
        assertTrue("WalletEmbed.Pane не малює WalletScreen", Regex("""fun Pane\(\)[\s\S]{0,200}WalletScreen\(door""").containsMatchIn(wallet))
        assertFalse("«Гаманець» досі рядком у Профілі", src("ui/home/ProfileTab.kt").contains("profile_wallet"))
    }

    @Test
    fun `заглушка гаманця зі Сховищем у Профіль не вертається`() {
        val sections = src("ui/home/ProfileSections.kt")
        assertFalse(sections.contains("wallet_open_vault"))
        assertFalse(sections.contains("WALLET("))
        listOf("values-uk", "values").forEach { dir ->
            assertFalse(file("app/src/main/res/$dir/strings_home.xml").readText().contains("wallet_empty_title"))
        }
    }

    @Test
    fun `заставка й значок несуть Естафету Nightpost`() {
        // docs/brand/assets/symbol/nightpost-symbol-24.svg
        val mark = "M11 2A8 8 0 0 0 11 18ZM13 6A8 8 0 0 1 13 22Z"
        assertTrue(mark in file("app/src/main/res/drawable/ic_splash_mark.xml").readText())
        assertTrue(mark in file("app/src/main/res/drawable/ic_launcher_foreground.xml").readText())
        assertTrue(mark in file("app/src/main/res/drawable/ic_launcher_monochrome.xml").readText())
    }

    @Test
    fun `бандл у APK відкривається списком розмов з однією капсулою пошуку й питання`() {
        val js = file("app/src/main/assets/webmsgr/assets").listFiles { f -> f.name.endsWith(".js") }.orEmpty()
        assertTrue("бандла немає", js.isNotEmpty())
        val text = js.joinToString("\n") { it.readText() }
        assertTrue("капсули «Пошук або спитати Nightpost» немає в бандлі (забули sync_web_messenger.sh?)", "search-ask-voice" in text)
        assertFalse("окреме поле «Спитати Nightpost» над пошуком досі в бандлі (збірка 18 його зняла)", "ask-nightpost" in text)
        assertFalse("домівка помічника досі в бандлі (забули sync_web_messenger.sh?)", "phantom-home" in text)
    }
}
