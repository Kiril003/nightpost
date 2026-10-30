package local.phantom.companion.core.voice.duplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * κ вирішує, чи PHANTOM здатен договорити речення на пристрої без апаратного
 * придушення ехо. Недооцінити його — це поріг нижче власного голосу і петля
 * самоперебивання; переоцінити — лише втрачена чутливість. Тести стережуть
 * саме цю асиметрію.
 */
class EchoCouplingProbeTest {

    private fun feed(probe: EchoCouplingProbe, mic: Float, out: Float, frames: Int) {
        repeat(frames) { probe.observe(mic, out) }
    }

    @Test
    fun `поки доказів мало — тримається песимістичне запасне значення`() {
        val probe = EchoCouplingProbe(fallback = 0.75f)
        feed(probe, mic = 0.05f, out = 0.8f, frames = 10)

        assertFalse(probe.measured)
        assertEquals(0.75f, probe.coupling(), 0.001f)
    }

    @Test
    fun `тихий пристрій міряється як тихий і повертає чутливість`() {
        val probe = EchoCouplingProbe(fallback = 0.75f)
        // Апарат, у якому в мікрофон повертається чверть власного голосу.
        feed(probe, mic = 0.2f, out = 0.8f, frames = 300)

        assertTrue(probe.measured)
        assertEquals(0.25f, probe.coupling(), 0.05f)
    }

    @Test
    fun `гучне ехо піднімає оцінку швидко`() {
        val probe = EchoCouplingProbe(fallback = 0.75f)
        feed(probe, mic = 0.2f, out = 0.8f, frames = 200)
        val quiet = probe.coupling()
        // Телефон поклали на стіл динаміком униз — звʼязок різко зріс.
        feed(probe, mic = 0.72f, out = 0.8f, frames = 20)

        assertTrue("оцінка мала піднятись швидко: $quiet → ${probe.coupling()}",
            probe.coupling() > quiet + 0.3f)
    }

    @Test
    fun `тихі кадри не отруюють оцінку діленням на шум`() {
        val probe = EchoCouplingProbe(fallback = 0.75f)
        feed(probe, mic = 0.9f, out = 0.01f, frames = 500)

        assertFalse("кадри без нашого голосу нічого не кажуть про κ", probe.measured)
    }

    @Test
    fun `оцінка ніколи не падає в нуль`() {
        val probe = EchoCouplingProbe(fallback = 0.75f, floor = 0.05f)
        // Пристрій, який себе взагалі не чує (емулятор без акустичного шляху).
        feed(probe, mic = 0f, out = 0.8f, frames = 500)

        assertTrue(probe.measured)
        assertTrue("нуль зробив би поріг ловцем кожного шурхоту", probe.coupling() >= 0.05f)
    }
}
