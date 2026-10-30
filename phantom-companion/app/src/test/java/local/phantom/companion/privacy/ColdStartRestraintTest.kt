package local.phantom.companion.privacy

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дві обіцянки холодного старту, кожну з яких уже знімали мовчки.
 *
 * 1. Слух не качає себе до дотику. Хвиля 08.09 додала `ensureHearing` у
 *    `boot()`, лишивши поруч НЕЗМІНЕНИМИ два коментарі, що обіцяють
 *    протилежне (`AppContainer.hearingProvisioner`: «never at cold start»,
 *    і аудит 05.09 трьома рядками нижче). Спечені в APK Moonshine і
 *    Supertonic цього не закривають: `hearingProvisioner` тримає VOSK, якого
 *    в пакунку немає, тож перший безлімітний Wi-Fi віз би модель без тапу.
 *    Див. phantom-phone-downloads-without-a-key.
 *
 * 2. Дозволи питають один раз. Та сама хвиля прибрала позначку
 *    `asked_at_launch` і додала виклик у `onResume` — а `onResume` настає
 *    щоразу, коли системний діалог дозволу закривається. Після двох відмов
 *    ОС відповідає миттєво й без вікна, тому цикл не видно оком: його видно
 *    лише тут.
 *
 * Вимір джерелом, не поведінкою: `:app` ходить у ворота без Robolectric, тож
 * ні `boot()`, ні Activity тут не запустити. Тест доводить, що виклику немає
 * і що позначка читається — не те, що людина не побачила діалогу.
 */
class ColdStartRestraintTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private fun src(path: String): String {
        val file = File(root, "app/src/main/java/local/phantom/companion/$path")
        assertTrue("зник $path — обіцянку холодного старту більше ні на чому міряти", file.isFile)
        return file.readText()
    }

    private val boot by lazy { src("data/ContainerBoot.kt") }
    private val activity by lazy { src("MainActivity.kt") }

    @Test
    fun `слух не озброюється на холодному старті`() {
        assertFalse(
            "boot() кличе ensureHearing — модель слуху поїде качатись без дотику людини",
            "hearingProvisioner.ensureHearing(" in boot,
        )
        assertTrue(
            "boot() більше не слухає мережу — перервана качка не відновиться після дотику",
            "hearingProvisioner.observeNetwork(" in boot,
        )
    }

    @Test
    fun `дозволи питаються один раз за встановлення`() {
        val body = activity.substringAfter("private fun askOncePastOnboarding()", "")
            .substringBefore("\n    private fun checkAndRequestPermissions")
        assertTrue("зникла askOncePastOnboarding — перевимір шляху дозволів", body.isNotBlank())
        assertTrue(
            "askOncePastOnboarding більше не читає позначку: onResume повертатиме діалог без кінця",
            "NotificationAsk.due(" in body && "return@launch" in body,
        )
        assertFalse(
            "позначку ставить питання, а не відповідь: смерть процесу чи складання екрана з'їсть єдину спробу",
            "putBoolean(" in body,
        )
        assertTrue(
            "відповідь людини не ставить позначку — прочитати її наступного разу буде нічим",
            "NotificationAsk.answered(context, source)" in activity,
        )
    }
}
