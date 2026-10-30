package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Бали досяжні з реального входу, а підпис гаманця — лише з дотику людини.
 *
 *  - Профіль малює рядок «Бали», рядок веде в розділ, розділ читає власні бали
 *    підписом пристрою і показує те, що сказав сервер;
 *  - «Зарахувати» — єдине місце в застосунку, де гаманець підписує речення для
 *    балів, і воно стоїть у дотику на цьому рядку; міст веба (`WebMessengerPoints`)
 *    підписати гаманцем не може за побудовою;
 *  - слова нових рядків є в обох мовах і не містять заборонених.
 *
 * ЩО ЦЕЙ СТОРОЖ НЕ ДОВОДИТЬ: він читає джерело, а не рендерить екран (у :app
 * немає compose-ui-test). Доводить, що виклик стоїть у тілі потрібного місця, а
 * не що палець його дістав; погляд — знімками з апарата.
 */
class ProfilePointsReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val app = File(root, "app/src/main/java/local/phantom/companion")

    private fun code(rel: String): String {
        val f = File(app, rel)
        if (!f.isFile) error("немає $f — файл перейменували або перенесли")
        return f.readLines().filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private fun balancedBlockAfter(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        if (open < 0) return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(open, i + 1) }
            }
        }
        return text.substring(open)
    }

    @Test
    fun `Профіль малює рядок Бали, рядок веде в розділ, розділ читає бали і знає свій маршрут`() {
        val tab = code("ui/home/ProfileSettings.kt")
        assertTrue("Налаштування не малюють рядок «Бали»", "PointsRow(go)" in tab)
        val points = code("ui/home/ProfilePoints.kt")
        val group = balancedBlockAfter(points, points.indexOf("internal fun PointsRow("))
        assertTrue("рядок «Бали» нікуди не веде", "go(ProfileSection.POINTS.route)" in group)
        assertTrue("рядок «Бали» показується без перевірки, чи є що показати", "PointsPanel.visible(load)" in group)
        assertEquals("points", ProfileSection.POINTS.slug)
        assertTrue(
            "розділ «Бали» не змонтовано в ProfileSectionScreen",
            "ProfileSection.POINTS -> PointsSection(back)" in code("ui/home/ProfileSections.kt"),
        )
        // Розділ і рядок беруть бали в сервера підписом пристрою, а не вигадують.
        assertTrue("бали читаються не з container.points", "container.points.me()" in points)
        assertTrue("відповідь не проходить через PointsPanel.load", "PointsPanel.load(" in points)
    }

    @Test
    fun `підпис гаманця під балами є в одному місці — у дотику «Зарахувати»`() {
        val offenders = app.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "signStatement(" in code(it.relativeTo(app).path) }
            .map { it.relativeTo(app).path }.toList()
        assertEquals("гаманець підписує речення не лише з Профілю", listOf("ui/home/ProfilePoints.kt"), offenders)

        val src = code("ui/home/ProfilePoints.kt")
        val settleRow = src.substring(src.indexOf("R.string.points_settle"))
        val click = balancedBlockAfter(settleRow, settleRow.indexOf("onClick = if (busy) null else"))
        assertTrue("підпис не в дотику на «Зарахувати»", "SolanaLayer.signStatement(" in click)
        assertTrue("відбиток не просять перед підписом", "activity.walletEmbed.confirm(" in click)
        assertTrue("заявку не підтверджує PointsClaims", "container.pointsClaims.settle(" in click)
        // Одна відповідь людини — один підпис: поки йде, друге натискання не приймається.
        assertTrue("повторне натискання не заблоковано", "if (busy) null else" in settleRow)
    }

    @Test
    fun `міст веба не підписує гаманцем і не просить відбитка`() {
        val bridge = code("ui/messenger/WebMessengerPoints.kt")
        for (forbidden in listOf("signStatement", "walletSign", "walletEmbed", "confirm(", "BiometricPrompt")) {
            assertFalse("WebMessengerPoints містить $forbidden — гаманець підписує веб, а не людина", forbidden in bridge)
        }
        assertTrue("міст не віддає заявку PointsClaims", "claims.offer(" in bridge)
        // Заявка в мосту й у Профілі — один екземпляр.
        val container = code("data/AppContainer.kt")
        assertTrue("міст бере заявки не з container.pointsClaims", "claims = container.pointsClaims" in code("ui/messenger/WebMessengerBridge.kt"))
        assertTrue("контейнер не створює pointsClaims", "val pointsClaims" in container)
    }

    private fun strings(dir: String, file: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(root, "app/src/main/res/$dir/$file").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `слова балів є в обох мовах і без заборонених слів`() {
        val uk = strings("values-uk", "strings_home.xml").filterKeys { it == "profile_points" || it.startsWith("points_") } +
            strings("values-uk", "strings.xml").filterKeys { it.startsWith("msgr_scene_points_") }
        val en = strings("values", "strings_home.xml").filterKeys { it == "profile_points" || it.startsWith("points_") } +
            strings("values", "strings.xml").filterKeys { it.startsWith("msgr_scene_points_") }
        assertTrue("слів балів у values менше 20", uk.size >= 20)
        assertEquals("мови розійшлись: ${uk.keys.symmetricDifference(en.keys)}", uk.keys, en.keys)
        val banned = Regex("""\b(light|blackout|darkness|bluetooth|mesh)\b|темря|блекаут""", RegexOption.IGNORE_CASE)
        val hits = (uk + en).filter { banned.containsMatchIn(it.value) }.keys
        assertEquals("заборонені слова в рядках: $hits", emptySet<String>(), hits)
        // Бали ні на що не обіцяють нагород: слова «виграш», «нагорода», «reward» немає.
        val promises = (uk + en).filter { Regex("""нагород|виграш|reward|prize""", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }.keys
        assertEquals("рядки обіцяють нагороду: $promises", emptySet<String>(), promises)
    }

    private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)
}
