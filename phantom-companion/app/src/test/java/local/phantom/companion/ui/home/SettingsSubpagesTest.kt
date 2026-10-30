package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Обхід r19 на A52: підсторінки «Ви» — стіни тексту, ключ Gemini на виду,
 * імʼя для пошуку в «Пристроях», «Проба · 59 дн.» на рядку компʼютера,
 * «Українською» на англійському інтерфейсі.
 *
 * Сторож читає джерело й ресурси, а не рендерить екран (у :app немає
 * compose-ui-test): доводить, що рядок стоїть у тілі потрібного розділу.
 */
class SettingsSubpagesTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища")

    private val app = File(root, "app/src/main/java/local/phantom/companion")
    private val res = File(root, "app/src/main/res")

    private fun text(rel: String): String {
        val f = File(app, rel)
        if (!f.isFile) fail("немає $f")
        return f.readText()
    }

    private fun block(src: String, from: Int): String {
        val open = src.indexOf('{', from)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return src.substring(open, i + 1) }
            }
        }
        return src.substring(open)
    }

    private fun fn(src: String, name: String): String {
        val at = Regex("""fun\s+$name\s*\(""").find(src)?.range?.first ?: return ""
        var depth = 0
        var i = src.indexOf('(', at)
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        return block(src, i)
    }

    private fun section(name: String) = fn(text("ui/home/ProfileSections.kt"), name)

    private fun strings(dir: String): Map<String, String> =
        File(res, dir).listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { f ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()

    private val en by lazy { strings("values") }
    private val uk by lazy { strings("values-uk") }

    @Test
    fun `імені для пошуку в Пристроях немає, а Приватність і плитка ведуть до нього`() {
        assertFalse("«Пристрої» знову просять імʼя для пошуку", "DirectoryHandleEntry(" in section("DevicesSection"))
        assertTrue("імʼя для пошуку зникло з налаштувань зовсім", "DirectoryHandleEntry()" in section("PrivacySection"))
        val tab = text("ui/home/ProfileTab.kt")
        assertFalse("плитка імені веде в «Пристрої», де імені вже немає", "HANDLE -> ProfileSection.DEVICES.route" in tab)
    }

    @Test
    fun `ключ Gemini не на сторінці Nightpost, а за рядком Додатково`() {
        val phantom = section("PhantomSection")
        assertFalse("ключ знову на сторінці Nightpost", "AiKeyEntry(" in phantom)
        assertTrue("зі сторінки Nightpost немає дверей у «Додатково»", "go(ProfileSection.ADVANCED.route)" in phantom)
        assertTrue("«Додатково» не тримає ключа", "AiKeyEntry()" in section("AdvancedSection"))
        val screen = section("ProfileSectionScreen")
        assertTrue("маршрут «Додатково» нікуди не веде", "ProfileSection.ADVANCED -> AdvancedSection(" in screen)
        listOf(en, uk).forEach { assertTrue("немає рядка phantom_advanced", "phantom_advanced" in it) }
    }

    @Test
    fun `на рядку компʼютера немає плашки проби`() {
        val pair = text("ui/settings/PairEntry.kt")
        assertFalse("«Проба · N дн.» знову на рядку пристрою", "look_pair_trial" in pair || "trialDaysLeft" in pair)
    }

    @Test
    fun `ключ не показано ані знаком — лише підключено чи ні`() {
        listOf("ui/settings/AiKeyEntry.kt", "ui/settings/HfTokenEntry.kt").forEach {
            assertFalse("$it знову показує хвіст ключа", "takeLast(" in text(it))
        }
        listOf("ai_key_body_set", "hf_key_body_set").forEach { id ->
            listOf(en, uk).forEach { assertFalse("$id вставляє частину ключа", "%1" in it[id].orEmpty()) }
        }
    }

    @Test
    fun `під імʼям для пошуку один рядок пояснення`() {
        assertFalse("рядок імені склеює два пояснення", "+ \"\\n\" +" in text("ui/settings/DirectoryHandleEntry.kt"))
    }

    @Test
    fun `розділи Приватність, Сповіщення, Nightpost і Мова не малюють підрядкових пояснень`() {
        listOf("PrivacySection", "NotificationsSection", "PhantomSection", "LanguageSection").forEach { name ->
            val body = section(name)
            assertFalse("$name досі передає body у рядки", "body =" in body)
            assertFalse("$name досі малює Explainer", "Explainer(" in body)
        }
        val access = text("ui/settings/AccessPane.kt")
        listOf("NotificationGrantEntry", "AlertDeliveryRow", "UkraineAlertsRow", "BatteryGrantEntry").forEach { name ->
            val body = fn(access, name)
            assertTrue("не знайдено $name у AccessPane.kt", body.isNotEmpty())
            assertFalse("$name на сторінці Сповіщення досі передає body у рядки", "body =" in body)
            listOf(
                "look_battery_body",
                "settings_perm_notif_detail",
                "settings_alert_delivery_none_detail",
                "settings_alert_delivery_quiet_detail",
                "settings_alert_delivery_caveat",
                "uaAlertsBody(",
            ).forEach { prose ->
                assertFalse("$name досі малює пояснювальне речення $prose", prose in body)
            }
        }
        val phantom = section("PhantomSection")
        assertTrue("PhantomSection не показує стан розуму через InfoRow і mindValue", "InfoRow(" in phantom && "mindValue(" in phantom)
        val dir = text("ui/settings/DirectoryHandleEntry.kt")
        listOf("dir_row_on", "dir_row_on_plain", "dir_row_expired").forEach {
            assertFalse("DirectoryHandleEntry досі використовує підрядкове речення $it", it in dir)
        }
        assertEquals("Not published", en["dir_row_off"])
        assertEquals("Не опубліковано", uk["dir_row_off"])
    }

    @Test
    fun `голос відповідей названо чесно`() {
        val body = en["look_tts_body"].orEmpty()
        assertTrue("англійський підпис не каже, який голос: $body", "Ukrainian voice" in body)
        assertTrue("український підпис не каже, який голос: ${uk["look_tts_body"]}", "голос" in uk["look_tts_body"].orEmpty())
    }

    private val entries = mapOf(
        "LockScreenRevealRows" to "ui/settings/AccessPane.kt",
        "ScreenAwarenessEntry" to "ui/settings/AccessPane.kt",
        "BackgroundLocationEntry" to "ui/settings/BackgroundLocationEntry.kt",
        "DirectoryHandleEntry" to "ui/settings/DirectoryHandleEntry.kt",
        "PairEntry" to "ui/settings/PairEntry.kt",
        "AiKeyEntry" to "ui/settings/AiKeyEntry.kt",
        "BrainOrderRows" to "ui/settings/BrainPane.kt",
        "brainOrderBody" to "ui/settings/BrainPane.kt",
    )

    private fun pageIds(): Map<String, Set<String>> {
        val idRe = Regex("""(?<![A-Za-z])R\.string\.(\w+)""")
        return listOf("PrivacySection", "DevicesSection", "PhantomSection", "AdvancedSection").associateWith { name ->
            val body = section(name)
            val parts = listOf(body) + entries.filterKeys { "$it(" in body || it == "brainOrderBody" && "BrainOrderRows(" in body }
                .map { (fnName, file) -> if (fnName == "DirectoryHandleEntry") text(file) else fn(text(file), fnName) }
            parts.flatMap { p -> idRe.findAll(p).map { it.groupValues[1] }.toList() }.toSet()
        }
    }

    private fun words(s: String) = s.replace(Regex("""%\d\$[sd]"""), "X").split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) }

    @Test
    fun `кожен рядок підсторінок — один рядок, а не абзац`() {
        val ids = pageIds()
        assertTrue("сторож осліп: не знайшов рядків", ids.values.sumOf { it.size } > 40)
        val long = ids.flatMap { (page, set) ->
            set.flatMap { id ->
                listOf("en" to en[id], "uk" to uk[id]).mapNotNull { (lang, v) ->
                    v?.takeIf { words(it) > MAX_WORDS }?.let { "$page/$lang/$id (${words(it)}): $it" }
                }
            }
        }
        assertTrue("довше за $MAX_WORDS слів:\n" + long.joinToString("\n"), long.isEmpty())
    }

    @Test
    fun `на підсторінках немає заборонених слів`() {
        val banned = Regex("""(?i)\b(crew|phantom|checkpoint|light|blackout|darkness|bluetooth|mesh)\b|екіпаж|блокпост""")
        val hits = pageIds().values.flatten().toSet().flatMap { id ->
            listOfNotNull(en[id], uk[id]).filter { banned.containsMatchIn(it) }.map { "$id: $it" }
        }
        assertTrue("заборонені слова:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `рядки строку історії з великої літери в усіх мовах`() {
        val ids = listOf(
            "settings_retention_title", "settings_retention_detent_year", "settings_retention_detent_forever",
            "settings_retention_status_empty", "settings_retention_wipe", "settings_retention_wipe_armed",
        )
        val cs = strings("values-cs")
        val low = listOf("en" to en, "uk" to uk, "cs" to cs).flatMap { (lang, m) ->
            ids.mapNotNull { id -> m[id]?.takeIf { v -> v.first { it.isLetter() }.isLowerCase() }?.let { "$lang/$id: $it" } }
        }
        assertTrue("з малої літери:\n" + low.joinToString("\n"), low.isEmpty())
    }

    @Test
    fun `вибір строку — один контрол із рамкою, а не пʼять кнопок`() {
        val body = fn(text("ui/settings/SettingsDesignKit.kt"), "<T> SettingsSegmented")
        assertTrue("немає тіла SettingsSegmented", body.isNotEmpty())
        assertTrue("одна рамка навколо всіх сегментів", ".border(" in body && "selectableGroup()" in body)
        assertFalse("сегменти розсунуто проміжками — знову окремі кнопки", "spacedBy" in body)
        assertEquals("лише зовнішній контур обрізає форму", 1, Regex("""\.clip\(""").findAll(body).count())
    }

    @Test
    fun `посилання, імʼя для пошуку й хмарна копія — одна група`() {
        val s = section("PrivacySection")
        val at = s.indexOf("link_preview_row")
        val start = s.lastIndexOf("RowGroup", at)
        assertTrue("немає групи з передпереглядом посилань", at > 0 && start >= 0)
        val group = block(s, start)
        assertTrue("імʼя для пошуку окремою плиткою", "DirectoryHandleEntry()" in group)
        assertTrue("хмарна копія окремою плиткою", "CloudBackupRows(" in group)
    }

    private companion object {
        const val MAX_WORDS = 12
    }
}
