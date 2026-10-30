package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Коли пошук не вміє того місця, де людина стоїть, — сказати це одним рядком.
 *
 * Підпис країни в рядку видачі зняв ОБМАН: людина в Кошицях бачить, що «Нова»
 * за 500 км це UA, а не SK. Лишалась МАРНІСТЬ — ми казали «це не те, що ти
 * шукав» і мовчали про те, що словацький пакет існує й лежить у каталозі.
 *
 * Умова навмисно вузька, і саме це тут стережеться: пропозиція не сміє
 * зʼявлятися там, де пакет уже стоїть, і там, де країну визначити не вдалось.
 */
class MissingPackTest {

    // Кошице.
    private val kosice = 48.7164 to 21.2611

    // Київ, Майдан.
    private val kyiv = 50.4501 to 30.5234

    @Test
    fun `країна під ногами без пакета — одна пропозиція`() {
        val gap = missingPackFor(kosice, installedCountries = listOf("UA"))
        assertEquals("SK", gap?.code)
        assertEquals("Словаччина", gap?.nameUa)
    }

    @Test
    fun `пакет своєї країни стоїть — мовчимо`() {
        assertNull(missingPackFor(kyiv, listOf("UA"), inLocalCountry = true))
        // Регістр коду не має вирішувати, чи побачить людина пропозицію.
        assertNull(missingPackFor(kyiv, listOf("ua"), inLocalCountry = true))
    }

    @Test
    fun `Львів у рамці Польщі, але полігон каже Україна`() {
        // Рамка Польщі (49,00–55,04 / 14,07–24,15) накриває Львів і менша за
        // українську, тож без полігонів пропозиція назвала б людині у Львові
        // «Польщу». Це та брехня, заради якої тут стоїть окремий прапорець.
        val lviv = 49.8397 to 24.0297
        assertEquals("UA", missingPackFor(lviv, emptyList(), inLocalCountry = true)?.code)
        assertEquals("PL", missingPackFor(lviv, emptyList(), inLocalCountry = false)?.code)
    }

    @Test
    fun `материкова рамка Росії не забирає Кошице`() {
        // Через Калінінград вона починається з ~19,6° сх. д. і накриває
        // Словаччину; за абеткою «Росія» йшла б першою.
        assertEquals("SK", missingPackFor(kosice, emptyList())?.code)
    }

    @Test
    fun `кілька пакетів, і потрібний серед них`() {
        assertNull(missingPackFor(kosice, installedCountries = listOf("UA", "SK", "PL")))
    }

    @Test
    fun `положення невідоме — мовчимо, а не вгадуємо`() {
        assertNull(missingPackFor(null, installedCountries = emptyList()))
    }

    @Test
    fun `посеред океану країни немає — пропозиції теж`() {
        // Жодна рамка сюди не дістає, і вигадати країну гірше, ніж змовчати.
        assertNull(missingPackFor(0.0 to -140.0, installedCountries = emptyList()))
    }

    @Test
    fun `жодного пакета взагалі — пропозиція називає країну під ногами`() {
        val gap = missingPackFor(kyiv, emptyList(), inLocalCountry = true)
        assertEquals("UA", gap?.code)
        assertEquals("Україна", gap?.nameUa)
    }
}
