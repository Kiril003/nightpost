package local.phantom.companion.core.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Збери Олену й Петра в Cafe X о 19:00» словами людини, без моделі й без
 * мережі: хто, де, коли — як сказано. Імена тут НЕ звіряються з контактами
 * (це робить інструмент, що має реєстр), а слова не вгадуються: чого не
 * сказано, того в розборі немає.
 */
class MeetupSpeechTest {

    private fun create(text: String): MeetupAsk.Create =
        MeetupSpeech.read(text) as? MeetupAsk.Create ?: error("«$text» не прочиталось як зустріч: ${MeetupSpeech.read(text)}")

    @Test
    fun `two people, a venue and a time come out of one sentence`() {
        val ask = create("збери Олену й Петра в Cafe X о 19:00")
        assertEquals(listOf("Олену", "Петра"), ask.people)
        assertEquals("Cafe X", ask.place)
        assertEquals("19:00", ask.at)
    }

    @Test
    fun `commas, conjunctions and quotes around the venue are only punctuation`() {
        val ask = create("Збери Олену, Петра і Марту у кафе «Рояль» завтра о 7 вечора")
        assertEquals(listOf("Олену", "Петра", "Марту"), ask.people)
        assertEquals("кафе Рояль", ask.place)
        assertEquals("завтра 19:00", ask.at)
    }

    @Test
    fun `a meeting with people in the instrumental case reads the same`() {
        val ask = create("організуй зустріч з Оленою і Петром в Cafe X о сьомій вечора")
        assertEquals(listOf("Оленою", "Петром"), ask.people)
        assertEquals("Cafe X", ask.place)
        assertEquals("19:00", ask.at)
    }

    @Test
    fun `a bare hour stays a bare hour so the messenger can read it as morning or evening`() {
        assertEquals("о 7", create("запроси Олену в Cafe X о 7").at)
        assertEquals("07:30", create("запроси Олену в Cafe X о 7:30").at)
        assertEquals("21:00", create("запроси Олену в Cafe X о 21").at)
    }

    @Test
    fun `the venue and the time may come in either order`() {
        val ask = create("поклич Олену завтра о 20:00 у Cafe X")
        assertEquals(listOf("Олену"), ask.people)
        assertEquals("Cafe X", ask.place)
        assertEquals("завтра 20:00", ask.at)
    }

    @Test
    fun `an apostrophe from any keyboard reads as the one product apostrophe`() {
        for (mark in listOf("'", "ʼ", "’")) {
            val ask = create("запроси Олену в п${mark}ятницю о дев${mark}ятій вечора у Cafe X")
            assertEquals("апостроф «$mark»", "в пʼятницю 21:00", ask.at)
            assertEquals(listOf("Олену"), ask.people)
        }
    }

    @Test
    fun `a weekday, a polite word, a surname and another verb change nothing`() {
        val day = create("запроси Олену й Петра в суботу о 19:00 у Cafe X")
        assertEquals("в суботу 19:00", day.at)
        assertEquals("Cafe X", day.place)
        assertEquals(listOf("Олену", "Петра"), day.people)

        val polite = create("Фантом, збери, будь ласка, Олену й Петра в Cafe X о 19:00")
        assertEquals(listOf("Олену", "Петра"), polite.people)
        assertEquals("Cafe X", polite.place)

        assertEquals(listOf("Олену Коваль"), create("збери Олену Коваль в Cafe X о 19:00").people)
        assertEquals(listOf("Олену", "Петра"), create("давай зберемо Олену і Петра в Cafe X о 19:00").people)
    }

    @Test
    fun `a coffee invitation without a time or a meeting word is left to the model`() {
        assertNull(MeetupSpeech.read("запроси Олену на каву"))
    }

    @Test
    fun `what was not said is not in the ask`() {
        val noPlace = create("збери Олену й Петра о 19:00")
        assertNull(noPlace.place)
        assertEquals("19:00", noPlace.at)

        val bare = create("організуй зустріч")
        assertEquals(emptyList<String>(), bare.people)
        assertNull(bare.place)
        assertNull(bare.at)
    }

    @Test
    fun `everybody is not a name`() {
        val ask = create("збери всіх друзів у Cafe X о 19:00")
        assertEquals("«всіх друзів» — не імена, шукати їх у контактах не можна", emptyList<String>(), ask.people)
        assertEquals("Cafe X", ask.place)
    }

