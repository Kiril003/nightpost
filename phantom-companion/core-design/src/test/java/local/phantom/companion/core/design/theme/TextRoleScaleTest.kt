package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The operator's text-size dial is a shipped setting, stored, clamped and
 * threaded into `PhantomTheme(fontScale = …)`; the ViewModel's own comment says
 * it moves "the whole UI". It moved MaterialTheme's roles and nothing else,
 * because PhantomTextRoles writes its own `sp` and never read the value —
 * so PhantomKicker, PhantomCaption, PhantomDataText and PhantomProse, which
 * are most of the words on most screens, sat at one size forever.
 *
 * A composable's resolved size cannot be read without a Compose host, so this
 * pins the shape of the code instead: every size in that file goes through
 * `scaled(…)`, with one documented exception.
 */
class TextRoleScaleTest {

    private val source: String by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root not found") as Nothing
        File(
            root,
            "core-design/src/main/java/local/phantom/companion/core/design/theme/PhantomTextRoles.kt",
        ).readText()
    }

    @Test
    fun `every role's size passes through the dial`() {
        val sizes = Regex("""(fontSize|lineHeight)\s*=\s*([^,\n]+)""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            // The parameter declaration itself is a default, not a set size.
            .filterNot { it.second.startsWith("TextUnit") }
            .toList()

        assertTrue("PhantomTextRoles sets no sizes at all", sizes.isNotEmpty())

        val unscaled = sizes.filterNot { it.second.startsWith("scaled(") }
        assertEquals(
            "unscaled sizes: $unscaled. PhantomGlyph is the only one allowed — " +
                "it is set to the Material icon size beside it, and icons do " +
                "not move with the dial. Everything else must be scaled(…).",
            listOf("fontSize" to "17.sp"),
            unscaled,
        )
    }

    @Test
    fun `tracking is left alone`() {
        // phantomTypography treats letter-spacing as an optical constant. The
        // roles must not disagree with the type scale about that.
        val tracking = Regex("""letterSpacing\s*=\s*([^,\n]+)""")
            .findAll(source).map { it.groupValues[1].trim() }.toList()
        assertTrue(
            "letter-spacing must not be scaled: $tracking",
            tracking.none { it.startsWith("scaled(") },
        )
    }

    @Test
    fun `the dial the roles read is the one the theme clamps`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        val theme = File(
            dir!!,
            "core-design/src/main/java/local/phantom/companion/core/design/theme/PhantomTheme.kt",
        ).readText()
        // The rule is that a clamped value reaches the roles, not that it is
        // spelled inline: the dial now also feeds LocalDensity, so it is
        // computed once and provided to both.
        assertTrue(
            "PhantomTheme must clamp the stored scale before anything reads it",
            theme.contains("fontScale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)"),
        )
        assertTrue(
            "PhantomTheme must provide LocalPhantomFontScale from that clamped value",
            theme.contains("LocalPhantomFontScale provides scale") ||
                theme.contains(
                    "LocalPhantomFontScale provides fontScale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)",
                ),
        )
        assertTrue(
            "the dial must reach every sp in the tree, not just the four roles",
            theme.contains("LocalDensity provides"),
        )
    }

    /**
     * Зріз БЕЗ трикрапки не читається як зріз.
     *
     * Знайдено на апараті 19.09: у списку пакiв усі 22 рядки виходили як
     * «МІСЦЯ Й ВУЛИЦІ КРАЇНИ · Австрія — місця й» — повний текст у дереві
     * доступності є, на склі його рубає по гліфу. Винен не екран: `PhantomDataText`
     * оголошував `maxLines = 1` разом із `TextOverflow.Clip`, тобто СТАНДАРТ ролі
     * ріже мовчки, і кожен із сотень викликів успадковував це, нічого не
     * оголошуючи. Те саме стояло в `PhantomBody` і `PhantomProse` — там воно
     * латентне лише доти, доки виклик не задасть свій `maxLines`.
     *
     * Ellipsis не вмикає нічого зайвого: крапки зʼявляються рівно тоді, коли
     * текст не вліз, тобто там, де інакше був би мовчазний зріз. Роль, яка
     * хоче саме Clip (лічильник, що не сміє смикнутись), каже це на виклику.
     */
    @Test
    fun `no role clips its text without saying so`() {
        val defaults = Regex("""overflow:\s*TextOverflow\s*=\s*TextOverflow\.(\w+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        assertTrue("PhantomTextRoles declares no overflow default at all", defaults.isNotEmpty())
        assertEquals(
            "roles whose DEFAULT is a silent cut: $defaults. A clamped line that " +
                "ends mid-glyph reads as the whole value — that is how a pack " +
                "list shipped 22 rows of «Австрія — місця й». Default to " +
                "Ellipsis; a call site that truly wants Clip passes it itself.",
            emptyList<String>(),
            defaults.filter { it != "Ellipsis" },
        )
    }
}
