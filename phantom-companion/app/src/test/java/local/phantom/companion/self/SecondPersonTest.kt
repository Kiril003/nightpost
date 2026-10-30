package local.phantom.companion.self

import local.phantom.companion.self.SelfSpeech.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondPersonTest {

    private fun remembered(text: String) = (SelfSpeech.read(text) as Heard.Remember).fact.text
    private fun facts(text: String) = (SelfSpeech.read(text) as Heard.Facts).facts.map { it.text }

    @Test
    fun `the person's own sentence comes back to them, verb agreed, no quotes`() {
        assertEquals("Ти прокидаєшся о шостій", remembered("Запамʼятай, що я прокидаюсь о шостій"))
        assertEquals("Ти ходиш у зал по вівторках", remembered("запамʼятай: я ходжу у зал по вівторках"))
        assertEquals("Ти не пʼєш каву після обіду", remembered("Запамʼятай, я не пʼю каву після обіду"))
        assertEquals("Ти вчишся водити", remembered("запамʼятай, що я вчуся водити"))
        assertEquals("Ти працюєш з дому по пʼятницях", remembered("Запамʼятай, що я працюю з дому по пʼятницях"))
        assertEquals("Ти народився у Вінниці", remembered("Запамʼятай, що я народився у Вінниці"))
        assertEquals("Ти програміст", remembered("Запамʼятай, що я програміст"))
        assertEquals("Ти говориш польською", remembered("запамʼятай, що я говорю польською"))
    }

    @Test
    fun `frequent verb forms`() {
        mapOf(
            "люблю" to "любиш", "роблю" to "робиш", "сплю" to "спиш", "говорю" to "говориш",
            "живу" to "живеш", "пишу" to "пишеш", "можу" to "можеш", "хочу" to "хочеш", "бачу" to "бачиш",
            "граю" to "граєш", "малюю" to "малюєш", "займаюсь" to "займаєшся", "дзвоню" to "дзвониш",
            "їду" to "їдеш", "беру" to "береш", "стою" to "стоїш",
        ).forEach { (i, you) -> assertEquals(i, you, SecondPerson.verb(i)) }
        assertNull(SecondPerson.verb("програміст"))
    }

    @Test
    fun `third person from old notes becomes second`() {
        assertEquals("Ти любиш каву без цукру", SelfMemory.human("Оператор любить каву без цукру"))
        assertEquals("Ти їздиш на Skoda Octavia", SelfMemory.human("їздить на Skoda Octavia"))
        assertEquals("Ти займаєшся бігом", SelfMemory.human("Займається бігом."))
        assertEquals("Ти любиш каву", SelfMemory.human("запамʼятай, що я люблю каву"))
        assertNull(SelfMemory.human("Skoda Octavia 2015, сіра"))
    }

    @Test
    fun `free speech about people in their life, without a mind`() {
        assertEquals(listOf("Тобі 25 років"), facts("мені 25"))
        assertEquals(listOf("Тобі 31 рік"), facts("Мені 31."))
        assertEquals(listOf("У тебе є собака Рекс"), facts("У мене є собака Рекс"))
        assertEquals(listOf("У тебе є собака"), facts("у мене собака"))
        assertEquals(listOf("Твій брат — Андрій"), facts("Мій брат Андрій"))
        assertEquals(listOf("Твоя сестра живе в Києві"), facts("Моя сестра живе в Києві."))
        assertEquals(listOf("Твого брата звати Андрій"), facts("мого брата звати Андрій"))
        assertEquals(listOf("Ти працюєш лікарем у лікарні"), facts("Я працюю лікарем у лікарні"))
    }
}
