package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Одна межа Дня й Ночі на весь застосунок.
 *
 * Тема Living розвʼязується двічі: рідним боком ([resolveLivingTheme] за
 * [DayPhase]) і вебом (`themeOfPhase` у services/theme.ts, з фази, яку дає
 * `PhantomBridge.getThemePhase`). До збірки 16 вони розходились у сутінках:
 * рідний бік уже малював Ніч, веб іще День — і щовечора месенджер стояв
 * кремовою плямою в чорній оболонці. Цей тест читає веб-функцію як текст і
 * звіряє полярність кожної фази.
 *
 * GATE LAW: читає сусіднє дерево сирим файловим I/O. Ганяти з `--rerun-tasks`.
 */
class LivingWebParityTest {

    private fun webTheme(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root with feature-stream/ not found") as Nothing
        val f = File(root.parentFile, "phantom-os/src/frontend/src/services/theme.ts")
        if (!f.isFile) fail("немає ${f.path}")
        return f.readText()
    }

    /** Фази, які `themeOfPhase` зводить до 'day' / 'night'. */
    private fun webPhases(text: String, theme: String): Set<String> {
        val body = text.substringAfter("export const themeOfPhase").substringBefore("\n};")
        val line = body.lines().firstOrNull { it.contains("return '$theme'") }
            ?: fail("themeOfPhase більше не повертає '$theme' — сторож не розуміє веб") as Nothing
        return Regex("""phase === '([a-z]+)'""").findAll(line).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `native Living and the web agree on every phase`() {
        val text = webTheme()
        val day = webPhases(text, "day")
        val night = webPhases(text, "night")
        assertTrue("веб не знає жодної фази", day.isNotEmpty() && night.isNotEmpty())
        for (p in DayPhase.entries) {
            val web = when (p.web) {
                in day -> false
                in night -> true
                else -> fail("веб не знає фази «${p.web}»") as Nothing
            }
            assertEquals("фаза ${p.name}: рідний бік і веб малюють різні палітри", web, resolveLivingTheme(p).isDark)
        }
    }
}