    @Test
    fun `ordinary requests with the same verbs are not meetings`() {
        listOf(
            "збери звіт до 18:00",
            "збери дані в таблиці",
            "збери всі файли у папці о 5",
            "запроси Олену в групу",
            "збери інформацію про кафе",
            "що таке зустріч",
            "покажи карту",
        ).forEach { assertNull("«$it» не прохання про зустріч", MeetupSpeech.read(it)) }
    }

    @Test
    fun `a time is a clock, not any number`() {
        // «5 осіб» і «на 5 хвилин» — не година.
        val ask = create("організуй зустріч з Оленою в Cafe X на 5 осіб")
        assertNull(ask.at)
        assertEquals(listOf("Оленою"), ask.people)
        // «до 18:00» — межа, а не початок зустрічі.
        assertNull(create("організуй зустріч з Оленою до 18:00").at)
    }

    @Test
    fun `splitting the bill is its own ask with two modes`() {
        assertEquals(MeetupAsk.Split("claimed"), MeetupSpeech.read("розділи рахунок"))
        assertEquals(MeetupAsk.Split("claimed"), MeetupSpeech.read("Фантом, поділи рахунок, будь ласка"))
        assertEquals(MeetupAsk.Split("equal"), MeetupSpeech.read("розділи рахунок порівну"))
        assertEquals(MeetupAsk.Split("equal"), MeetupSpeech.read("поділи чек навпіл"))
    }

    @Test
    fun `a question about splitting is not an order to split`() {
        assertNull(MeetupSpeech.read("як розділити рахунок?"))
        assertNull(MeetupSpeech.read("розділи файл на частини"))
        assertNull(MeetupSpeech.read("рахунок за світло прийшов"))
    }

    // `at` is what the web resolver reads (services/understand.ts): English day words work there, Czech ones do not, so Czech is normalised.
    @Test
    fun `English and Czech meetups read like the Ukrainian ones`() {
        val en = create("plan pizza with Olena and Daniel Friday 19:00 at Pizzeria X")
        assertEquals(listOf("Olena", "Daniel"), en.people)
        assertEquals("Pizzeria X", en.place)
        assertEquals("on friday 19:00", en.at)

        val en2 = create("invite Olena, Daniel and Mia for coffee tomorrow at 9:30 at Cafe Y")
        assertEquals(listOf("Olena", "Daniel", "Mia"), en2.people)
        assertEquals("Cafe Y", en2.place)
        assertEquals("tomorrow 09:30", en2.at)

        val cs = create("naplánuj pizzu s Olenou a Danielem v pátek v 19:00 v Pizzerii X")
        assertEquals(listOf("Olenou", "Danielem"), cs.people)
        assertEquals("Pizzerii X", cs.place)
        assertEquals("on friday 19:00", cs.at)
    }

    @Test
    fun `names on both sides of with are all people`() {
        val ask = create("invite Olena with Daniel at 19:00 at Cafe X")
        assertEquals(listOf("Olena", "Daniel"), ask.people)
        assertEquals("Cafe X", ask.place)
        assertEquals("19:00", ask.at)
    }

    @Test
    fun `English and Czech ordinary talk is not a meetup`() {
        listOf(
            "plan my week",
            "organise the report by 18:00",
            "schedule a backup tonight",
            "naplánuj mi týden",
            "plan dinner",
            "naplánuj večeři",
            "schedule a call about the contract with Olena at 15:00",
            "how do I split the bill?",
        ).forEach { assertNull(it, (MeetupSpeech.read(it) as? MeetupAsk.Create)) }
    }

    @Test
    fun `English and Czech bills split with the mode`() {
        assertEquals(MeetupAsk.Split("equal"), MeetupSpeech.read("split the bill equally"))
        assertEquals(MeetupAsk.Split("claimed"), MeetupSpeech.read("split the bill"))
        assertEquals(MeetupAsk.Split("equal"), MeetupSpeech.read("rozděl účet rovným dílem"))
        assertEquals(MeetupAsk.Split("claimed"), MeetupSpeech.read("rozděl účet"))
    }

    @Test
    fun `the reader compiles and reads Cyrillic without Java-only regex flags`() {
        // На Android (ICU) вбудованого (?U) немає: клас мусить жити без нього (див. AndroidRegexGuardTest).
        assertTrue(MeetupSpeech.read("збери Олену в Cafe X о 19:00") is MeetupAsk.Create)
    }
}
