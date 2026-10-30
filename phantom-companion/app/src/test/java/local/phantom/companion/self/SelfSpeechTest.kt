package local.phantom.companion.self

import local.phantom.companion.self.SelfSpeech.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfSpeechTest {

    private fun facts(text: String) = (SelfSpeech.read(text) as Heard.Facts).facts.map { it.text }

    @Test
    fun `remember keeps the person's words, turned to face them`() {
        assertEquals(Heard.Remember(SelfSpeech.Fact("Ти любиш каву без цукру")), SelfSpeech.read("Запамʼятай, що я люблю каву без цукру."))
        assertEquals(Heard.Remember(SelfSpeech.Fact("Моя сестра — Марта".let { "Твоя сестра — Марта" })), SelfSpeech.read("запамʼятай: моя сестра — Марта"))
        assertEquals(Heard.Remember(SelfSpeech.Fact("Ти прокидаєшся о шостій")), SelfSpeech.read("Запамʼятай, що я прокидаюсь о шостій"))
    }

    @Test
    fun `steady facts are heard inside ordinary talk`() {
        assertEquals(listOf("Тебе звати Кирило", "Ти живеш у Львові"), facts("Привіт! Мене звати Кирило. Я живу у Львові."))
        assertEquals(listOf("Тобі 27 років"), facts("мені 27 років"))
        assertEquals("name", (SelfSpeech.read("Мене звати Кирило") as Heard.Facts).facts.single().slot)
    }

    @Test
    fun `questions and passing states are not facts`() {
        assertEquals(Heard.Nothing, SelfSpeech.read("Я живу у Львові?"))
        assertEquals(Heard.Nothing, SelfSpeech.read("я працюю над цим"))
        assertEquals(Heard.Nothing, SelfSpeech.read("яка погода завтра"))
    }

    @Test
    fun `forget and list are commands`() {
        assertEquals(Heard.Forget("каву"), SelfSpeech.read("Забудь про каву"))
        assertEquals(Heard.Forget("я люблю каву"), SelfSpeech.read("забудь, що я люблю каву"))
        assertEquals(Heard.ForgetAll, SelfSpeech.read("Забудь усе про мене"))
        assertEquals(Heard.WhatDoYouKnow, SelfSpeech.read("Що ти про мене знаєш?"))
        assertEquals(Heard.WhatDoYouKnow, SelfSpeech.read("а що ти знаєш про мене"))
        assertEquals(Heard.WhatDoYouKnow, SelfSpeech.read("What do you know about me?"))
    }

    @Test
    fun `pronouns flip without touching the rest`() {
        assertEquals("твій кіт і тобі", SelfSpeech.flip("мій кіт і мені"))
        assertTrue(SelfSpeech.flip("Моя мама").startsWith("Твоя"))
    }
}
