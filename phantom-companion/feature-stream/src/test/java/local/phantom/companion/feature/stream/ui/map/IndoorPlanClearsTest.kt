package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Немає плану — немає панелі поверхів.
 *
 * **Кадр власника 01.09, 02:18.** Він сидить удома. Внутрішнього плану його
 * будинку не існує ніде — а мапа пропонує «ПОВЕРХ +2 / +1 / 1 / −1», тобто
 * поверхи, **за якими нічого немає**. Його слова про цей режим: «тупорилий,
 * неспроможний ні на що і непродуманий».
 *
 * **Причина була в одному символі.** Пошук плану завершувався так:
 * ```kotlin
 * fetchIndoorPlan(...)?.let { ui.indoorPlan = it }
 * ```
 * `?.let` означає «застосуй, якщо не null» — тобто відповідь «плану тут немає»
 * мовчки викидалась, і на екрані лишався план **попереднього** будинку. Панель
 * пережила будинок, у якому мала сенс.
 *
 * Це та сама родина, що й решта наших найдорожчих вад: **порожня відповідь —
 * теж відповідь**, і вона мусить доходити. Тут вона не доходила синтаксично.
 *
 * **Сліпа пляма:** тест читає джерело, а не малює панель. Він побачить
 * повернення `?.let`, але не побачить, якщо план почнуть скидати десь інде й
 * невчасно.
 */
class IndoorPlanClearsTest {

    private fun source(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію не знайдено") as Nothing
        val f = File(
            root,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapComposeOverlays.kt",
        )
        assertTrue("MapComposeOverlays.kt зник — сторож більше нічого не стереже", f.isFile)
        return f.readText()
    }

    /** Чиста функція — тому червоний доводиться на завідомо поганому рядку. */
    private fun swallowsEmptyAnswer(text: String): Boolean =
        Regex("""fetchIndoorPlan\([^)]*\)\s*\?\.""").containsMatchIn(text)

    @Test
    fun `порожня відповідь про план не викидається`() {
        assertTrue(
            "fetchIndoorPlan знову проковтує null через `?.`: коли плану тут немає, " +
                "на екрані лишається план попереднього будинку, і панель поверхів " +
                "пропонує поверхи, за якими нічого немає. Присвоюй результат ЗАВЖДИ.",
            !swallowsEmptyAnswer(source()),
        )
    }

    @Test
    fun `план присвоюється безумовно`() {
        assertTrue(
            "немає безумовного присвоєння ui.indoorPlan = fetchIndoorPlan(...)",
            Regex("""ui\.indoorPlan\s*=\s*fetchIndoorPlan\(""").containsMatchIn(source()),
        )
    }

    /** ДОКАЗ ЧЕРВОНОГО: сторож мусить упасти саме на тому рядку, що був у дереві. */
    @Test
    fun `сторож ловить проковтнуту відповідь`() {
        assertTrue(
            swallowsEmptyAnswer("fetchIndoorPlan(indoorEngine, ui.mapRef, lat, lon)?.let { ui.indoorPlan = it }"),
        )
        assertTrue(
            !swallowsEmptyAnswer("ui.indoorPlan = fetchIndoorPlan(indoorEngine, ui.mapRef, lat, lon)"),
        )
    }
}
