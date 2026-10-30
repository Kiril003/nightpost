package local.phantom.companion.packaging

import local.phantom.companion.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ПОРІГ СХЕМИ ПАКА — НЕ НОМЕР ПУБЛІКАЦІЇ.
 *
 * `minAppBuild` питає «чи вміє ця збірка прочитати мій формат». Обидва пакові
 * заслони годувались `BuildConfig.VERSION_CODE`, а він у debug навмисно
 * лишається 1 — щоб оголошення про збірку 8 читалось як новіше. Наслідок був
 * невидимий у release і повний у debug: живий каталог має `minAppBuild = 8`
 * у всіх 50 пакетах, тож debug відкидав КОЖЕН пак словами «пак просить
 * збірку 8, а ця — 1». На апараті власника стоять обидві збірки, і перша ж
 * ручна перевірка пакiв упиралась би саме в це.
 *
 * Цей тест їде у DEBUG-варіанті (`:app:testFieldDebugUnitTest`), тобто саме
 * там, де вада й жила.
 */
class PackSchemaBuildTest {

    /** Поріг живого каталогу станом на 12.09: усі 50 пакетів просять 8. */
    private val catalogueMinAppBuild = 8

    @Test
    fun `debug приймає пак живого каталогу`() {
        assertTrue(
            "PACK_SCHEMA_BUILD=${BuildConfig.PACK_SCHEMA_BUILD} менший за поріг каталогу " +
                "$catalogueMinAppBuild — debug відкине кожен пак",
            BuildConfig.PACK_SCHEMA_BUILD >= catalogueMinAppBuild,
        )
    }

    /**
     * Те, що зробило ваду невидимою: у debug ці два числа РІЗНІ. Якби вони
     * збігались, підміна одного на інше нічого б не змінила, і тест був би
     * зеленим ні про що.
     */
    @Test
    fun `у debug номер публікації й поріг схеми — різні числа`() {
        assertEquals("debug versionCode перестав бути 1", 1, BuildConfig.VERSION_CODE)
        assertTrue(
            "PACK_SCHEMA_BUILD зрівнявся з VERSION_CODE — перевірка нижче нічого не доводить",
            BuildConfig.PACK_SCHEMA_BUILD != BuildConfig.VERSION_CODE,
        )
    }

    /**
     * Проводка. Числа вище нічого не варті, якщо контейнер годує заслони
     * знову `VERSION_CODE` — а це правка одного слова у двох місцях.
     */
    @Test
    fun `контейнер годує обидва заслони порогом схеми`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("не знайшов корінь дерева")
        val src = File(root, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()

        // Вікном, а не до першої ")": усередині виклику стоїть java.io.File(…),
        // і зріз "до дужки" обривався ДО рядка appBuild — перевірка червоніла
        // на справному коді.
        val country = src.substringAfter("CountryPackStore(", "").take(400)
        assertTrue(
            "заслін пакiв країн більше не читає PACK_SCHEMA_BUILD:\n$country",
            country.contains("PACK_SCHEMA_BUILD"),
        )
        val world = src.substringAfter("WorldPackLink.appBuild = {", "").take(200)
        assertTrue(
            "заслін світових пакiв більше не читає PACK_SCHEMA_BUILD:\n$world",
            world.contains("PACK_SCHEMA_BUILD"),
        )
        assertTrue(
            "хтось повернув VERSION_CODE у пакові заслони",
            !country.contains("VERSION_CODE") && !world.contains("VERSION_CODE"),
        )
    }
}
