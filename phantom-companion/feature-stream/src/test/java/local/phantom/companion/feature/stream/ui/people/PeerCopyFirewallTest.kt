package local.phantom.companion.feature.stream.ui.people

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Жоден рядок ресурсів не сміє обіцяти захист від посередника там, де звірки
 * ще не було.
 *
 * Пів року копія казала, що шість цифр «зупиняють посередника» — а шість цифр
 * підбирались за мільйон спроб. Слово було неправдою не тому, що хтось збрехав,
 * а тому, що речення про захист стояло в стані «не звірено» і ніхто цього не
 * помітив. Тому правило структурне: згадати третього між двома можна або в
 * реченні, яке зʼявляється ЛИШЕ як наслідок звірки (збіг чи розбіжність), або
 * називаючи його небезпекою — без жодного слова про захист.
 *
 * Читає ресурси всіх модулів як сирий ввід-вивід — невидимо для up-to-date
 * Gradle. Ганяти з `--rerun-tasks`, як робить scripts/verify.sh.
 */
class PeerCopyFirewallTest {

    /** Слова про третього між двома. */
    private val middleman = Regex(
        "посередник|посередин|хтось третій|між вами хтось|man.in.the.middle|in the middle|" +
            "somebody (is )?between|someone (is )?between|third party|\\bMITM\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Слова, що обіцяють: після них третій «не може». */
    private val assurance = Regex(
        "захищ|ніхто не|не може|не зможе|неможлив|безпечн|protect|secure|nobody|no one|cannot|can't|safe from",
        RegexOption.IGNORE_CASE,
    )

    /** Показуються лише як наслідок звірки — збіг із екрана або розбіжність. */
    private val afterOutcome = setOf(
        "peer_card_mismatch_title",
        "peer_card_mismatch_why",
        "peer_card_mismatch_mine_why",
        "peer_card_proven_why",
    )

    /** Показуються ДО звірки: третього можна назвати небезпекою, не більше. */
    private val namesTheRisk = setOf(
        "peer_card_unverified_why",
        "peer_card_shared_why",
        "peer_card_matched_why",
        "peer_card_key_changed_why",
        "peer_chat_key_changed_why",
        "chatwindow_why_default",
        "chatwindow_why_handed",
        "chatwindow_why_changed",
    )

    private data class Res(val file: String, val name: String, val text: String)

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun strings(): List<Res> = repoRoot().listFiles()!!
        .filter { it.isDirectory && File(it, "src/main/res").isDirectory }
        .flatMap { module ->
            File(module, "src/main/res").walkTopDown()
                .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" && it.parentFile.name.startsWith("values") }
                .flatMap { f -> entry.findAll(f.readText()).map { Res("${module.name}/${f.parentFile.name}/${f.name}", it.groupValues[1], it.groupValues[2]) } }
                .toList()
        }

    @Test
    fun `no resource string promises protection from a middleman without a verification outcome`() {
        val all = strings()
        assertTrue("resources not found — the guard is reading nothing", all.size > 100)
        val offenders = all.filter { middleman.containsMatchIn(it.text) }.mapNotNull { r ->
            when {
                r.name in afterOutcome -> null
                r.name in namesTheRisk -> if (assurance.containsMatchIn(r.text)) "${r.file}: ${r.name} names the risk AND promises" else null
                else -> "${r.file}: ${r.name} speaks of a middleman outside a verification outcome"
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the number is never called six digits again`() {
        // The six digits were ~20 bits; the surfaces now show 24 or 48. A string
        // that still says «шість цифр» is a screen telling people to compare the
        // wrong thing.
        val six = Regex("шість цифр|шести цифр|six digits", RegexOption.IGNORE_CASE)
        val stale = strings()
            .filter { it.name.startsWith("peer_") || it.name.startsWith("people_") || it.name.startsWith("chatwindow_") }
            .filter { six.containsMatchIn(it.text) }
            .map { "${it.file}: ${it.name}" }
        assertTrue(stale.joinToString("\n"), stale.isEmpty())
    }

    @Test
    fun `the allowlists still point at strings that exist`() {
        val names = strings().map { it.name }.toSet()
        val gone = (afterOutcome + namesTheRisk).filterNot { it in names }
        assertTrue("allowlisted names no longer defined: $gone", gone.isEmpty())
    }

    @Test
    fun `the guard can redden`() {
        assertTrue(middleman.containsMatchIn("Між вами хтось третій"))
        assertTrue(middleman.containsMatchIn("Somebody is in the middle"))
        assertTrue(assurance.containsMatchIn("зʼєднання захищене від посередника"))
        assertTrue(assurance.containsMatchIn("nobody can sit in the middle"))
    }
}
