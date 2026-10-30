package local.phantom.companion.license

import java.io.File
import local.phantom.companion.core.license.AvailableBuild
import local.phantom.companion.core.license.BuildPolicy
import local.phantom.companion.core.license.UpdateOffer
import local.phantom.companion.ui.settings.UpdateSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Проводка правила «збірка не з кабінету не грає в оновлення» — від політики до
 * того, що бачить людина.
 *
 * Поведінку доводять запуском чистих функцій, якими сервіс і рахує. САМ сервіс
 * доведено сканом джерела, а не запуском, і ось чому — заміряно 04.09, коли
 * шукали вузький шов, щоб створити LicenseService у JVM-тесті. Шов виявився не
 * один, а чотири, і три з них ширші за сам сервіс:
 *
 *  1. Сховище секретів (LicenseVault: EncryptedSharedPreferences поверх
 *     AndroidKeyStore) — власне те, що й планували винести за інтерфейс.
 *  2. IntegritySentry. `_state = MutableStateFlow(evaluate())` — ініціалізатор
 *     властивості, тобто ВИКОНУЄТЬСЯ при створенні, а evaluate() читає
 *     `integrity.tampered` → context.applicationInfo / packageManager. У :app
 *     немає ні Robolectric, ні `isReturnDefaultValues`, тож заглушки android.jar
 *     кидають виняток. Довелось би виносити й звірку підпису пакета.
 *  3. Сам Context. Його нічим не підмінити: на тестовому шляху :app лише junit,
 *     coroutines-test, work-testing і ktor-mock — жодної бібліотеки макетів.
 *     Прибрати Context із конструктора означає завести в сервісі гілку «без
 *     Android», якою продукт ніколи не піде: `app` тримають ще vault, sentry і
 *     UpdateInstaller.
 *  4. Ключ звірки. storedPolicy() = BuildPolicy.verified(vault.policy,
 *     BuildConfig.LICENSE_PUBLIC_KEY). Щоб подати «збережену політику з latest
 *     7», її треба ПІДПИСАТИ ключем сервера, якого тут немає. Або робити
 *     публічний ключ параметром — тобто зробити якір довіри ліцензії
 *     замінним із тесту, — або ховати саму звірку за підробку. Друге гірше за
 *     недоведеність: перевірка «довела» б пропозицію повз підпис.
 *
 * Тому межа проведена тут: поведінка — запуском, проводка сервісу — сканом.
 * Скан реддениться (доведено мутацією «сервіс передає literal false»), але він
 * читає текст, а не поведінку, і не вдає інше.
 */
class DeveloperBuildTakesNoUpdatesTest {

    private val cabinetBuild = AvailableBuild(
        platform = "android",
        build = 7,
        version = "0.1.0-tier1",
        filename = "phantom-0.1.0-tier1.apk",
        url = "https://phantom-license.fly.dev/api/download",
        sizeBytes = 56_000_000L,
        sha256 = "b".repeat(64),
        requiresLicence = false,
    )

    private val cabinet = BuildPolicy.DEFAULT.copy(latest = cabinetBuild)

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun source(relPath: String): String = File(repoRoot(), relPath).readText()

    private val service = "app/src/main/java/local/phantom/companion/license/LicenseService.kt"
    private val screen = "app/src/main/java/local/phantom/companion/ui/settings/LicenseScreen.kt"

    @Test
    fun `on a developer build neither the card nor the screen sees an offer`() {
        // Числа зі скла 04.09: політика кабінету оголошує сьому збірку, у
        // польовій versionCode дорівнює одиниці.
        assertNull(UpdateOffer.forThisBuild(cabinet, 1, licensed = true, developerBuild = true))
        assertNull(UpdateOffer.announcedFor(cabinet, 1, developerBuild = true))
    }

    @Test
    fun `on a developer build the screen says so instead of falling silent`() {
        // Порожнє місце тут читалося б як «стоїть найсвіжіша відома збірка» —
        // саме та неправда, заради якої речення й додано.
        assertEquals(
            UpdateSection.DeveloperBuild,
            UpdateSection.of(developerBuild = true, announced = null, licensed = false),
        )
        assertEquals(
            "оголошення не сміє переважити ознаку польової збірки",
            UpdateSection.DeveloperBuild,
            UpdateSection.of(developerBuild = true, announced = cabinetBuild, licensed = true),
        )
    }

    @Test
    fun `a published build sees the offer it should`() {
        assertEquals(
            7,
            UpdateOffer.forThisBuild(cabinet, 1, licensed = true, developerBuild = false)!!.build,
        )
        assertEquals(
            UpdateSection.Offer(cabinetBuild),
            UpdateSection.of(developerBuild = false, announced = cabinetBuild, licensed = true),
        )
        assertEquals(
            UpdateSection.Locked(cabinetBuild.copy(requiresLicence = true)),
            UpdateSection.of(
                developerBuild = false,
                announced = cabinetBuild.copy(requiresLicence = true),
                licensed = false,
            ),
        )
        assertEquals(
            UpdateSection.Newest,
            UpdateSection.of(developerBuild = false, announced = null, licensed = true),
        )
    }

    // СКАН ДЖЕРЕЛА, не запуск. Причина — у чотирьох пунктах KDoc класу; коротко:
    // сервіс не створюється в JVM-тесті, бо його тримають AndroidKeyStore,
    // PackageManager, сам Context і ключ звірки політики. Заміряно 04.09.
    @Test
    fun `the service hands the developer flag to both calculations`() {
        val text = source(service)
        val block = text.substringAfter("private fun announcedUpdate")
            .substringBefore("private fun evaluate")
        val announcedCall = block.substringAfter("UpdateOffer.announcedFor(")
            .substringBefore("private fun availableUpdate")
        val offerCall = block.substringAfter("UpdateOffer.forThisBuild(")

        assertTrue(
            "ознака польової збірки не доходить до announcedFor — банер повернеться",
            announcedCall.contains("developerBuild"),
        )
        assertTrue(
            "ознака польової збірки не доходить до forThisBuild — картка повернеться",
            offerCall.contains("developerBuild"),
        )
        assertTrue(
            "BuildConfig.DEBUG читають усередині розрахунку — тест не може подати друге значення",
            !block.contains("BuildConfig.DEBUG"),
        )
        assertTrue(
            "замовчування конструктора мусить бути BuildConfig.DEBUG",
            text.contains("val developerBuild: Boolean = BuildConfig.DEBUG"),
        )
    }

    @Test
    fun `the licence screen asks the state machine, not the announcement`() {
        val text = source(screen)

        assertTrue(
            "екран рахує стан секції повз UpdateSection — доведене тут його не стосується",
            text.contains("UpdateSection.of("),
        )
        assertTrue(
            "екран не питає сервіс про польову збірку",
            text.contains("service.developerBuild"),
        )
        assertTrue(
            "речення про збірку розробника ніде не показують",
            text.contains("R.string.update_developer"),
        )
    }

    @Test
    fun `the sentence exists in both languages`() {
        listOf("app/src/main/res/values-uk", "app/src/main/res/values").forEach { dir ->
            assertTrue(
                "у $dir немає рядка update_developer — на цій мові екран промовчить",
                source("$dir/strings.xml").contains("name=\"update_developer\""),
            )
        }
    }
}
