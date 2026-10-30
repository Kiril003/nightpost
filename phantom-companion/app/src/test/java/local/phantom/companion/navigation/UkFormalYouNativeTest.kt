package local.phantom.companion.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Рідні екрани звертаються на «ви», як і веб (`ukFormalYou.test.ts`, той самий список слів).
 * Скануємо uk-рядки всіх модулів (`values/` і `values-uk/`); цитати в «…» — слова самої людини.
 * Виняток — рядки, які людина сама каже помічникові, і підказки моделі.
 */
class UkFormalYouNativeTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val l = "\\p{L}'’ʼ"
    private val pronouns = listOf("ти", "тебе", "тобі", "тобою", "твій", "твоя", "твоє", "твої", "твого", "твоєї", "твоїм", "твоїх", "твоєму", "твоїми", "твою", "твоїй", "твоєю")
    private val imperatives = listOf(
        "спробуй", "перевір", "придумай", "скажи", "увімкни", "вимкни", "обери", "вибери", "додай", "встав", "перезапусти",
        "зачекай", "розкажи", "подивись", "глянь", "набери", "перезавантаж", "тримай", "торкнись", "напиши", "дозволь", "візьми",
        "заповни", "зніми", "перемкни", "заверши", "підійди", "перейди", "дочекайся", "попроси", "опиши", "вийди", "увійди",
        "під’єднайся", "під'єднайся", "підʼєднайся", "введи", "натисни", "наведи", "згенеруй", "відкрий", "закрий", "знайди",
        "онови", "познач", "надішли", "покажи", "створи", "запроси", "почни", "зроби",
    )
    private val notVerbs = setOf("кеш", "миш")
    private val pronounRe = Regex("(?iu)(?<![$l])(${pronouns.joinToString("|")})(?![$l])")
    private val imperativeRe = Regex("(?iu)(?<![$l])(${imperatives.joinToString("|")})(?![$l])")
    private val verb2sgRe = Regex("(?iu)(?<![$l])([$l]+(?:еш|єш|иш|їш)(?:ся)?)(?![$l])")
    private val entry = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    /** «модуль|ім'я» → чому тут говорить сама людина (або модель). */
    private val speakerIsUser = mapOf(
        "app|map_poi_ask_prompt" to "prompt the user sends to the assistant",
        "feature-stream|widget_route_refresh" to "chip = what the user asks the assistant",
        "feature-stream|widget_show_again" to "chip = what the user asks the assistant",
        "feature-stream|widget_diff_refresh" to "chip = what the user asks the assistant",
        "feature-stream|widget_progress_refresh" to "chip = what the user asks the assistant",
        "feature-stream|widget_markdown_refresh" to "chip = what the user asks the assistant",
        "feature-stream|widget_code_refresh" to "chip = what the user asks the assistant",
        "feature-stream|widget_photo_where_ask" to "prompt the user sends to the assistant",
        "feature-stream|mapl_b_brief_system" to "instruction sent to the model",
    )

    private fun tyForms(value: String): List<String> {
        val text = value.replace(Regex("«[^»]*»"), " ")
        val hits = mutableListOf<String>()
        pronounRe.find(text)?.let { hits += it.groupValues[1] }
        imperativeRe.find(text)?.let { hits += it.groupValues[1] }
        verb2sgRe.findAll(text).map { it.groupValues[1] }.filter { it.lowercase() !in notVerbs }.forEach { hits += it }
        return hits
    }

    private fun ukEntries(): List<Triple<String, String, String>> =
        root.listFiles().orEmpty().sortedBy { it.name }.flatMap { module ->
            listOf("values", "values-uk").flatMap { dir ->
                File(module, "src/main/res/$dir").listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
                    .flatMap { f -> entry.findAll(f.readText()).map { Triple(module.name, it.groupValues[2], it.groupValues[3]) }.toList() }
            }
        }

    @Test
    fun `catches ty forms and leaves look-alikes`() {
        assertEquals(listOf("тебе"), tyForms("Що я про тебе знаю"))
        assertEquals(listOf("Спробуй"), tyForms("Спробуй ще раз"))
        assertEquals(listOf("заходиш"), tyForms("Ним заходиш з інших пристроїв"))
        assertEquals(listOf("додай"), tyForms("додай слова"))
        assertEquals(emptyList<String>(), tyForms("Тихо цього тижня, тим краще; тиждень; Тиша"))
        assertEquals(emptyList<String>(), tyForms("Очистити кеш медіа"))
        assertEquals(emptyList<String>(), tyForms("Будь ласка, увійдіть знову. Спробуйте ще раз."))
        assertEquals(emptyList<String>(), tyForms("Або скажіть «запамʼятай …»."))
    }

    @Test
    fun `no native uk string addresses the person as ty`() {
        val offenders = ukEntries()
            .filter { (module, name, text) -> "$module|$name" !in speakerIsUser && cyrillic.containsMatchIn(text) }
            .mapNotNull { (module, name, text) ->
                tyForms(text).takeIf { it.isNotEmpty() }?.let { "$module/$name: ${it.joinToString()} — «${text.take(80)}»" }
            }
        assertEquals("", offenders.joinToString("\n"))
    }

    @Test
    fun `every exemption still exists`() {
        val keys = ukEntries().map { "${it.first}|${it.second}" }.toSet()
        assertEquals(emptyList<String>(), speakerIsUser.keys.filter { it !in keys })
    }
}
