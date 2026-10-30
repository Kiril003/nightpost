package local.phantom.companion.core.data.poi

import java.io.File
import local.phantom.companion.core.data.country.UkrainePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Назва села мусить бути в застосунку, а не в обіцянці.
 *
 * Виміряно 12.09 на дзеркалі корпусу телефона: `places.tsv` попри назву не
 * несе ЖОДНОГО населеного пункту — `category='place'` дає нуль рядків, там
 * POI. Міста знав лише `PlaceTable.kt`, 314 рядків, вписаних рукою; отже
 * «Моршин» із села і назва будь-якого села звідусіль давали порожній екран
 * при 496 806 рядках у кишені. Це вада ДАНИХ, і ранжуванням її не лікують.
 *
 * Сторож читає САМ АСЕТ, бо саме він і є ліками: якщо файл зникне, змаліє або
 * зʼїде форматом, «нема сіл» повернеться мовчки.
 */
class SettlementsAssetTest {

    private val file: File by lazy {
        listOf(
            File("src/main/assets/country/ua/settlements.tsv"),
            File("core-data/src/main/assets/country/ua/settlements.tsv"),
        ).firstOrNull { it.isFile } ?: error("settlements.tsv не знайдено")
    }

    @Test
    fun `пак оголошує асет`() {
        assertEquals("country/ua/settlements.tsv", UkrainePack.settlementsAsset)
    }

    @Test
    fun `заголовок несе покоління — на ньому тримається повторний засів`() {
        val header = file.bufferedReader().use { it.readLine() }
        assertTrue("перший рядок мусить бути заголовком: $header", header.startsWith("generated="))
        assertTrue(OfflinePlacePack.parseGenerated(header) > 0L)
    }

    @Test
    fun `сіл не менше, ніж було заміряно`() {
        val rows = file.bufferedReader().use { reader ->
            reader.readLine()
            OfflinePlacePack.chunkLines(reader).sumOf { it.size }
        }
        assertTrue("поселень поменшало: $rows", rows >= 68_000)
    }

    @Test
    fun `рядки розбираються в місця, а не в сміття`() {
        val sample = file.bufferedReader().use { reader ->
            reader.readLine()
            OfflinePlacePack.chunkLines(reader).first()
        }
        assertTrue(sample.isNotEmpty())
        assertTrue("усе це — населені пункти", sample.all { it.category == "place" })
        assertTrue("назва порожньою не буває", sample.all { it.name.isNotBlank() })
        assertTrue("координати в межах України", sample.all { it.lat in 44.0..53.0 && it.lon in 22.0..41.0 })
    }

    @Test
    fun `посилання несе СВІЙ вид обʼєкта`() {
        // Кузня зловила це в моїй чернетці: «n» усім рядкам виглядає
        // нешкідливо, але шляхів і відношень тут 24 318, і з чужим префіксом
        // їхні id сіли б у вузловий коридор ПОВЕРХ справжніх вузлів із тими
        // самими номерами. Факт, причеплений до одного місця, тихо переїхав би
        // на інше — і ніде б не почервоніло.
        val kinds = file.bufferedReader().use { reader ->
            reader.readLine()
            generateSequence { reader.readLine() }
                .take(20_000)
                .mapNotNull { it.split('\t').getOrNull(4)?.firstOrNull() }
                .toSet()
        }
        assertTrue("посилання мусять бути n/w/r, а є $kinds", kinds.isNotEmpty() && kinds.all { it in "nwr" })
        assertTrue("самих лише вузлів не буває на країні", kinds.size > 1)
    }
}
