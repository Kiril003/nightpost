package local.phantom.companion.core.data.alerts

import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_MAX
import android.app.NotificationManager.IMPORTANCE_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблиця станів доставки тривоги.
 *
 * Тут перевіряється рівно те, що можна перевірити без пристрою: висновок над
 * уже виміряними входами. Саме зчитування дозволу й каналу — виклики каркаса
 * Android, і воно лишається недоказовим у цьому модулі (Robolectric у
 * каталозі версій є, але в `:app` не підʼєднаний, і тягнути його в офлайнові
 * ворота не можна: він догружає свої архіви з мережі).
 *
 * Тому зелене тут означає «логіка не бреше», а не «на телефоні працює».
 */
class AirAlertDeliveryTest {

    /** До Android 13 дозволу не існує, і його відсутність не є хворобою. */
    @Test
    fun `наданий дозвіл із живим гучним каналом — тиша`() {
        assertEquals(
            AirAlertDelivery.Fine,
            AirAlertDelivery.resolve(
                permitted = true,
                appEnabled = true,
                channelImportance = IMPORTANCE_HIGH,
                askable = false,
            ),
        )
        assertEquals(
            "IMPORTANCE_MAX — тим паче тиша",
            AirAlertDelivery.Fine,
            AirAlertDelivery.resolve(true, true, IMPORTANCE_MAX, false),
        )
    }

    /**
     * Каналу ще немає — це стан ДО першої реєстрації, а не поломка. Назвати
     * його «не прийде» означало б лякати на порожньому місці при першому
     * запуску.
     */
    @Test
    fun `каналу ще немає — це не хвороба`() {
        assertEquals(
            AirAlertDelivery.Fine,
            AirAlertDelivery.resolve(true, true, null, false),
        )
    }

    @Test
    fun `три різні поломки дають одне речення`() {
        val byPermission = AirAlertDelivery.resolve(false, true, IMPORTANCE_HIGH, false)
        val byAppSwitch = AirAlertDelivery.resolve(true, false, IMPORTANCE_HIGH, false)
        val byChannel = AirAlertDelivery.resolve(true, true, IMPORTANCE_NONE, false)
        listOf(byPermission, byAppSwitch, byChannel).forEach {
            assertTrue(
                "усі три означають «сповіщення не вийде» — і мають казати це однаково: $it",
                it is AirAlertDelivery.Silent,
            )
        }
    }

    /**
     * `canAsk` — це не стан, а ціль дотику. Відмовлений назавжди виглядає так
     * само, як відмовлений уперше; різниця лише в тому, куди веде кнопка.
     */
    @Test
    fun `системний діалог пропонується лише коли він ще можливий`() {
        assertEquals(
            AirAlertDelivery.Silent(canAsk = true),
            AirAlertDelivery.resolve(false, true, IMPORTANCE_HIGH, askable = true),
        )
        assertEquals(
            AirAlertDelivery.Silent(canAsk = false),
            AirAlertDelivery.resolve(false, true, IMPORTANCE_HIGH, askable = false),
        )
        assertEquals(
            "сповіщення вимкнені цілком — діалог дозволу вже нічого не зарадить",
            AirAlertDelivery.Silent(canAsk = false),
            AirAlertDelivery.resolve(true, false, IMPORTANCE_HIGH, askable = true),
        )
    }

    /**
     * Найважливіший рядок таблиці: стишений канал — це НЕ «не прийде».
     * Плашка буде. Звести ці два стани означало б сказати неправду в бік
     * паніки, а вона коштує так само дорого, як неправда в бік спокою: після
     * неї перестають читати обидва.
     */
    @Test
    fun `стишений канал не видається за німий`() {
        assertEquals(
            AirAlertDelivery.Quiet,
            AirAlertDelivery.resolve(true, true, IMPORTANCE_DEFAULT, false),
        )
        assertEquals(
            AirAlertDelivery.Quiet,
            AirAlertDelivery.resolve(true, true, IMPORTANCE_LOW, false),
        )
    }

    /** Дозвіл важить більше за важливість каналу: без нього каналу не чути взагалі. */
    @Test
    fun `відсутній дозвіл перекриває будь-яку важливість каналу`() {
        listOf(IMPORTANCE_NONE, IMPORTANCE_LOW, IMPORTANCE_DEFAULT, IMPORTANCE_HIGH, IMPORTANCE_MAX)
            .forEach { imp ->
                assertTrue(
                    "важливість $imp не має рятувати відсутній дозвіл",
                    AirAlertDelivery.resolve(false, true, imp, false) is AirAlertDelivery.Silent,
                )
            }
    }

    /**
     * Здоровий стан мовчить. Це не дрібниця форми: позитивної обіцянки
     * («тривога дійде») продукт не має права вимовляти, бо виробничі оболонки
     * притримують сповіщення самі, і зсередини це не видно.
     */
    @Test
    fun `здоровий стан не має власного речення`() {
        // Статичне INSTANCE у `data object` — не стан, а сам обʼєкт; рахуємо
        // ЛИШЕ поля примірника. Перша редакція цього тесту цього не робила і
        // впала на власному коді — доречно, бо тест, який не вміє відрізнити
        // службове поле від змісту, теж міряє не те, що каже.
        val carried = AirAlertDelivery.Fine.javaClass.declaredFields.filter {
            !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        assertEquals(
            "Fine мусить лишатись обʼєктом без полів — щойно в нього зʼявиться " +
                "текст, продукт почне обіцяти доставку, якої не може гарантувати: " +
                carried.map { it.name },
            emptyList<String>(),
            carried.map { it.name },
        )
    }
}
