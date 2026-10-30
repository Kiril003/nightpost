package local.phantom.companion.ui.settings

import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.VOSK_RAM_FLOOR_BYTES
import local.phantom.companion.core.ai.hearingModelForRam
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vosk чує точніше, але тримає 827 МБ RSS проти 248 у Moonshine (CV17 uk test,
 * 300 кліпів, 12.09). На малому апараті це не «трохи гірше», а рушій, який не
 * підніметься — тож вибір робить памʼять, і саме тут він закріплений.
 *
 * Поріг міряний, а не вгаданий: `MemTotal` віддає близько 91 % номіналу
 * (SM-A525F, 8 ГБ → 7 668 936 кБ). Тому твердження нижче йдуть у тому, що
 * ПОВІДОМЛЯЄ система, а не в тому, що написано на коробці.
 */
class HearingEarByRamTest {

    private fun gib(x: Double) = (x * 1024 * 1024 * 1024).toLong()

    @Test
    fun `a six gigabyte phone reports less than six and still gets vosk`() {
        // Апарат «6 ГБ» повідомляє близько 5,5 ГіБ. Поріг у рівно 6 ГіБ
        // відсікав би саме ті апарати, заради яких він писаний.
        assertEquals(ModelType.VOSK, hearingModelForRam(gib(5.6)))
        assertEquals(ModelType.VOSK, hearingModelForRam(gib(5.5)))
    }

    @Test
    fun `the measured eight gigabyte device gets vosk`() {
        // SM-A525F, зміряно: MemTotal 7 668 936 кБ.
        assertEquals(ModelType.VOSK, hearingModelForRam(7_668_936L * 1024))
    }

    @Test
    fun `a small phone gets the model that fits it`() {
        assertEquals(ModelType.MOONSHINE_UK, hearingModelForRam(gib(4.0)))
        assertEquals(ModelType.MOONSHINE_UK, hearingModelForRam(gib(2.4)))
    }

    @Test
    fun `an unreadable probe falls to the model that runs everywhere`() {
        // deviceTotalRamBytes віддає 0 при відмові — і нуль мусить означати
        // меншу модель, а не більшу.
        assertEquals(ModelType.MOONSHINE_UK, hearingModelForRam(0L))
    }

    @Test
    fun `the floor sits where it was measured to sit`() {
        assertEquals(gib(5.5), VOSK_RAM_FLOOR_BYTES)
        assertEquals(ModelType.VOSK, hearingModelForRam(VOSK_RAM_FLOOR_BYTES))
        assertEquals(ModelType.MOONSHINE_UK, hearingModelForRam(VOSK_RAM_FLOOR_BYTES - 1))
    }
}
