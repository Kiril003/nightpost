package local.phantom.obsidian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Випічка мусить бути побайтово однаковою — інакше кожен `bake` дає шум у diff,
 * і асет, який ми обіцяли як офлайн-фолбек, перестає бути перевіряним: ніхто не
 * зможе сказати, чи змінився стиль, чи просто перетасувались ключі.
 */
class BakeDeterminismTest {

    @Test
    fun `two runs of the compiler give the same bytes`() {
        for (v in Variant.entries) {
            assertEquals("випічка ${v.slug} не детермінована", StyleCompiler.bake(v), StyleCompiler.bake(v))
        }
    }

    @Test
    fun `object keys come out sorted`() {
        val head = StyleCompiler.bake(Variant.NIGHT).lines()
            .mapNotNull { Regex("""^  "([a-z:]+)":""").find(it)?.groupValues?.get(1) }
        assertEquals("ключі верхнього рівня не відсортовані", head.sorted(), head)
        assertTrue("не знайдено ключів верхнього рівня", head.size >= 5)
    }

    @Test
    fun `whole numbers never carry a decimal tail`() {
        assertEquals("8", number(8.0))
        assertEquals("0.5", number(0.5))
        assertEquals("-1", number(-1.0))
        assertTrue(
            "у стилі з'явився хвіст .0 — дві гілки арифметики дадуть різний текст",
            !StyleCompiler.bake(Variant.DAY).contains(Regex("""\d\.0[,\n]""")),
        )
    }

    /**
     * Асет на диску — це те, що поїде на пристрій без мережі.
     *
     * Чесно про межу цієї перевірки: вона НЕ ловить того, хто правив JSON
     * руками — `bake` іде першою і його правку вже зітре. Вона ловить інше, і
     * саме це коштувало б живої мапи: випічку, яка мовчки не записала файл, і
     * шлях, що роз'їхався з тим, який просить застосунок.
     */
    @Test
    fun `the committed assets are exactly what the compiler produces`() {
        val dir = File(System.getProperty("obsidian.assets") ?: "../../feature-stream/src/main/assets/styles")
        val stale = Variant.entries.mapNotNull { v ->
            val file = File(dir, "${v.slug}.json")
            when {
                !file.isFile -> null
                file.readText() != StyleCompiler.bake(v) -> "${file.name} розійшовся з компілятором"
                else -> null
            }
        }
        assertEquals("асет правили повз компілятор", emptyList<String>(), stale)
    }
}
