package local.phantom.companion.core.design.look

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Перший запуск і профіль — на мові форми 1.2, і назад у старе скло не зʼїжджають.
 *
 * Власник (03.10): «найгірше — місця, де лишилось СТАРЕ: такий контраст між
 * старим мотлохом і новим». Чотирнадцять файлів цього пакета — крок імені,
 * сітка профілів, анкета, сховище, інструменти й питання про дозвіл, питання
 * про мікрофон, ліцензія з карткою оновлення і пояснення платного — говорили
 * старим склом: LocalPhantomTheme, GlassCard, моно-капс кікери, серифна
 * корона, стокові Material-значки, нескінченні «дихання» на власному годиннику.
 *
 * Сторож читає КОД (рядки-коментарі пропускає: пояснення, ЧОМУ старе пішло,
 * мусить мати право назвати його) і червоніє на кожному старому слові в
 * кожному з файлів. На cloud/beta b629fd08, до цієї хвилі, він червоний на
 * всіх чотирнадцяти.
 *
 * Чого він НЕ доводить: що екран гарний. Це доводять кадри до/після в нотатках
 * хвилі та апарат. Він доводить лише, що жоден із цих файлів більше не тягне
 * старий словник — і не потягне непомітно.
 *
 * GATE LAW (спільний із рештою firewall-ів): читає чужі модулі як сирі файли,
 * повз up-to-date Gradle. Прогін лише з `--rerun-tasks`.
 */
class FirstRunProfileLookFirewallTest {

    private val files = listOf(
        "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/OnboardingScreen.kt",
        "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/NameStep.kt",
        "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/OnboardingUiHelpers.kt",
        "app/src/main/java/local/phantom/companion/ui/launcher/ProfileLauncherScreen.kt",
        "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/vault/VaultScreen.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/vault/VaultRows.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/vault/VaultParts.kt",
        "app/src/main/java/local/phantom/companion/ui/settings/ToolsSettingsScreen.kt",
        "app/src/main/java/local/phantom/companion/ui/tools/ToolConsentHost.kt",
        "feature-stream/src/main/java/local/phantom/companion/feature/stream/voice/RecordAudioPermissionGate.kt",
        "app/src/main/java/local/phantom/companion/ui/settings/LicenseScreen.kt",
        "app/src/main/java/local/phantom/companion/ui/UpdateNotice.kt",
        "core-design/src/main/java/local/phantom/companion/core/design/license/LicenseNotice.kt",
    )

    /**
     * Старий словник. Кожне слово — те, що реально стояло в цих файлах на
     * cloud/beta, а не уявний ворог: тема й акцент старого скла, скляні картки
     * й аркуш, моно-капс ролі, серифна корона, стокові значки й діалоги,
     * нескінченний годинник, сира палітра повз токени.
     */
    private val oldWords: List<Pair<String, Regex>> = listOf(
        "LocalPhantomTheme" to Regex("""\bLocalPhantomTheme\b"""),
        "LocalStateAccent" to Regex("""\bLocalStateAccent\b"""),
        "GlassCard" to Regex("""\bGlass(Card|Level)\b"""),
        "PhantomSheet" to Regex("""\bPhantomSheet\("""),
        "ChamberHeader" to Regex("""\bChamberHeader\b"""),
        "PhantomKicker" to Regex("""\bPhantomKicker\b"""),
        "PhantomEditorial" to Regex("""\bPhantomEditorial\w*"""),
        "PhantomDataText" to Regex("""\bPhantomDataText\b"""),
        "PhantomMono" to Regex("""\bPhantomMono\b"""),
        "FontFamily.Monospace" to Regex("""FontFamily\.Monospace"""),
        "Icons." to Regex("""\bIcons\."""),
        "rememberInfiniteTransition" to Regex("""\brememberInfiniteTransition\b"""),
        "PhantomPalette" to Regex("""\bPhantomPalette\b"""),
        "AlertDialog(" to Regex("""\bAlertDialog\("""),
        "OutlinedButton/TextButton" to Regex("""\b(OutlinedButton|TextButton)\("""),
        "PhantomBackPill" to Regex("""\bPhantomBackPill\b"""),
        "SettingsSectionCard" to Regex("""\bSettingsSectionCard\b"""),
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з feature-stream/ не знайдено") as Nothing
    }

    /** Лише код: рядки-коментарі пояснюють, що пішло, і мусять мати право назвати його. */
    private fun codeLines(text: String): List<Pair<Int, String>> =
        text.lines().withIndex()
            .filterNot { (_, line) ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .map { (i, line) -> i + 1 to line }

    /** Чиста функція — її доводять на завідомо поганому рядку нижче. */
    private fun offendersIn(name: String, text: String): List<String> =
        codeLines(text).flatMap { (n, line) ->
            oldWords.mapNotNull { (word, rx) ->
                if (rx.containsMatchIn(line)) "$name:$n  $word  ←  ${line.trim().take(90)}" else null
            }
        }

    @Test
    fun `жоден файл першого запуску й профілю не говорить старим склом`() {
        val root = repoRoot()
        val found = files.flatMap { rel ->
            val f = File(root, rel)
            assertTrue("сторож дивиться в порожнє: немає $rel", f.isFile)
            offendersIn(f.name, f.readText())
        }
        assertEquals(
            "Старий словник повернувся в перший запуск чи профіль. Мова форми 1.2: " +
                "LocalPhantomColors, PhantomText, PhantomSpace/PhantomRadius/PhantomSize, lc_* значки.",
            emptyList<String>(),
            found,
        )
    }

    /** ДОКАЗ ЧЕРВОНОГО: кожне старе слово ловиться в коді й не ловиться в коментарі. */
    @Test
    fun `сторож ловить старе слово в коді і пропускає його в коментарі`() {
        val bad = """
            val theme = LocalPhantomTheme.current
            GlassCard(level = GlassLevel.Card) { PhantomKicker(text = "x") }
            Icon(imageVector = Icons.Outlined.Check, contentDescription = null)
            val pulse = rememberInfiniteTransition(label = "ob-bg")
            AlertDialog(onDismissRequest = {})
            TextButton(onClick = {}) { }
            PhantomEditorialTitle(text = "x")
        """.trimIndent()
        val hits = offendersIn("Bad.kt", bad).map { it.substringAfter("  ").substringBefore("  ") }.toSet()
        listOf(
            "LocalPhantomTheme", "GlassCard", "PhantomKicker", "Icons.",
            "rememberInfiniteTransition", "AlertDialog(", "OutlinedButton/TextButton", "PhantomEditorial",
        ).forEach { assertTrue("сторож сліпий до «$it»: $hits", it in hits) }

        val comment = """
            // Тут стояв GlassCard з LocalPhantomTheme — пішов.
             * Стоковий AlertDialog з моно-капсом пішов.
        """.trimIndent()
        assertEquals("коментар, що пояснює зняте, не порушення", emptyList<String>(), offendersIn("Ok.kt", comment))
    }

    @Test
    fun `список файлів не спорожнів`() {
        assertEquals("пакет — чотирнадцять файлів; зміна числа — це зміна сторожа", 14, files.distinct().size)
    }
}
