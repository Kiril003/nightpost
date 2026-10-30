package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * On 2026-07-28 the state pill printed `accent.state.name` — the enum constant
 * — as the single word naming what PHANTOM was doing. It read «GHOST» on a
 * Ukrainian screen while the vault two taps away called that state «ПРИМАРА».
 * Diagnostics did the same with a sensor status and an error severity.
 *
 * Every other i18n gate here looks at string literals, so none of them could
 * see this: an identifier is not a literal, and a constant name carries no
 * Cyrillic to trip the English-in-Ukrainian check. The defect is structural,
 * so the rule is too — a `text`/`label`/`title` argument may not be fed a
 * `.name` off an enum.
 *
 * [dataNames] are the properties that legitimately hold a human name: a file,
 * a place, a person, a saved region. Those are content and are meant to show.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate.
 */
class EnumNameAsLabelFirewallTest {

    private val roots = listOf(
        "app/src/main", "core-design/src/main",
        "feature-stream/src/main", "feature-onboarding/src/main",
        "feature-precision/src/main", "feature-ambient/src/main",
    )

    /**
     * `x.name` where x is one of these reads a human name, not a constant.
     *
     * `it` was on this list and should not have been: it is the commonest
     * receiver in Kotlin and says nothing about what it holds. A lambda-form
     * label — `label = { it.name.take(3) }` — therefore passed the gate twice
     * over, once for the shape and once for the receiver.
     */
    private val dataNames = setOf(
        "region", "file", "poi", "peer", "place", "entity", "manifest",
        "tmpl", "data", "spec", "p", "profile", "device", "thread", "pack",
        "group", "folder", "person", "room", "item", "s", "line", "st", "stop",
        // «state» — це стан екрана профілю, а не константа переліку: там лежить
        // імʼя, яке ввів оператор. «project» — назва проєкту, теж його слова.
        "state", "project",
        // «location» — досьє місця в листі: name там це назва, яку написала
        // людина («Phantom Hub»), а не константа. Сусідній `location.category`
        // вже читається з рядків, тож підозра була саме на name — і вона знята.
        "location",
        // «me» — профіль у вкладці «Профіль»: name там displayName активного профілю.
        "me",
        // «candidate» — заклад для зустрічі: name там назва місця.
        "candidate",
    )

    /**
     * Sites that genuinely mean to print a constant. Only one: a debug-only
     * state-forcing harness behind `BuildConfig.DEBUG`, whose whole purpose is
     * to name the enum values themselves. It never reaches a release build.
     *
     * The anchor is a line number, so it moves whenever the file above it
     * does — 231 became 337 when the settings hub was regrouped. Re-point it;
     * do not widen the rule to the whole file.
     */
    /**
     * Pinned by CONTENT, not by line. It used to be «SettingsRealm.kt:337»,
     * and adding a setting above it silently un-exempted the line — a ratchet
     * that fires because something moved teaches people to bump numbers.
     *
     * 04.10 (profile-hub): порожньо. Той налагоджувальний перемикач стану жив
     * у SettingsRealm і пішов разом із ним — у розділ «Для розробника» його не
     * перенесено: стан системи малює стару тему, якої новий образ не має.
     */
    private val deliberate = emptySet<String>()

    private val labelArg = Regex(
        """\b(?:text|label|title|value)\s*=\s*\{?\s*([A-Za-z_][\w.]*)\.name\b""",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    @Test
    fun `no label is fed an enum constant name`() {
        val root = repoRoot()
        val offenders = roots.map { File(root, it) }.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.contains("/ui/") || it.path.contains("/component/") }
                .flatMap { f ->
                    f.readText().lineSequence().withIndex().mapNotNull { (i, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapNotNull null
                        val m = labelArg.find(line) ?: return@mapNotNull null
                        val receiver = m.groupValues[1].substringAfterLast('.')
                        val site = "${f.name}:${i + 1}"
                        val byContent = "${f.name}|${trimmed.trim()}"
                        if (receiver in dataNames || byContent in deliberate) null
                        else "$site  ${trimmed.take(90)}"
                    }
                }
        }

        assertEquals(
            "a label is showing an enum constant instead of a word:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nMap it to a string resource. If the receiver genuinely holds a " +
                "human name, add it to `dataNames`.",
            emptyList<String>(),
            offenders,
        )
    }
}
