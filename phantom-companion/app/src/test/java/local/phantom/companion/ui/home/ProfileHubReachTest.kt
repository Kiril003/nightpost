package local.phantom.companion.ui.home

import java.io.File
import local.phantom.companion.data.AppEventBus
import local.phantom.companion.ui.messenger.AgencySessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Старий екран налаштувань (SettingsRealm) розпущено в розділи Профілю.
 *
 * До 04.10 до нього вели лише двері «Для розробника» — сім дотиків по версії.
 * Тобто виняток з економії батареї, порядок мозків, вибір для заблокованого
 * екрана, «бачити екран», прибирання ключа й три голосові перемикачі були в
 * застосунку — і для людини їх не існувало. Цей сторож доводить, що кожен
 * із них тепер має рядок у розділі, який видно без розблокування, і що
 * переписані файли не тягнуть назад скла й літер старого образу.
 *
 * ЩО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ: він читає джерело, а не рендерить екран — у :app
 * немає ні Robolectric, ні compose-ui-test. Доводить, що виклик стоїть у тілі
 * потрібного розділу, а не що палець його дістав; погляд — знімками з апарата.
 */
class ProfileHubReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val app = File(root, "app/src/main/java/local/phantom/companion")

    private fun text(rel: String): String {
        val f = File(app, rel)
        if (!f.isFile) fail("немає $f — файл перейменували або перенесли")
        return f.readText()
    }

    /** Тіло від `{` після [from] до парної `}` — саме тіло, не файл. */
    private fun balancedBlockAfter(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
            i++
        }
        return text.substring(open)
    }

    private fun matchingParen(text: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return openIdx
    }

    private fun section(name: String): String {
        val src = text("ui/home/ProfileSections.kt")
        val open = Regex("""fun\s+$name\s*\(""").find(src)?.range?.last
            ?: run { fail("розділу $name немає в ProfileSections.kt"); 0 }
        return balancedBlockAfter(src, matchingParen(src, open))
    }

    @Test
    fun `маршрутів старого екрана, окремої памʼяті й старої назви чатів немає`() {
        val routes = text("navigation/Routes.kt")
        listOf("Settings", "Memory", "Messenger").forEach { gone ->
            assertFalse(
                "Routes.$gone повернувся — двері, які мали зникнути разом зі своїм екраном",
                Regex("""data object\s+$gone\s*:""").containsMatchIn(routes),
            )
        }
        val graph = text("navigation/PhantomNavGraph.kt")
        listOf("SettingsDestination(", "MemoryDestination(", "composable(Routes.Messenger.path)").forEach {
            assertFalse("навграф досі монтує $it", it in graph)
        }
        assertFalse("старий екран повернувся", File(app, "ui/settings/SettingsRealm.kt").exists())
        assertFalse("пошук старого екрана повернувся", File(app, "ui/settings/SettingsIndex.kt").exists())
    }

    @Test
    fun `розділи без дверей розробника тримають те, що жило лише на старому екрані`() {
        val privacy = section("PrivacySection")
        assertTrue("«Приватність» не має «бачити екран»", "ScreenAwarenessEntry(" in privacy)
        assertTrue("«Приватність» не має вибору для заблокованого екрана", "LockScreenRevealRows(" in privacy)

        val notifications = section("NotificationsSection")
        assertTrue("«Сповіщення» не мають винятку з економії батареї", "BatteryGrantEntry(" in notifications)

        val phantom = section("PhantomSection")
        assertTrue("ключа немає ні в «Додатково», ні дверей туди з Nightpost", "AiKeyEntry()" in section("AdvancedSection") && "ProfileSection.ADVANCED.route" in phantom)
        assertTrue("PHANTOM не має порядку мозків", "BrainOrderRows(" in phantom)

        // Розділи справді відчиняються без розблокування: у блоці `if (devOpen)`
        // Профілю стоїть лише «Для розробника».
        val tab = text("ui/home/ProfileSettings.kt")
        val devBlock = balancedBlockAfter(tab, tab.indexOf("if (facts.developer) {"))
        listOf("PRIVACY", "NOTIFICATIONS", "PHANTOM").forEach {
            assertTrue("на Профілі немає дверей у $it", "go(ProfileSection.$it.route)" in tab)
            assertFalse("двері у $it сховано за розблокуванням розробника", "ProfileSection.$it.route" in devBlock)
        }
    }

    @Test
    fun `прибрати ключ у PHANTOM гасить лише Gemini`() {
        val entry = text("ui/settings/AiKeyEntry.kt")
        assertTrue("у рядку ключа немає прибирання", "saveGeminiKey(null)" in entry)
        assertTrue("прибирання не перепитує — один дотик стирає ключ", "ProfileDialog(" in entry)
        assertFalse(
            "прибирання ключа Gemini знову гасить і токен HuggingFace",
            "saveHfToken" in entry || "clearKeys" in entry,
        )
        val hf = text("ui/settings/HfTokenEntry.kt")
        assertTrue("у токена HuggingFace немає власного прибирання", "saveHfToken(null)" in hf)
        assertFalse("прибирання токена HF гасить і ключ Gemini", "saveGeminiKey" in hf)
    }

    @Test
    fun `озвучка, перший голос і живий діалог мають рядки в PHANTOM`() {
        val phantom = section("PhantomSection")
        assertTrue("немає перемикача озвучки", "voice.setTtsEnabled(" in phantom)
        assertTrue("немає перемикача «заговорювати першим»", "voice.setProactiveEnabled(" in phantom)
        assertTrue(
            "живий діалог вмикається повз дозвіл на мікрофон",
            "voiceGate.requestThen { voice.setLiveDialogEnabled(true) }" in phantom,
        )
        assertTrue("живий діалог не знає про вимкнений мікрофон", "if (micMuted)" in phantom)
    }

    @Test
    fun `рядок «Що я про вас знаю» відкриває чат PHANTOM`() {
        val bus = AppEventBus()
        openPhantomChat(bus)
        assertEquals(
            "рядок памʼяті просить не той чат",
            AgencySessionManager.PHANTOM_AGENT_ID,
            bus.peerChatRequest.value,
        )
        val phantom = section("PhantomSection")
        assertTrue("рядок памʼяті не просить чату PHANTOM", "openPhantomChat(container.events)" in phantom)

        // Запит доходить: збирач у навграфі розгортає чат і вмикає вкладку Чати.
        val graph = text("navigation/PhantomNavGraph.kt")
        val collector = balancedBlockAfter(graph, graph.indexOf("container.peerChatRequest.collect"))
        assertTrue("збирач запиту чату не відкриває розмову", "PhantomVisits.openChat.value = id" in collector)
        assertTrue("збирач запиту чату не вмикає Чати", "HomeTab.CHATS" in collector)
    }

    @Test
    fun `одне імʼя для сховища — і одні двері в розділах`() {
        val uk = File(root, "app/src/main/res/values-uk/strings_home.xml").readText()
        val en = File(root, "app/src/main/res/values/strings_home.xml").readText()
        fun word(xml: String, key: String) =
            Regex("""<string name="$key">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
        listOf(uk, en).forEach { xml ->
            assertEquals("плитка й рядок «Приватності» звуть сховище по-різному", word(xml, "privacy_vault"), word(xml, "data_saved"))
        }
        assertFalse("«Дані» знову дублюють двері сховища", "Routes.Vault.path" in section("DataSection"))
        assertTrue("«Приватність» загубила двері сховища", "go(Routes.Vault.path)" in section("PrivacySection"))
    }

    @Test
    fun `MicPrivacyScreen будується на SubScreen і RowGroup без вигаданих власників`() {
        val mic = text("ui/settings/MicPrivacyScreen.kt")
        listOf("SubScreen(", "RowGroup", "InfoRow(", "SwitchRow(").forEach {
            assertTrue("MicPrivacyScreen не використовує $it", it in mic)
        }
        listOf(
            "GlassCard", "LocalPhantomTheme", "PhantomCaption", "OwnerRow",
            "mic_owner_wake", "mic_owner_noise", "Planned",
        ).forEach {
            assertFalse("MicPrivacyScreen досі містить $it", it in mic)
        }
        assertFalse(
            "MicOwnerStatus досі має Planned",
            local.phantom.companion.ui.settings.MicOwnerStatus.entries.any { it.name == "Planned" },
        )
    }

    @Test
    fun `RetentionBody використовує SettingsSegmented без скляної доріжки й без превʼю-абзацу`() {
        val retention = text("ui/settings/RetentionSettings.kt")
        assertTrue("RetentionBody не використовує SettingsSegmented", "SettingsSegmented(" in retention)
        assertTrue("RetentionBody загубив підтвердження стирання", "SettingsConfirmAction(" in retention)
        listOf(
            "RetentionDetents", "44.dp", "glassPanel",
            "settings_retention_body", "settings_retention_preview",
        ).forEach {
            assertFalse("RetentionSettings досі містить $it", it in retention)
        }
        val kit = text("ui/settings/SettingsDesignKit.kt")
        val rowDecl = kit.substringAfter("fun SettingsRow(").substringBefore(") {")
        assertFalse("SettingsRow досі приймає body", "body:" in rowDecl)
    }

    @Test
    fun `скорочення строку до приїзду лічильника не стирає без підтвердження`() {
        val retention = text("ui/settings/RetentionSettings.kt")
        assertFalse("порожній лічильник читається як «нема що стерти»", "perDetentDelete?.get(target) ?: 0" in retention)
        assertTrue("без лічильника скорочення не чекає підтвердження", "toDelete == null || toDelete > 0" in retention)
        assertTrue("озброєне підтвердження переходить на іншу ціль", "key(target)" in retention)
    }

    @Test
    fun `сховати текст не вдає перемикача, коли його тримає замок`() {
        val privacy = section("PrivacySection")
        assertFalse("перемикач, що не перемикається", "if (!lockState.enabled) lock.setHideNotificationText" in privacy)
        assertTrue("перемикач показує справжній стан, і під замком теж", "checked = lockState.hidesText" in privacy)
    }

    @Test
    fun `переписані файли не тягнуть старого образу`() {
        val files = listOf(
            "ui/home/ProfileSections.kt", "ui/home/ProfileRows.kt", "ui/home/ProfileDialog.kt",
            "ui/screens/PairScreen.kt",
            "ui/settings/AccessPane.kt", "ui/settings/AiKeyEntry.kt", "ui/settings/BackgroundLocationEntry.kt",
            "ui/settings/BrainPane.kt", "ui/settings/DangerZonePane.kt", "ui/settings/DirectoryHandleEntry.kt",
            "ui/settings/FalsePositionEntry.kt", "ui/settings/HfTokenEntry.kt", "ui/settings/MicPrivacyScreen.kt",
            "ui/settings/OperatorStanceEntry.kt", "ui/settings/PairEntry.kt", "ui/settings/RetentionSettings.kt",
            "ui/settings/SettingsDesignKit.kt",
        )
        val forbidden = listOf(
            "LocalPhantomTheme", "LocalStateAccent", "GlassCard", "PhantomKicker",
            "AlertDialog(", "material.icons", "Color(0x", "fontSize =",
        )
        val offenders = files.flatMap { rel ->
            val code = text(rel).lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            forbidden.filter { it in code }.map { "$rel: $it" }
        }
        assertEquals("стара фурнітура в новому образі:\n${offenders.joinToString("\n")}", emptyList<String>(), offenders)
    }
}
