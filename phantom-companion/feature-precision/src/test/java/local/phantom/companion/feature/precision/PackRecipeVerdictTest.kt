package local.phantom.companion.feature.precision

import local.phantom.companion.core.sensor.nav.RoadMeshStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакет, спечений старим рецептом, мусить сказати про себе вголос.
 *
 * ЩО ЦЕ СТЕРЕЖЕ. 15 серпня 2026 о 19:18 пекар навчився класти теги доріг.
 * Найновіший артефакт на дисках спечено о 17:19 того ж дня, національний —
 * на три тижні раніше. Ліки написали й ніхто не перезапустив те, що виробляє
 * річ, якою користуються люди. Виявили це виміром через півтора місяця, бо
 * втрата тегів нічого не ламає — вона мовчки знижує клас.
 *
 * ЧОГО ТУТ НАВМИСНЕ НЕМАЄ. Жодного літерала `2`. Тест, який закріплює
 * поточну версію як норму, захищав би ваду й виглядав при цьому сумлінно —
 * рівно так поводиться тест на боці ПК, що стверджує `format_version == 1`:
 * він почервоніє не на поразці, а на ЛІКАХ. Тому тут перевіряється
 * ВІДНОШЕННЯ до [RoadMeshStore.DB_VERSION], і коли рецепт навчиться нового,
 * сторож поїде разом із ним, не ослабнувши.
 */
class PackRecipeVerdictTest {

    private val app = RoadMeshStore.DB_VERSION

    @Test
    fun `пакет на версію позаду — старий рецепт`() {
        assertEquals(PackVerdict.STALE_RECIPE, packVerdict(app - 1, hasTags = true, appVersion = app))
    }

    @Test
    fun `теги не рятують старий рецепт`() {
        // Головна причина, чому вирок стоїть на версії, а не на симптомі:
        // майбутній v3 матиме теги й лишиться старим. Симптом цього не
        // помітить, версія помітить одразу.
        assertTrue(
            "рецепт мусить уміти більше за один крок, інакше цей тест порожній",
            app >= 2,
        )
        assertEquals(PackVerdict.STALE_RECIPE, packVerdict(app - 1, hasTags = true, appVersion = app))
    }

    @Test
    fun `свіжий рецепт мовчить`() {
        assertEquals(PackVerdict.FRESH, packVerdict(app, hasTags = true, appVersion = app))
    }

    @Test
    fun `пакет новіший за пристрій не оголошується старим`() {
        // Пристрій міг відстати від пекарні. Це не «старий рецепт», і казати
        // людині «перепечіть» тут було б хибною порадою.
        assertEquals(PackVerdict.FRESH, packVerdict(app + 1, hasTags = true, appVersion = app))
    }

    @Test
    fun `найстаріші пакети — без версії й без тегів`() {
        assertEquals(PackVerdict.UNVERSIONED, packVerdict(0, hasTags = false, appVersion = app))
    }

    @Test
    fun `справжні пакети з дисків станом на 30 серпня 2026 — старий рецепт`() {
        // 56 файлів, усі user_version=1 без колонки tags: заміряно.
        assertEquals(PackVerdict.STALE_RECIPE, packVerdict(1, hasTags = false, appVersion = app))
    }
}
