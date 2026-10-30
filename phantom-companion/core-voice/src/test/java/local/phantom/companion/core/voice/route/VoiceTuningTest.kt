package local.phantom.companion.core.voice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Голос мусить слухатись людини — і не мусить дати себе зламати числом.
 *
 * Дві різні перевірки в одному файлі, і вони навмисне різного роду:
 *  • [prosody_is_stated_on_every_utterance] — **заслін по джерелу**, який
 *    ЧЕРВОНІЄ на коді, як він був: до 31.08 у всьому дереві не існувало
 *    жодного виклику `setPitch`/`setSpeechRate`, і саме це власник почув як
 *    «інтонація взагалі не задіювалась».
 *  • решта — сторожі нового контракту (обрізання), які на старому коді не
 *    зібралися б, бо [VoiceTuning] тоді не існувало. Кажу це прямо, бо
 *    сьогодні я вже раз описав такий тест як «падає на старому коді», і це
 *    було неправдою: тест, що не збирається, не червоніє, а лише виглядає так.
 */
class VoiceTuningTest {

    /**
     * Рушій Android **липкий**: він памʼятає останнє, що йому сказали, і
     * говорить цим, доки не скажуть інше. Сам файл це вже визнає про звукові
     * атрибути («every call must state its own channel rather than assume a
     * default») — до висоти й темпу це стосується так само, тому вони мусять
     * стояти в тілі `speak`, а не в ініціалізації.
     *
     * Перевірка читає ДЖЕРЕЛО, бо поведінку `TextToSpeech` без пристрою не
     * відтворити. **Чого вона не бачить:** що значення дійшло до динаміка; що
     * воно правильне; і що хтось не перевизначив його нижче. Вона доводить
     * рівно одне — що прозодію взагалі проголошують, і проголошують на кожній
     * фразі.
     */
    @Test
    fun prosody_is_stated_on_every_utterance() {
        val src = sourceOf("AndroidSystemTts.kt")
        val speakBody = src.substringAfter("suspend fun speak(text: String, locale: String, isNavigation: Boolean)")

        assertTrue(
            "у тілі speak() немає setPitch — голос говоритиме тим, що лишив вендор або " +
                "попередній виклик, і повзунок висоти буде декорацією",
            speakBody.contains("setPitch("),
        )
        assertTrue(
            "у тілі speak() немає setSpeechRate — те саме про темп",
            speakBody.contains("setSpeechRate("),
        )
    }

    @Test
    fun `a broken number never reaches the engine`() {
        assertEquals(
            "NaN означає «це число ніколи не порахували» — чесна відповідь на нього " +
                "нейтраль, а не найближча межа: обрізання до мінімуму зробило б із поломки " +
                "навмисно повільний голос",
            VoiceTuning.NEUTRAL,
            VoiceTuning.of(Float.NaN, Float.NaN).pitch,
            0f,
        )
        assertEquals(VoiceTuning.MIN, VoiceTuning.of(0f, 1f).pitch, 0f)
        assertEquals(VoiceTuning.MIN, VoiceTuning.of(-3f, 1f).pitch, 0f)
        assertEquals(VoiceTuning.MAX, VoiceTuning.of(1f, 99f).rate, 0f)
    }

    @Test
    fun `an untouched voice sounds exactly as before`() {
        val default = VoiceTuning()
        assertEquals(
            "без дотику до повзунків голос мусить лишитись байт-у-байт тим самим — " +
                "інакше я поміняв би звучання всім, хто нічого не просив",
            VoiceTuning.NEUTRAL,
            default.pitch,
            0f,
        )
        assertEquals(VoiceTuning.NEUTRAL, default.rate, 0f)
    }

    private fun sourceOf(name: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val f = File(dir, "core-voice/src/main/java/local/phantom/companion/core/voice/route/$name")
            if (f.isFile) return f.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError(
            "не знайшов $name у шести рівнях від ${System.getProperty("user.dir")} — " +
                "заслін не бачить джерела, яке мусить стерегти, і зелений прогін нічого " +
                "не означав би. Полагодь пошук, а не видаляй тест."
        )
    }
}
