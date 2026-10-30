package local.phantom.companion.copy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила копірайту: жодного забороненого слова чи воєнної рамки в рядках en, uk і cs усіх модулів. */
class BannedWordsCopyTest {

    @Test
    fun `рядки інтерфейсу en, uk і cs без заборонених слів`() {
        val files = File("..").listFiles().orEmpty()
            .map { File(it, "src/main/res") }
            .filter { it.isDirectory }
            .flatMap { res -> LOCALES.mapNotNull { File(res, it).takeIf(File::isDirectory) } }
            .flatMap { dir -> dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().toList() }
        assertTrue("рядків не знайдено — сторож дивиться в порожнечу", files.size > 20)
        val hits = files.flatMap { file ->
            val xml = COMMENT.replace(file.readText(), "")
            VALUE.findAll(xml)
                .filterNot { "translatable=\"false\"" in it.groupValues[1] }
                .mapNotNull { m -> BANNED.find(m.groupValues[2])?.let { "${file.parentFile.parentFile.parentFile.parentFile.parentFile.name}/${file.parentFile.name}/${file.name} ${m.groupValues[1].trim()} «${it.value}»" } }
                .toList()
        }
        assertEquals(emptyList<String>(), hits)
    }

    private companion object {
        val LOCALES = listOf("values", "values-uk", "values-cs")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val VALUE = Regex("""<(?:string|item)\b([^>]*)>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)
        val BANNED = Regex(
            """(?i)\blight\b|blackout|darkness|bluetooth|\bmesh\b|jamm|air raid|(?<![А-Яа-яІіЇїЄєҐґ])РЕБ(?![А-Яа-яІіЇїЄєҐґ])|темряв|глушін|глушат|глушил|\bsvětl|\btma\b|\btmě\b|\btmou\b|zatemn|temnot|válečn|\bcircles?\b|(?<![А-Яа-яІіЇїЄєҐґ])[Кк]ол(?:о|а|і|у|ах|ам|ами|ом)(?![А-Яа-яІіЇїЄєҐґ])|\bokruh(?:y|u|ů|ům|zích)\b""",
        )
    }
}
