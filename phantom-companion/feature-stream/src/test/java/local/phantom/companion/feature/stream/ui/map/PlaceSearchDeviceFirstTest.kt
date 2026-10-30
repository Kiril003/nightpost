package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пошук місць питає ПРИСТРІЙ раніше, ніж мережу.
 *
 * Вирок власника: «пошук місць та іншого не спроможний ні на що». Причина була
 * не в якості індексу, а в черзі: спершу чекали на публічний Nominatim (4,5 с
 * на зʼєднання + 7,5 с на відповідь, плюс власна пауза 1,1 с між запитами), і
 * тільки ПОТІМ шукали серед того, що вже лежить у телефоні. У тунелі, під
 * глушінням і просто при слабкій мережі людина не бачила нічого — хоч місце
 * було в неї в кишені.
 *
 * Сторож читає джерело, як і запобіжники дизайн-системи: логіка живе в
 * `LaunchedEffect` усередині композабла, і чесніше перевірити ПОРЯДОК у
 * тексті, ніж будувати навколо нього фальшиву сцену.
 *
 * ЧОГО ЦЕЙ СТОРОЖ НЕ БАЧИТЬ: він судить лише порядок рядків у трьох відомих
 * місцях `MapScreen.kt`. Якщо пошук переїде в інший файл або сховається за
 * функцією — сторож промовчить. Тому він падає й тоді, коли жодного місця не
 * знайшов: зникнення шаблону — теж подія.
 */
class PlaceSearchDeviceFirstTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева")
    }

    private val source: String
        get() = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapScreen.kt",
        ).readText()

    @Test
    fun `у кожному місці пошуку пристрій питають раніше за мережу`() {
        val text = source
        val netCalls = Regex("(?:geocoder\\?|geocoder\\(\\)|placeSearch)\\.search\\(q\\b").findAll(text).map { it.range.first }.toList()
        assertTrue(
            "жодного виклику мережевого пошуку не знайдено — пошук переїхав, і цей сторож осліп",
            netCalls.isNotEmpty(),
        )
        val localCalls = Regex("searchOffline\\(q").findAll(text).map { it.range.first }.toList()
        assertTrue("жодного виклику пошуку на пристрої не знайдено", localCalls.isNotEmpty())

        for (net in netCalls) {
            val localBefore = localCalls.filter { it < net }.maxOrNull()
            assertTrue(
                "мережевий пошук на позиції $net не має перед собою пошуку на пристрої — " +
                    "порядок перевернуто назад, і людина знову чекатиме на чужий сервер",
                localBefore != null && net - localBefore < 900,
            )
        }
    }
}
