package local.phantom.companion.license

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Debug не має чим порівнюватись — і мусить через це МОВЧАТИ.
 *
 * Випадок жив у [ForeignSignatureIsVisibleTest], у спільному наборі `src/test`,
 * а питає він `BuildConfig.EXPECTED_SIGNATURE` — значення, РІЗНЕ по типах
 * збірки. У release воно непорожнє за побудовою, тож у
 * `:app:testFieldReleaseUnitTest` цей випадок падав завжди, і падав НЕ через
 * ваду: сторож кричав вовка там, де вовка не буває.
 *
 * Сторож, що падає на правках, які нічого не змінюють, привчає себе обходити —
 * рівно те, про що попереджає KDoc унизу. Тому випадок переїхав у `src/testDebug`:
 * у release його тепер НЕМАЄ зовсім, а не «пропущено» — пропуск у звіті легко
 * недобачити, відсутність класу недобачити нема як.
 *
 * Решта [ForeignSignatureIsVisibleTest] лишилась спільною: вона годує
 * `SignatureFacts` вигаданими звітами й до варіанта збірки байдужа.
 */
class DebugCarriesNoReleaseFingerprintTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun source(relPath: String): String {
        val f = File(repoRoot(), relPath)
        assertTrue("$relPath не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    @Test
    fun `a debug build never carries the release fingerprint to compare against`() {
        // 06.09 03:52, A52: debug-збірка з ноутбука показала власникові «Цю копію
        // підписали не ми». keystore.properties лежав поруч, і його signatureSha256
        // в'їжджав у BuildConfig УСІХ типів збірки — а debug підписано
        // debug.keystore, тож порівняння з релізним відбитком хибне за побудовою.
        // Гілка «порожній очікуваний = мовчати» вище доведена запуском; тут
        // доводиться, що debug справді приходить туди з порожнім.
        // Цей тест ЇДЕ у debug-варіанті (:app:testFieldDebugUnitTest), тож
        // BuildConfig тут — саме той, що поїхав би на апарат. Питаємо факт, а
        // не правопис build.gradle.kts: попередня редакція вимагала рядок
        // дослівно `if (name == "debug") "" else` і почервоніла, коли той самий
        // вираз розгорнули в блок. Сторож, який ловить форматування замість
        // поведінки, падає на правках, що нічого не змінюють, — і привчає
        // обходити себе.
        assertEquals(
            "debug приходить із НЕпорожнім EXPECTED_SIGNATURE — він порівняє себе " +
                "з релізним відбитком і скаже власникові «цю копію підписали не ми» " +
                "(кадр 06.09 03:52, A52)",
            "",
            local.phantom.companion.BuildConfig.EXPECTED_SIGNATURE,
        )
        // Друга половина контракту — release МУСИТЬ мати чим порівнювати, і
        // звідки він це бере, з debug-варіанта не видно. Тому структурно, але
        // по ВИРАЗУ цілком, а не по одному рядку: debug-гілка дає порожнє,
        // release-гілка читає -P або keystore.properties.
        val gradle = source("app/build.gradle.kts")
        assertTrue("EXPECTED_SIGNATURE зник із build.gradle.kts", gradle.contains("\"EXPECTED_SIGNATURE\""))
        val expr = gradle.substringAfter("val signatureSha =", "")
            .substringBefore("buildConfigField(\"String\", \"EXPECTED_SIGNATURE\"")
        if (expr.isBlank()) fail("вираз, що обчислює очікуваний відбиток, зник")
        assertTrue(
            "у виразі немає гілки на debug — debug знову порівнюватиметься з релізним відбитком:\n$expr",
            expr.contains("""name == "debug""""),
        )
        assertTrue(
            "release-гілка більше не читає відбиток ні з -P, ні з keystore.properties:\n$expr",
            expr.contains("phantom.signatureSha256") || expr.contains("signatureSha256"),
        )
    }

}
