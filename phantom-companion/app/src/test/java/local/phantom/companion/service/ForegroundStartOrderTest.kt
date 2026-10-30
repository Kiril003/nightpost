package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `startForeground` — до першого дотику до контейнера. В УСІХ службах.
 *
 * Система рахує дедлайн (5 с) від `startForegroundService`, а
 * `(application as PhantomApp).container` — лінивий граф, який перший дотик
 * будує цілком. На навантаженому старті це не встигало: процес падав із
 * `ForegroundServiceDidNotStartInTimeException`. Пʼять запусків поспіль цей
 * FATAL приймали за «крах континуума», якого не існує.
 *
 * Знайдено в `PeerMeshService`, але тим самим візерунком хворіли ще ЧОТИРИ:
 * `AirAlertService` (вартовий тривоги — найдорожчий: процес гине, людина не
 * чує сирени), `NavigationService`, `LiveDialogService`, `PhantomLinkService`.
 * Тому тест міряє КОЖНУ службу з `onStartCommand`, а не ту одну.
 *
 * Коментарі вирізаються перед пошуком: інакше сторож ловив би власне
 * пояснення, у якому ці самі рядки й названо.
 *
 * Вимір джерелом, не поведінкою: підняти Service під JVM-воротами нічим.
 * Тест доводить ПОРЯДОК, а не те, що система прийняла службу.
 */
class ForegroundStartOrderTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    private val dir = File(root, "app/src/main/java/local/phantom/companion/service")

    private val lineComment = Regex("//[^\\n]*")
    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** Тіло `onStartCommand` без коментарів, або null — служба його не має. */
    private fun startCommandBody(src: String): String? {
        if (!src.contains("override fun onStartCommand(")) return null
        val body = src.substringAfter("override fun onStartCommand(")
            .substringBefore("\n    private fun")
            .substringBefore("\n    override fun")
        return blockComment.replace(lineComment.replace(body, ""), "")
    }

    @Test
    fun `жодна служба не чіпає контейнер до виходу на передній план`() {
        val files = dir.listFiles()?.filter { it.extension == "kt" }.orEmpty()
        assertTrue("сторож дивиться в порожнє: немає служб у ${dir.path}", files.isNotEmpty())

        var checked = 0
        val offenders = mutableListOf<String>()
        for (f in files) {
            val body = startCommandBody(f.readText()) ?: continue
            val raise = listOf("StatusShade.attach", "startForegroundLocation", "startForeground")
                .map { body.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: continue
            val touch = body.indexOf("PhantomApp).container")
            if (touch < 0) { checked++; continue }
            checked++
            if (raise > touch) {
                offenders += "${f.nameWithoutExtension}: контейнер на ${touch}, підйом аж на $raise"
            }
        }
        assertTrue("не знайшла жодної служби з onStartCommand — перевимір шляху", checked > 0)
        assertTrue(
            "служба будує лінивий контейнер ДО startForeground — дедлайн 5 с зʼїсть процес:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
