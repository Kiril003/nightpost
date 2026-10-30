package local.phantom.companion.core.design.layout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The island floats over every non-flow route. A screen that insets only with
 * `systemBarsPadding()` therefore runs its content to the system nav bar and
 * lets the island sit on top of it — the operator loses whatever was last in
 * the column, which on a short screen is nothing and on a full one is the
 * primary action.
 *
 * Found three times by hand on 2026-07-27: the twelve chambers had no island
 * at all, щоденник's «+ нове рішення» was drawn underneath it, and шепіт's
 * explainer card was sliced in half. Three of one thing is a class.
 *
 * `LocalBottomChromeInset` is published at the NavHost and defaults to 0.dp
 * where no island exists, so reading it is always safe. It cannot simply be
 * applied globally: the map and the dialogue deliberately draw *under* the
 * island, which is why this is a per-screen rule and therefore a ratchet.
 *
 * ## 2026-08-13 — four ways this gate was green while blind
 *
 * 1. It knew `systemBarsPadding()` and not its sibling `navigationBarsPadding()`.
 *    That is how the Continuum's own Orb — «the one persistent affordance in
 *    the whole OS», and on the World stratum the only way out of the map — sat
 *    20dp off the bottom, dead under a 64dp rail, without ever being counted.
 * 2. It only looked at files whose path contained `/ui/`. `PrecisionScreen.kt`
 *    and `AutopilotHudOverlay.kt` are neither. The population is now every file
 *    that declares a `@Composable`, which no package rename can quietly empty —
 *    and [the population is asserted][`the sweep never inspects nothing`] so a
 *    rename that DOES empty it fails here instead of passing everywhere.
 * 3. It never asked about the keyboard. A screen may honour the rail's reserve
 *    and still run its list under the IME (`HistorySearchScreen`, reported from
 *    use), or add both and leave a rail-high dead band above the keyboard
 *    (`PeerChatScreen`). The contract at the head of `BottomChromeInset.kt` is
 *    `max(ime, navBar + reserve)`, never the sum — [imeBlind] is that ratchet.
 * 4. It grepped for one literal, `LocalBottomChromeInset`. The moment a screen
 *    migrates to `bottomChromeInsets()` that literal is gone, so the guard's
 *    coverage would have shrunk with every fix while staying green — a test
 *    that cannot fail. [answered] now knows all three honest answers, and
 *    [doubleCounted] watches the failure mode the NEW api introduces: taking
 *    the union and then adding the reserve again on top of it.
 *
 * ## 2026-10-02 — the island is gone
 *
 * The shell is now Чати · Мапа · Профіль with a bottom bar that is a real
 * `Column` sibling of the tab content, and `LocalBottomChromeInset` is
 * `0.dp` everywhere. Nothing floats over a screen any more, so the shell's own
 * files inset with system bars legitimately — see [shellDir]. The ratchets stay:
 * the reserve plumbing is still read by 31 files, and the day something floats
 * again they must already be honest.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate or a stale green will hide a count move.
 */
class BottomChromeInsetFirewallTest {

    // 2026-07-27 — today's ground, after щоденник and шепіт were fixed. Every
    // one of these is a screen that would clip its own last row if its content
    // ever reached the bottom; most are short today, which is exactly why they
    // are latent rather than visible. Lower this as they are migrated.
    // −7 with the test itself: every settings screen. −10 straight after:
    // the remaining chambers. Two files left the count by being exempt
    // rather than fixed — see [flowFiles].
    private val uninsetCeiling = 0

    // 2026-08-13 — screens that own a text field, honour the rail and have
    // never heard of the keyboard. HistorySearchScreen was the one reported
    // from use (results ran under the IME while the query was being typed);
    // these are the same shape, found by sweeping for it. Lower as they move
    // to `bottomChromeInsets()`.
    // −2 on 2026-08-14: both halves of the add-a-person path. PeopleScreen took
    // the union; PeerHandshakeSheet took `imePadding()` alone, because its host
    // hides the island and owes its reserve nothing.
    // 0 on 2026-10-02: the last three left with the chat stream and the island.
    private val imeBlindCeiling = 0

    // The union already contains the reserve. Adding it again is the same
    // dead band, arrived at from the other side. Never above zero.
    private val doubleCountedCeiling = 0

    // The population this gate inspects. A floor, not a fixed count: files are
    // added and deleted daily. It exists so that a package move, a rename or a
    // broken root cannot empty the input and leave every check trivially green.
    // 2026-10-04: 150 → 125. system-and-subtraction deleted ~55 dead composable
    // files (feature-stream ui/widget, ui/facet, ui/items, ui/live, PhantomMenu;
    // core-design OrbView/Lumiere/StatePill); the gate measured 136 after. The
    // floor sits ~10 below so ordinary daily deletions stay green while an
    // emptied root (0, or a lost module of ~40) still goes red.
    private val inspectedFloor = 125

