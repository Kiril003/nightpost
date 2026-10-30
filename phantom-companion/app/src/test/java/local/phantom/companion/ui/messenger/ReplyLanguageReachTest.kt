package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «I couldn't find “olena” among the people in your chats.» — посеред
 * української домівки (збірка 16, A52 з англійською системою).
 *
 * Механізм вибору мови доводить `ReplyLanguageTest` у :core-data (Robolectric,
 * англійська система → український рядок). Тут — досяжність: КОЖНА дорога,
 * якою помічник пише в розмову, бере рядки з контексту `ReplyLanguage`, а не з
 * `applicationContext` (мова системи). Читає джерела, бо Android-контексту в
 * JVM-тестах :app немає.
 */
class ReplyLanguageReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun src(path: String) = File(repoRoot(), "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `the assistant's own words come from the reply language`() {
        val agency = src("ui/messenger/AgencySessionManager.kt")
        assertTrue(
            "str() помічника знову читає мову системи",
            Regex("""private fun str\(resId: Int, vararg args: Any\): String =\s+replyContext\(\)\.getString""").containsMatchIn(agency),
        )
        assertTrue(agency.contains("ReplyLanguage.context(container.applicationContext)"))
        assertTrue(
            "відповідь про гроші (money_nobody…) знову з applicationContext",
            agency.contains("heard?.words(replyContext())") && !agency.contains("words(container.applicationContext)"),
        )
    }

    @Test
    fun `the self speaks the reply language too`() {
        val self = src("self/PhantomSelfAndroid.kt")
        assertTrue(self.contains("words(ReplyLanguage.context(app), say)"))
        assertTrue("відповіді памʼяті й досі з мови системи", !Regex("""words\(app, """).containsMatchIn(self))
    }

    @Test
    fun `council members are not named in English in Kotlin`() {
        val english = listOf("System Designer", "Red Team / Challenger", "Performance Profiler", "Lead Synthesizer")
        // Лише рядкові літерали в коді: коментарі й приписи моделі («Ти — … (Red Team)»)
        // людина не читає, а підпис автора й рядок колегії — читає.
        val literal = english.associateWith { Regex("\"[^\"\\n]{0,4}" + Regex.escape(it)) }
        val offenders = repoRoot().resolve("app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                english.filter { name -> code.any { literal.getValue(name).containsMatchIn(it) } }.map { "${f.name}: $it" }
            }
            .toList()
        assertEquals("англійські імена учасників колегії в коді", emptyList<String>(), offenders)
    }

    @Test
    fun `the reply that started this is Ukrainian by default and English only in values`() {
        val res = File(repoRoot(), "app/src/main/res")
        val uk = File(res, "values-uk/strings_money.xml").readText()
        val en = File(res, "values/strings_money.xml").readText()
        assertTrue(Regex("""name="money_nobody">Не знайшов""").containsMatchIn(uk))
        assertTrue(Regex("""name="money_nobody">I couldn""").containsMatchIn(en))
    }
}
