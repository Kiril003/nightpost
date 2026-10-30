package local.phantom.companion.license

import java.io.File
import local.phantom.companion.core.license.LicenseReason
import local.phantom.companion.ui.settings.SignatureFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Чужий підпис мусить ДОЛІТАТИ до людини з телефоном.
 *
 * Заміряно 04.09 на підписаному релізному пакунку: IntegritySentry рахував усі
 * чотири прапорці правильно, LicenseService віддавав звіт, і в усьому
 * застосунку той звіт мав РІВНО ОДНОГО споживача — рядок, який просто не клав
 * сертифікат у ворота. Перепакована й переписана копія працювала звичайно, а
 * власник телефона не мав жодного способу дізнатись, що вона не наша.
 *
 * Тому сторож перевіряє не те, що звірка підпису правильна (це вже робив
 * `inspect`), а те, що ЛАНЦЮГ цілий: прапорець -> факти -> екран -> маршрут.
 *
 * Поведінку доводить ЗАПУСК тієї самої чистої функції, якою рахує пристрій:
 * [IntegritySentry.report] винесено з `inspect` саме для цього. Останню ланку —
 * виклик у композиції й оголошення маршруту — доводить скан джерела, і причина
 * та сама, що в DeveloperBuildTakesNoUpdatesTest: у :app немає ні Robolectric,
 * ні `isReturnDefaultValues`, тож ні Context, ні Compose тут не запускаються.
 */
class ForeignSignatureIsVisibleTest {

    /** Справжня сума релізного сертифіката, звірена з пакунком 04.09. */
    private val ours = "d249e0fb73743d219047fc18ece1eaffdf193892f1c859a6b7af46cd98be4357"
    private val theirs = "0f".repeat(32)

    private fun report(
        actual: String?,
        expected: String,
        debuggable: Boolean = false,
        storeBuild: Boolean = false,
        installer: String? = null,
        emulator: Boolean = false,
    ) = IntegritySentry.report(
        actualSignatureSha256 = actual,
        expectedSignatureSha256 = expected,
        debuggable = debuggable,
        storeBuild = storeBuild,
        installer = installer,
        emulator = emulator,
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію з feature-stream/ не знайдено") as Nothing
    }

