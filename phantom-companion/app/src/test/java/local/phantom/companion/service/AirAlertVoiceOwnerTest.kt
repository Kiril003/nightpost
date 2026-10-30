package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Хто володіє голосом тривоги. 03.09 виміряно: `announce` жив у
 * MapViewModel (Activity), тож у кишені з мертвою MainActivity PHANTOM
 * мовчав — лише рінгтон. Голос переїхав у AirAlertService; цей сторож не
 * дає йому повернутись на екран і не дає службі його загубити.
 *
 * Сліпі плями: читає джерело, не поведінку; поведінка — в [AirAlertVoiceTest].
 * GATE LAW: файли повз Gradle — ганяти з `--rerun`.
 */
class AirAlertVoiceOwnerTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію не знайдено") as Nothing
    }

    private fun read(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("$rel зник — сторож більше нічого не стереже", f.isFile)
        return f.readText()
    }

    /** Блок збирача сповіщень: від `notices.collect` до кінця його `scope.launch`. */
    internal fun noticesBlock(source: String): String? {
        val at = source.indexOf("notices.collect")
        if (at < 0) return null
        val end = source.indexOf("\n        }", at).takeIf { it >= 0 } ?: source.length
        return source.substring(at, end)
    }

    internal fun screenSpeaksAlerts(mapLiveSession: String): Boolean =
        noticesBlock(mapLiveSession)?.contains("announce(") == true

    internal fun serviceSpeaksAlerts(airAlertService: String): Boolean =
        airAlertService.contains("AirAlertVoice(") &&
            noticesBlock(airAlertService)?.contains(".announce(") == true

    @Test
    fun `сторож червоніє на голос, що жив на екрані`() {
        val before = """
            scope.launch {
                watch.notices.collect { n ->
                    announce(n.phrase)
                    postNotice(n.phrase)
                }
            }
        """.trimIndent().replace("\n", "\n        ")
        assertTrue(screenSpeaksAlerts(before))
        assertFalse(screenSpeaksAlerts(before.replace("announce(n.phrase)\n", "")))

        val mute = "scope.launch { watch.notices.collect { AirAlertNotifications.notify(this, it) } }\n        }"
        assertFalse(serviceSpeaksAlerts(mute))
    }

    @Test
    fun `голос тривоги живе в службі, а не на екрані`() {
        assertFalse(
            "MapLiveSession знову промовляє тривогу: цей ViewModel помирає разом з " +
                "Activity, і в кишені PHANTOM мовчатиме. Голос — у AirAlertService.",
            screenSpeaksAlerts(read("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapLiveSession.kt")),
        )
        assertTrue(
            "AirAlertService не промовляє тривоги: сповіщення є, голосу нема — " +
                "рівно те, що власник чув 03.09 з погаслим екраном.",
            serviceSpeaksAlerts(read("app/src/main/java/local/phantom/companion/service/AirAlertService.kt")),
        )
    }
}
