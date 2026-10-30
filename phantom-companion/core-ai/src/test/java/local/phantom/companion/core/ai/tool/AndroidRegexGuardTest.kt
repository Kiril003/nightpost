package local.phantom.companion.core.ai.tool

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож різниці JVM і Android у регулярних виразах.
 *
 * На Android регулярні вирази компілює ICU, і вбудованого прапорця `(?U)` там немає —
 * `Regex("(?U)…")` кидає PatternSyntaxException. JVM-тести цього не бачать, бо в Java
 * прапорець є. Так `MoneyTool` падав при першому зверненні на телефоні (03.10.2026, A52):
 * «передай Олені 5 USDC» тихо йшло в загальну відповідь замість чернетки переказу.
 */
class AndroidRegexGuardTest {

    @Test
    fun noJavaOnlyRegexFlagsInMainSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "test" }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
            .filter { f -> f.readText().let { "(?U)" in it || "UNICODE_CHARACTER_CLASS" in it } }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("На Android (ICU) (?U)/UNICODE_CHARACTER_CLASS не працюють: $offenders", offenders.isEmpty())
    }

    @Test
    fun moneyToolLoadsAndReadsCyrillicWithoutTheFlag() {
        val ask = MoneyTool.read("передай Олені 5 USDC")
        assertTrue("кирилиця має читатись без (?U): $ask", ask is MoneyAsk.Pay)
        assertTrue("засвідчення теж: ", MoneyTool.read("засвідчи це фото") is MoneyAsk.Seal)
    }
}