    private val uiRoots = listOf(
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
        // 2026-08-13 — both were outside the sweep entirely. `feature-car`
        // declares no Composable at all today; listed so the first one added
        // there is not invisible on the day it lands.
        "feature-car/src/main",
        "feature-precision/src/main",
    )

    /** Roots that carry UI today and must not silently stop doing so. */
    private val rootsThatMustNotEmpty = setOf(
        "app/src/main",
        "feature-stream/src/main",
    )

    /**
     * Flows render before the island exists (bootstrap, onboarding, pairing,
     * the profile launcher), so they own their whole footprint.
     */
    private val flowFiles = setOf(
        "OnboardingScreen.kt",
        "OnboardingUiHelpers.kt",
        "PairClaimingScreen.kt",
        "PairScreen.kt",
        "ProfileLauncherScreen.kt",
        // A full-screen Dialog renders above the island, not beneath it.
        "WorkspaceCanvas.kt",
    )

    /**
     * The home shell lays its bottom bar out beside the tab content, not over
     * it: the bar consumes the navigation inset, the call screen takes the
     * whole window. They owe a reserve that does not exist.
     */
    private val shellDir = "/app/src/main/java/local/phantom/companion/ui/home/"

    /**
     * Material3's `ModalBottomSheet` and Compose's `Dialog` each attach their
     * OWN window (`ModalBottomSheetDialogWrapper` is a `ComponentDialog`), so
     * their content composites above the island and their scrim covers the
     * rail. Content written for such a window owes the reserve nothing.
     *
     * Detected by the file naming one — except where host and content live in
     * different files, which has to be said out loud.
     */
    private val ownWindowFiles = setOf(
        // Hosted by MapSheetHost's ModalBottomSheet, one file over.
        "PlaceDossierSheet.kt",
        // Обидва — вміст `ModalBottomSheet`, оголошеного в SheetHost.kt:
        // власне вікно поверх острівця, і M3 сам інсетить його системні смуги.
        "TransitHubSheet.kt",
        "TransitHubTabs.kt",
        // Вміст `MapSheet` (ModalBottomSheet у MapSheetScaffold.kt): «Поруч» з
        // MapChrome.kt і «У чат» із SheetHost.kt — те саме власне вікно, що й досьє.
        "AroundYouSheet.kt",
        "PlacePage.kt",
    )

    /**
     * `padding(bottom = N.dp)` on its own. PresentScreen pinned its «Чат /
     * Голос» row 32dp off the bottom with no systemBarsPadding anywhere in the
     * file, so the first version of this test could not see it and the island
     * sat on both buttons.
     */
    private val bottomPad = Regex("""padding\(\s*bottom\s*=\s*[0-9]+\.dp""")

    /** Both spellings. Knowing only one is how the Orb hid here for weeks. */
    private val systemInset = Regex("""(systemBarsPadding|navigationBarsPadding)\(\)""")

    /**
     * Own window ⇒ above the island ⇒ nothing owed.
     *
     * The lookbehind is the whole point: `Dialog(` also matched `AlertDialog(`,
     * and an alert is a small centred box that leaves the screen behind it
     * fully composed — it buys the HOST screen no exemption whatsoever.
     */
    private val ownWindow = Regex("""ModalBottomSheet|(?<![A-Za-z])Dialog\(""")

    /**
     * The three honest answers to «the island floats over me»: read the
     * published reserve, take the union with the IME, or take the island off
     * the screen for as long as you are on it.
     */
    private val answered = Regex("""LocalBottomChromeInset|bottomChromeInsets|HideBottomChrome""")

    private val textField = Regex("""BasicTextField|OutlinedTextField|[^A-Za-z]TextField\(""")

    /** Any acknowledgement that a keyboard exists. */
    private val imeAware = Regex("""imePadding\(\)|WindowInsets\.ime|bottomChromeInsets""")

    /** `bottomChromeInsets()` already includes the reserve and the IME. */
    private val addsReserveAgain = Regex("""imePadding\(\)|LocalBottomChromeInset""")