    private fun source(relPath: String): String {
        val f = File(repoRoot(), relPath)
        assertTrue("$relPath не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    private val sentry = "app/src/main/java/local/phantom/companion/license/IntegritySentry.kt"
    private val service = "app/src/main/java/local/phantom/companion/license/LicenseService.kt"
    private val screen = "app/src/main/java/local/phantom/companion/ui/settings/LicenseScreen.kt"
    private val graph = "app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt"
    private val routes = "app/src/main/java/local/phantom/companion/navigation/Routes.kt"
    // 04.10 (profile-hub): старий екран налаштувань розпущено. Рядок «Ліцензія»
    // тепер у «Допомозі» Профілю, і саме він мусить називати чужий підпис.
    private val hub = "app/src/main/java/local/phantom/companion/ui/home/ProfileSections.kt"

    @Test
    fun `our own signature raises nothing and shows nothing`() {
        val clean = report(actual = ours, expected = ours)
        assertFalse(clean.tampered)
        assertNull("на нашій же збірці екран мусить мовчати", SignatureFacts.of(clean))

        // Сума пакунка приходить від PackageManager у нижньому регістрі, а в
        // BuildConfig її вписує людина. Різниця в регістрі — не підміна.
        val shouted = report(actual = ours.uppercase(), expected = ours)
        assertFalse("регістр видали за чужий підпис", shouted.tampered)
        assertNull(SignatureFacts.of(shouted))
    }

    @Test
    fun `a foreign signature reaches the screen with both fingerprints`() {
        val swapped = report(actual = theirs, expected = ours)
        assertTrue(swapped.tampered)

        val facts = SignatureFacts.of(swapped)
        assertNotNull("прапорець є, а показувати нема чого — ланцюг обірвано", facts)
        assertEquals("показано не той відбиток, що знайдено", theirs, facts!!.found)
        assertEquals("немає з чим звіряти — очікуваний відбиток загубився", ours, facts.expected)
    }

    @Test
    fun `a build with nothing to compare against stays silent`() {
        // Debug: BuildConfig.EXPECTED_SIGNATURE порожній. Без цієї гілки кожна
        // збірка з ноутбука кричала б про підміну сама на себе.
        val debug = report(actual = theirs, expected = "", debuggable = true)
        assertFalse(debug.tampered)
        assertNull(SignatureFacts.of(debug))

        // PackageManager не віддав підпису: невідомо не означає чуже.
        val blind = report(actual = null, expected = ours)
        assertFalse(blind.tampered)
        assertNull(SignatureFacts.of(blind))
    }

    @Test
    fun `the other three flags are not a foreign signature`() {
        // Емулятор, відлагоджувальна збірка й встановлення повз Play — це не
        // підміна підпису, і жодна з них не сміє відкрити цю секцію: хибне
        // спрацювання на польовій збірці коштує дорожче за зламану копію.
        val noisy = report(
            actual = ours,
            expected = ours,
            debuggable = true,
            storeBuild = true,
            installer = "com.android.shell",
            emulator = true,
        )
        assertTrue("прапорці мали б бути — інакше перевірка нічого не каже", noisy.suspicious)
        assertFalse(noisy.tampered)
        assertNull("секція відкрилась не на тому прапорці", SignatureFacts.of(noisy))
    }

    // ---- ПРОВОДКА. Скан джерела, не запуск: причина — у KDoc класу. ----

    @Test
    fun `the device runs the very function this test proves`() {
        val text = source(sentry)
        // substringAfter повертає ВЕСЬ текст, коли роздільника немає, — без цієї
        // перевірки зникнення inspect читалося б як «усе на місці».
        assertTrue("inspect зник із IntegritySentry", text.contains("fun inspect("))
        val inspect = text.substringAfter("fun inspect(").substringBefore("fun signingSha256")
        assertTrue(
            "inspect більше не кличе report( — доведене тут не стосується пристрою",
            inspect.contains("report("),
        )
        assertFalse(
            "звірку підпису переписали всередині inspect, повз перевірену функцію",
            inspect.contains("Flag.ForeignSignature"),
        )
        assertTrue(
            "звіт більше не несе відбитків — показувати на екрані стане нічого",
            text.contains("val signatureSha256") && text.contains("val expectedSha256"),
        )
    }

    @Test
    fun `the service still compares against the baked-in signature`() {
        assertTrue(
            "сервіс перестав подавати очікуваний підпис — прапорець згасне назавжди",
            source(service).contains("expectedSignatureSha256 = BuildConfig.EXPECTED_SIGNATURE"),
        )
    }

    @Test
    fun `the screen asks the real report, not a literal`() {
        val text = source(screen)
        assertTrue(
            "екран рахує факти повз SignatureFacts — доведене тут його не стосується",
            text.contains("SignatureFacts.of(service.integrity)"),
        )
        assertFalse(
            "звіт зібрано в самому екрані — показ відірвано від справжньої звірки",
            text.contains("IntegritySentry.Report("),
        )
        listOf(
            "R.string.license_signature_section",
            "R.string.license_signature_foreign",
            "R.string.license_signature_meaning",
            "R.string.license_signature_found",
            "R.string.license_signature_expected",
        ).forEach {
            assertTrue("екран не показує $it", text.contains(it))
        }
        assertTrue("знайдений відбиток не показано", text.contains("text = facts.found"))
        assertTrue("очікуваний відбиток не показано", text.contains("text = facts.expected"))
    }

    @Test
    fun `the licence screen is reachable from the live graph`() {
        assertTrue(
            "маршрут settings/license зник із Routes",
            source(routes).contains("""SettingsLicense : Routes("settings/license")"""),
        )

        val nav = source(graph)
        assertTrue(
            "маршрут settings/license зник із графа — екран став недосяжним",
            nav.contains("composable(Routes.SettingsLicense.path)"),
        )
        val entry = nav.substringAfter("composable(Routes.SettingsLicense.path)")
            .substringBefore("composable(Routes.SettingsDataLicenses.path)")
        assertTrue(
            "маршрут settings/license більше не веде на LicenseScreen — екран став мертвим",
            entry.contains("LicenseScreen("),
        )
        assertTrue(
            "розділи Профілю більше не маршрут — до «Допомоги» нема чим дійти",
            nav.contains("composable(local.phantom.companion.ui.home.ProfileSection.ROUTE)") &&
                nav.contains("ProfileSectionScreen(section, navController)"),
        )

        val help = source(hub).substringAfter("private fun HelpSection(", "")
            .substringBefore("internal fun helpLicenseValue(")
        assertTrue("у Профілі немає «Допомоги»", help.isNotEmpty())
        assertTrue(
            "рядок «Ліцензія» в Допомозі більше не веде на екран ліцензії",
            help.contains("{ go(Routes.SettingsLicense.path) }"),
        )
    }

    @Test
    fun `the help licence row says the cause instead of the consequence`() {
        // Чужий підпис важить більше за рівень: рівень і так «Вільний», бо
        // сертифікат до воріт не доходить.
        LicenseReason.values().forEach { reason ->
            assertEquals(
                "за чужого підпису рядок каже наслідок ($reason), а не причину",
                local.phantom.companion.R.string.license_signature_row,
                local.phantom.companion.ui.home.helpLicenseValue(tampered = true, reason = reason),
            )
            assertEquals(
                "на своїй збірці рядок мусить казати стан ліцензії",
                LicenseService.explain(reason),
                local.phantom.companion.ui.home.helpLicenseValue(tampered = false, reason = reason),
            )
        }
    }

    @Test
    fun `the help licence row asks the real report`() {
        val help = source(hub).substringAfter("private fun HelpSection(", "")
            .substringBefore("internal fun helpLicenseValue(")
        assertTrue(
            "рядок «Ліцензія» не питає про підпис — факт лишився на екран глибше",
            help.contains("helpLicenseValue(container.license.integrity.tampered, verdict.reason)"),
        )
    }

    @Test
    fun `every sentence exists in both languages`() {
        val keys = listOf(
            "license_signature_section",
            "license_signature_foreign",
            "license_signature_meaning",
            "license_signature_found",
            "license_signature_expected",
            "license_signature_row",
        )
        listOf("app/src/main/res/values-uk", "app/src/main/res/values").forEach { dir ->
            val xml = source("$dir/strings.xml")
            keys.forEach { key ->
                assertTrue(
                    "у $dir немає рядка $key — цією мовою екран промовчить",
                    xml.contains("name=\"$key\""),
                )
            }
        }
    }
}
