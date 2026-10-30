package local.phantom.companion.core.data.country

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * КНОПКИ, ЯКИМ НЕМА ЩО ЗНАЙТИ.
 *
 * Троє тестувальників 11.09 — зі Словаччини — бачили у швидкому доступі мапи
 * «Укриття» і «Пункт незламності». Це не порожній результат: реєстру таких
 * обʼєктів у їхній країні немає взагалі.
 */
class OperatorStanceTest {

    @Test
    fun `цивільний у Словаччині не бачить воєнних категорій України`() {
        assertFalse(
            OperatorStance.showsUkraineWartime(
                stance = OperatorStance(countryId = null, role = OperatorRole.CIVILIAN),
                located = EuropePack.id,
                localeRegion = "SK",
            ),
        )
    }

    @Test
    fun `цивільний в Україні бачить`() {
        assertTrue(
            OperatorStance.showsUkraineWartime(
                stance = OperatorStance(countryId = null, role = OperatorRole.CIVILIAN),
                located = UkrainePack.id,
                localeRegion = "SK",
            ),
        )
    }

    @Test
    fun `волонтер і служба бачать у будь-якій країні`() {
        for (role in listOf(OperatorRole.VOLUNTEER, OperatorRole.SERVICE)) {
            assertTrue(
                role.name,
                OperatorStance.showsUkraineWartime(
                    stance = OperatorStance(countryId = null, role = role),
                    located = EuropePack.id,
                    localeRegion = "SK",
                ),
            )
        }
    }

    @Test
    fun `вибір оператора головніший за фікс`() {
        assertTrue(
            OperatorStance.showsUkraineWartime(
                stance = OperatorStance(countryId = UkrainePack.id, role = OperatorRole.CIVILIAN),
                located = EuropePack.id,
                localeRegion = "SK",
            ),
        )
        assertFalse(
            OperatorStance.showsUkraineWartime(
                stance = OperatorStance(countryId = EuropePack.id, role = OperatorRole.CIVILIAN),
                located = UkrainePack.id,
                localeRegion = "UA",
            ),
        )
    }

    @Test
    fun `поки країни не знає ніхто, вирішує мова апарата`() {
        assertTrue(OperatorStance.showsUkraineWartime(null, located = null, localeRegion = "UA"))
        assertFalse(OperatorStance.showsUkraineWartime(null, located = null, localeRegion = "SK"))
        assertFalse(OperatorStance.showsUkraineWartime(null, located = null, localeRegion = null))
    }

    @Test
    fun `невідома роль з диска читається як цивільний, а не падає`() {
        assertEquals(OperatorRole.CIVILIAN, OperatorRole.of(null))
        assertEquals(OperatorRole.CIVILIAN, OperatorRole.of("КОМАНДУВАЧ"))
        assertEquals(OperatorRole.SERVICE, OperatorRole.of("SERVICE"))
    }
}