    /**
     * Прокрутний список — і є «екранний рівень», незалежно від того, чи
     * згадував файл системні смуги.
     *
     * ## 2026-09-20 — пʼята сліпота цього сторожа
     *
     * Живі тестери на A52 (збірка 14): «у пошуку зрізано "Лікарня /
     * Травмпункт" і "Банкомат / Готівка"; на екрані пакетів — цілий рядок».
     * Екран пакетів — це `PrecisionScreen.kt`, і він був ЗЕЛЕНИЙ тут: жодного
     * `systemBarsPadding()`, а `padding(bottom = N.dp)` шукалось лише у файлах
     * `*Screen.kt`, яких у нього теж не було на потрібному рядку. Іншими
     * словами, [offenders] питав «ти інсетиш системні смуги, але не рейку?» —
     * і екран, що не інсетить НІЧОГО, проходив як невинний.
     *
     * Питання правильне інше: у тебе є список, який добігає до низу вікна?
     * Тоді острівець сідає на його останній рядок, і байдуже, що ще написано
     * у файлі. Свідомі винятки лишаються ті самі — потоки ([flowFiles]) і
     * власні вікна ([ownWindow], [ownWindowFiles]), бо над ними рейки немає.
     *
     * ## Чого ця перевірка НЕ доводить — назване вголос
     *
     * Вона читає РОЗМІТКУ, не пікселі. Вона бачить, що резерв ОГОЛОШЕНО, і не
     * бачить, чи він правильної висоти й чи не зʼїв його предок, який спожив
     * інсети вище по дереву (`windowInsetsPadding` віднімає спожите — це
     * ефект дерева модифікаторів, і з JVM він невидимий; сусідній
     * [BottomChromeArithmeticTest] каже те саме про себе). «Рядок видно оком»
     * тут не доводиться і доведено бути не може — лише пристроєм.
     *
     * Друга пляма: одиниця огляду — ФАЙЛ. Список в одному файлі, чий резерв
     * ставить хост в іншому, порахується порушником (лікується записом в
     * [ownWindowFiles] з поясненням, як зроблено для транспортного хаба), а
     * зворотний випадок — резерв у файлі, але на іншому списку — пройде
     * зеленим. Обидва свідомі: точніше можна лише з деревом композиції.
     */
    private val scroller =
        Regex("""LazyColumn\(|LazyVerticalGrid\(|LazyVerticalStaggeredGrid\(""")

    private val scrollersThatIgnoreTheRail: Int = 0

    // Widened once to any file with bottom padding and it caught thirteen —
    // sheets, chat widgets, map capsules — none of which are screen chrome. A
    // ratchet that cries wolf gets its ceiling raised to silence it, which is
    // the failure it exists to prevent. Restricted to *Screen.kt: a widget
    // under the island is the feed's problem, a screen under it is this one's.

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    /** Every file that declares UI, whatever package somebody moved it to. */
    private fun composablesIn(relPath: String): List<File> {
        val dir = File(repoRoot(), relPath)
        if (!dir.isDirectory) {
            // ПЕРЕДУМОВА, а не дрібниця: якщо теки немає, обхід поверне порожньо,
            // «порушників» не буде — і сторож стане ЗЕЛЕНИМ ні про що саме тоді,
            // коли пакет перейменували чи перенесли, тобто в мить найімовірнішого
            // дрейфу. Мовчазна порожнеча тут гірша за відсутність сторожа.
            fail("сторож дивиться в порожнє: не існує $relPath")
        }
        val kt = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // Друга передумова: тека може існувати й СПОРОЖНІТИ — вміст переїхав,
        // каталог лишився. Міряти це треба на СИРОМУ обході, до фільтра
        // `@Composable`: нуль ПІСЛЯ фільтра — законний стан (`feature-car`
        // несе 8 файлів і жодного Compose, і стоїть у списку саме для того,
        // щоб перший, який там колись зʼявиться, не був невидимим), а нуль ДО
        // фільтра означає, що коріння спорожніло. Перша редакція цього сторожа
        // міряла після фільтра й червоніла на здоровому дереві — той самий
        // клас помилки, який вона ловить.
        if (kt.isEmpty()) fail("тека є, жодного .kt: $relPath")
        return kt.filter { it.readText().contains("@Composable") }
    }

    private fun candidates(): List<File> =
        uiRoots.flatMap { composablesIn(it) }.filter { it.name !in flowFiles }

    private fun offenders(): List<String> =
        candidates()
            .filter { it.name !in ownWindowFiles }
            .filterNot { it.invariantSeparatorsPath.contains(shellDir) }
            .filter { f ->
                val text = f.readText()
                if (ownWindow.containsMatchIn(text)) return@filter false
                val screenLevel = systemInset.containsMatchIn(text) ||
                    (f.name.endsWith("Screen.kt") && bottomPad.containsMatchIn(text))
                screenLevel && !answered.containsMatchIn(text)
            }
            .map { it.name }
            .sorted()

