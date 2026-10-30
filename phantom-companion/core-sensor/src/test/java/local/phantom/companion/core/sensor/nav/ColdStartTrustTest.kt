package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Скарга власника: «часто кричить про підміну/глушіння, коли їх немає».
 *
 * Один із двох шляхів до цього крику був не в порогах, а в стартовому значенні:
 * рушій починав із [LocationTrust.JAMMED], тож застосунок казав «глушіння» ще
 * до того, як побачив бодай ОДИН фікс. У приміщенні, на щойно запущеному
 * телефоні й на емуляторі без фікса смуга через це стояла намертво — це
 * побачив сусідній фронт на склі, і саме так воно й виглядало для власника.
 *
 * Цей сторож тримає межу: **звинувачення потребує доказу**. Поки доказів нема,
 * стан називається «шукаю небо», а не «нас глушать».
 */
class ColdStartTrustTest {

    @Test
    fun `рушій починає з пошуку неба, а не зі звинувачення`() {
        assertEquals(
            "до першого фікса доказів глушіння немає ЖОДНИХ",
            LocationTrust.SEARCHING,
            CustomLocationEngine().trust.value,
        )
    }

    @Test
    fun `скидання повертає пошук неба, а не глушіння`() {
        val engine = CustomLocationEngine()
        engine.reset()
        assertEquals(LocationTrust.SEARCHING, engine.trust.value)
    }
}
