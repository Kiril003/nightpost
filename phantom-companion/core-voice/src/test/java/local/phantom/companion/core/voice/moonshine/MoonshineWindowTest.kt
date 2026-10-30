package local.phantom.companion.core.voice.moonshine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Довга репліка більше не зникає мовчки.
 *
 * Вимір голосової лабораторії 12.09 на 300 кліпах CV17 uk: served
 * `decoder.onnx` ДЕТЕРМІНОВАНО падає на кліпах, довших за ~9,4 с (прихований
 * стан ≥ 389 кадрів) — ORT broadcast у `encoder_attn/Add`. Шість кліпів із
 * трьохсот, усі 9,38-10,1 с. `transcribe` ловив виняток і віддавав `null`:
 * що довше людина говорить, то менше шансів бути почутою — і жодного знаку.
 *
 * Тут міряється САМА РІЗАЛЬНА АРИФМЕТИКА, без ONNX: сесію в JVM-воротах не
 * підняти (нативна бібліотека лише під пристрій), тож тест доводить, що
 * жодне вікно не перетинає межу — не те, що декодер його прийняв. Межу
 * доводить лабораторія, цей тест тримає запас.
 */
class MoonshineWindowTest {

    /** Та сама розбивка, що в [MoonshineSttEngine.transcribe]. */
    private fun windowSizes(totalSamples: Int): List<Int> {
        val out = mutableListOf<Int>()
        var start = 0
        while (start < totalSamples) {
            val end = minOf(start + MoonshineSttEngine.WINDOW_SAMPLES, totalSamples)
            out += end - start
            start = end
        }
        return out
    }

    private fun seconds(s: Double) = (s * 16_000).toInt()

    @Test
    fun `вікно не дотягується до межі, на якій декодер падає`() {
        // Межа виміряна: ~9,4 с. Вікно мусить лишатись під нею із запасом.
        assertTrue(
            "вікно ${MoonshineSttEngine.WINDOW_SAMPLES} семплів дотягує до межі падіння",
            MoonshineSttEngine.WINDOW_SAMPLES < seconds(9.4),
        )
        assertEquals(144_000, MoonshineSttEngine.WINDOW_SAMPLES)
    }

    @Test
    fun `кліп, довший за межу, ріжеться і жодне вікно не завелике`() {
        // 10,1 с — найдовший із шести, що валили декодер у лабораторії.
        for (clip in listOf(9.38, 9.4, 10.1, 25.0, 60.0)) {
            val sizes = windowSizes(seconds(clip))
            assertTrue("кліп $clip с не порізано", sizes.size >= 2 || seconds(clip) <= MoonshineSttEngine.WINDOW_SAMPLES)
            sizes.forEach {
                assertTrue(
                    "кліп $clip с дав вікно $it семплів — це знову вбиває декодер",
                    it <= MoonshineSttEngine.WINDOW_SAMPLES,
                )
            }
            assertEquals("різання загубило семпли на кліпі $clip с", seconds(clip), sizes.sum())
        }
    }

    @Test
    fun `коротка репліка лишається одним вікном`() {
        // Середня репліка в наборі — 4,92 с; різати її було б зайвим ризиком
        // для якості на стику.
        assertEquals(listOf(seconds(4.92)), windowSizes(seconds(4.92)))
    }
}
