package local.phantom.companion.feature.stream.ui.map.dossier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обіцянка картки лінії має дорівнювати її вмісту.
 *
 * Скарга власника 15.09: «натискаю на транспорт і картка не розгортається».
 * Вона розгорталась — і відкривала порожнечу: шеврон малювався безумовно, а за
 * ним лежали тільки дві речі, яких у багатьох ліній немає (кнопка «на мапі»
 * існує лише з `relationId`/`shape`, зупинки приходять не з кожного джерела).
 * Висота не змінювалась, і це читається як «зламано».
 *
 * Тест міряє саме те, чого не видно оком: картка виглядає ОДНАКОВО до й після
 * дотику, тож без нього регрес повернувся б непоміченим.
 */
class TransitLineDetailTest {

    @Test
    fun `лінія без зупинок і без мапи не обіцяє розгортання`() {
        assertFalse(transitLineHasDetail(stopCount = 0, canShowOnMap = false))
    }

    @Test
    fun `сама лише кнопка мапи вже є подробицею`() {
        assertTrue(transitLineHasDetail(stopCount = 0, canShowOnMap = true))
    }

    @Test
    fun `самі лише зупинки вже є подробицею`() {
        assertTrue(transitLineHasDetail(stopCount = 1, canShowOnMap = false))
    }

    @Test
    fun `обидва разом — тим паче`() {
        assertTrue(transitLineHasDetail(stopCount = 12, canShowOnMap = true))
    }

    /**
     * Негативна кількість — не «є зупинки».
     *
     * Не теоретизування: `stopCount` приходить із `List.size` сьогодні, але
     * умова, написана як `stopCount != 0`, пережила б заміну джерела й почала
     * б обіцяти розгортання там, де показувати нічого.
     */
    @Test
    fun `безглузде число зупинок не вважається подробицею`() {
        assertFalse(transitLineHasDetail(stopCount = -1, canShowOnMap = false))
    }
}