    /**
     * Імпорт — не відповідь.
     *
     * Перша редакція цієї перевірки лишилась зеленою, коли з `PrecisionScreen`
     * прибрали САМ `contentPadding`, бо рядок `import …LocalBottomChromeInset`
     * лишився на місці й [answered] його бачив. Сторож, який рахує імпорт за
     * використання, зеленіє рівно на тому русі, проти якого стоїть.
     */
    private fun bodyOf(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("import ") }
            .map { line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            .joinToString("\n")

    private fun scrollBlind(): List<String> =
        candidates()
            .filter { it.name !in ownWindowFiles }
            .filter { f ->
                val text = f.readText()
                if (ownWindow.containsMatchIn(text)) return@filter false
                val body = bodyOf(text)
                scroller.containsMatchIn(body) && !answered.containsMatchIn(body)
            }
            .map { it.name }
            .sorted()

    private fun imeBlind(): List<String> =
        candidates()
            .filter { f ->
                val text = f.readText()
                answered.containsMatchIn(text) &&
                    textField.containsMatchIn(text) &&
                    !imeAware.containsMatchIn(text)
            }
            .map { it.name }
            .sorted()

    /**
     * Читає КОД, не коментарі.
     *
     * Спіймано 17 серп.: у `MapScreen.kt` коментар пояснював, чому резерв тут
     * НЕ додається — і назвав `bottomChromeInsets()` словами. Сканер порахував
     * згадку за виклик і дав червоне на файлі, який усе робить правильно.
     * Сторож, який читає пояснення як порушення, вчить не писати пояснень.
     * Сусідній firewall кирилиці пропускає рядки-коментарі з тієї ж причини.
     */
    private fun doubleCounted(): List<String> =
        candidates()
            .filter { f ->
                val text = codeOnly(f.readText())
                text.contains("bottomChromeInsets") && addsReserveAgain.containsMatchIn(text)
            }
            .map { it.name }
            .sorted()

    /** Прибирає рядкові коментарі й тіла блокових, лишаючи самий код. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .map { line ->
                val t = line.trimStart()
                when {
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") -> ""
                    else -> line.substringBefore("//")
                }
            }
            .joinToString("\n")

    @Test
    fun `the sweep never inspects nothing`() {
        val seen = candidates()
        assertTrue(
            "this gate inspected ${seen.size} composable files, floor is $inspectedFloor.\n" +
                "  A rename or a moved package emptied the input. Every other check " +
                "in this class is trivially green while that is true, which is worse " +
                "than the bug they exist to catch.",
            seen.size >= inspectedFloor,
        )
        for (root in rootsThatMustNotEmpty) {
            assertTrue(
                "root '$root' yielded no @Composable file at all — the path is stale.",
                composablesIn(root).isNotEmpty(),
            )
        }
    }

    @Test
    fun `screens that ignore the island's reserve never grow`() {
        val found = offenders()

        assertEquals(
            "screens insetting with system bars but not the island's reserve " +
                "is ${found.size}, ceiling says $uninsetCeiling.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  ↑ rose: this screen's last row will sit under the island. Add " +
                "`.windowInsetsPadding(bottomChromeInsets())`, or `HideBottomChrome()` " +
                "if the surface takes the screen for itself.\n" +
                "  ↓ fell: good. Set uninsetCeiling = ${found.size} to keep the ground.",
            found.size,
            uninsetCeiling,
        )
    }

    @Test
    fun `scrolling surfaces that reserve nothing at the bottom never grow`() {
        val found = scrollBlind()

        assertEquals(
            "surfaces that own a lazy list and answer the island with nothing " +
                "is ${found.size}, ceiling says $scrollersThatIgnoreTheRail.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  ↑ rose: this list runs to the bottom of the window and the " +
                "island sits on its last row — reported from the device on " +
                "2026-09-19 as a whole pack row eaten. Add " +
                "`contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + …)`, " +
                "or `.windowInsetsPadding(bottomChromeInsets())` where the list " +
                "also owns a text field.\n" +
                "  ↓ fell: good. Set scrollersThatIgnoreTheRail = ${found.size}.",
            found.size,
            scrollersThatIgnoreTheRail,
        )
    }

    @Test
    fun `screens that type and forget the keyboard never grow`() {
        val found = imeBlind()

        assertEquals(
            "screens that honour the rail, own a text field and never mention " +
                "the IME is ${found.size}, ceiling says $imeBlindCeiling.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  ↑ rose: with the keyboard up this screen either runs its last " +
                "rows under the IME, or reserves both and leaves a rail-high dead " +
                "band above it. `bottomChromeInsets()` takes the per-axis MAX.\n" +
                "  ↓ fell: good. Set imeBlindCeiling = ${found.size} to keep the ground.",
            found.size,
            imeBlindCeiling,
        )
    }

    @Test
    fun `the union is never added to the reserve it already contains`() {
        val found = doubleCounted()

        assertEquals(
            "files taking `bottomChromeInsets()` AND adding the reserve or the " +
                "IME again is ${found.size}, ceiling says $doubleCountedCeiling.\n" +
                found.joinToString("\n") { "    $it" } +
                "\n  ↑ rose: the union already carries navigationBars + the reserve " +
                "+ the IME, per axis, as a MAX. Adding `imePadding()` or the reserve " +
                "on top of it restores the exact dead band the union removed.",
            found.size,
            doubleCountedCeiling,
        )
    }
}
