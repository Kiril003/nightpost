package local.phantom.companion.variant

// Пакет НЕ називається `build`: перша спроба поклала цей файл у
// `…/companion/build/`, і `.gitignore` (правило `build/`, будь-яка глибина)
// мовчки не пустив його в коміт. Ворота на x86 були зелені без сторожа —
// рівно той клас вади, який сторож і стереже.

import java.io.File
import local.phantom.companion.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ДВІ ЗБІРКИ МУСЯТЬ БУТИ РОЗРІЗНЮВАНІ — сторож на те, чого не видно в коді.
 *
 * 06.09 власник написав: «звідки він взагалі ще є у телефоні?? виходячи з цього
 * я твоїм словам про зміни у застосунку не вірю». Вимір на A52 (RZ8RB1VSN2L):
 *
 *   local.phantom.companion        0.1.0-tier1+9c078584              05.09 08:19
 *   local.phantom.companion.debug  0.1.0-tier1+81896ab5-dirty-debug  06.09 03:27
 *
 * Дві збірки з РІЗНИМ кодом під однією назвою «PHANTOM» і одним значком. Хвилі
 * ставлять debug; він, відкриваючи «PHANTOM», відкривав учорашній продакшен —
 * і мав рацію, що не вірив словам про зміни.
 *
 * Три місця, де вони зливались, і три перевірки тут. Кожна червоніє від
 * ОДНОГО видалення: приберіть `src/debug/res/values/strings.xml` — падає
 * перша, приберіть `values/colors.xml` — друга, поверніть у маніфест
 * `android:scheme="phantom"` — третя.
 *
 * ЩО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ: що на апараті стоїть одна збірка. Це не
 * властивість дерева, і жоден тест її не втримає — лише зняття зайвого пакета
 * рукою власника.
 */
class TwoBuildsApartTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    /** Підпис у списку програм. Читаємо ФАЙЛ, бо саме джерельний набір `debug` перекриває `main`. */
    @Test
    fun `debug carries its own visible name, in both locales`() {
        val main = File(repoRoot(), "app/src/main/res/values-uk/strings.xml").readText()
        assertTrue("у main мусить лишатись коротке ім'я продукту",
            main.contains("""<string name="app_name">Nightpost</string>"""))

        for (dir in listOf("values-uk", "values")) {
            val f = File(repoRoot(), "app/src/debug/res/$dir/strings.xml")
            assertTrue("немає $dir/strings.xml у src/debug — debug знову зветься «Nightpost»", f.isFile)
            val name = Regex("""<string name="app_name">([^<]+)</string>""")
                .find(f.readText())?.groupValues?.get(1)
            assertNotEquals("підпис debug збігається з продакшеном — у списку програм їх не розрізнити",
                "Nightpost", name)
            assertEquals("Nightpost · debug", name)
        }
    }

    /** Значок. Колір тла — перший знак, смуга — другий, бо колір з'їдає монохромна тема. */
    @Test
    fun `debug carries its own launcher icon`() {
        val mainBg = Regex("""<color name="ic_launcher_background">([^<]+)</color>""")
            .find(File(repoRoot(), "app/src/main/res/values/colors.xml").readText())
            ?.groupValues?.get(1)
        val debugBg = Regex("""<color name="ic_launcher_background">([^<]+)</color>""")
            .find(File(repoRoot(), "app/src/debug/res/values/colors.xml").readText())
            ?.groupValues?.get(1)
        assertNotEquals("тло значка однакове — два значки поруч читаються як один", mainBg, debugBg)

        val icon = File(repoRoot(), "app/src/debug/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue("значок debug не несе смуги — у монохромній темі відмінності не лишиться",
            icon.contains("ic_launcher_foreground_debug"))
        assertTrue("монохромний значок debug не позначений — тематичні значки знову близнюки",
            icon.contains("ic_launcher_monochrome_debug"))
        assertTrue(File(repoRoot(), "app/src/debug/res/drawable/ic_launcher_badge_debug.xml").isFile)
    }

    /**
     * Посилання. Ловить кожна збірка своє — інакше система питає «яким
     * відкрити» або мовчки віддає намір продакшену.
     */
    @Test
    fun `deep links are caught by exactly one build`() {
        assertEquals("юніт-тести їдуть на debug-варіанті: схема мусить бути його",
            "phantom-debug", BuildConfig.LINK_SCHEME)
        assertNotEquals("phantom", BuildConfig.LINK_SCHEME)

        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue("фільтр більше не читає схему зі збірки — розведення скасовано",
            manifest.contains("""android:scheme="${'$'}{linkScheme}""""))
        assertEquals(
            "у маніфесті лишилась прибита схема `phantom` — обидва пакети знову ловлять те саме",
            0,
            Regex("""android:scheme="phantom"""").findAll(manifest).count(),
        )
    }

    /**
     * Мова посилань ПРОДУКТУ не зрушила. `phantom://invite/…` роздано людям і
     * лежить у чужих месенджерах; якби debug почав ГЕНЕРУВАТИ свою схему,
     * зроблене на ньому запрошення не відкрив би ніхто.
     */
    @Test
    fun `produced links stay on the product scheme`() {
        val net = File(repoRoot(), "core-net/src/main/java/local/phantom/companion/core/net/peer/PeerInvite.kt")
        assertTrue("""PeerInvite.LINK_SCHEME зрушив зі "phantom" — старі запрошення стали нечитні""",
            net.readText().contains("""const val LINK_SCHEME = "phantom"""))
    }
    /**
     * Друга половина тієї самої угоди, якої тут не міряли.
     *
     * Карбування лишається на схемі продукту (тест вище) — інакше запрошення,
     * зроблене на debug, не відкрив би ніхто. Але ЧИТАННЯ мусить приймати й
     * схему збірки: debug ловить `phantom-debug://invite/…` з власного
     * сповіщення за маніфестом, і якби приймання знало лише «phantom», такий
     * тап тихо не робив би нічого. Мовчазна бездія — та сама вада, що й напис,
     * який бреше.
     */
    @Test
    fun `reading accepts both the product and the variant scheme`() {
        val act = File(repoRoot(), "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        assertTrue(
            "isPhantomLink() більше не приймає схему продукту — debug не відкриє phantom://",
            act.contains("scheme == \"phantom\""),
        )
        assertTrue(
            "isPhantomLink() більше не приймає схему збірки — тап по рідному сповіщенню debug нічого не зробить",
            act.contains("scheme == BuildConfig.LINK_SCHEME"),
        )
        val net = File(repoRoot(), "core-net/src/main/java/local/phantom/companion/core/net/peer/PeerInvite.kt").readText()
        assertTrue(
            "codeInLink() знову міряє лише префікс продукту — посилання збірки читалось би як не наше",
            net.contains("fun codeInLink") && net.contains("anyScheme"),
        )
    }
}
