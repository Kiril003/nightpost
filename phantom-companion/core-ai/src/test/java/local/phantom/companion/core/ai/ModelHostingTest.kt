package local.phantom.companion.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Маніфест власних моделей.
 *
 * До 12.09 Moonshine і Supertonic лежали в APK: 442 МБ у пакунку і ті самі
 * 442 МБ ще раз у filesDir після першого старту. Пакунок важив 690 МБ, а
 * реліз 29.08 — 56 МБ. Тепер їх качають на дотик із нашого сховища, і
 * єдине, що відрізняє «наш файл» від «того, що віддав бакет», — сума з
 * цього каталогу.
 *
 * Тому сума тут обовʼязкова, і обовʼязкова саме для НАШИХ ключів: чужий URL
 * ми не контролюємо, і вигадана сума для нього була б брехнею, яку ще й
 * звіряють.
 */
class ModelHostingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hex = Regex("^[0-9a-f]{64}$")

    @Test
    fun `кожен наш файл несе суму й розмір`() {
        val ours = ModelType.values()
            .flatMap { type -> (type.parts + type.extraParts).map { type to it } }
            .filter { (_, part) -> part.isOurs }
        assertTrue("жодного власного файла в каталозі — перевірка нічого не стереже", ours.isNotEmpty())
        ours.forEach { (type, part) ->
            val where = "${type.id}/${part.relPath}"
            assertNotNull("$where: наш файл без sha256", part.sha256)
            assertTrue(
                "$where: sha256 має бути 64 малих шістнадцяткових — маємо ${part.sha256}",
                hex.matches(part.sha256!!),
            )
            assertNotNull("$where: наш файл без розміру", part.bytes)
            assertTrue("$where: розмір ${part.bytes}", part.bytes!! > 0)
        }
    }

    /**
     * Чужий ключ із сумою був би гірший за обидва чесні стани: ми не можемо
     * пообіцяти, що huggingface віддасть саме ці байти завтра, а звірка, яка
     * почне падати сама по собі, навчить людину її обходити.
     */
    @Test
    fun `чужим адресам суми не вигадують`() {
        ModelType.values()
            .flatMap { type -> (type.parts + type.extraParts).map { type to it } }
            .filterNot { (_, part) -> part.isOurs }
            .forEach { (type, part) ->
                assertEquals(
                    "${type.id}/${part.relPath}: сума на чужу адресу нічого не доводить",
                    null,
                    part.sha256,
                )
            }
    }

    /**
     * Число, яке екран показує ДО витрати трафіку. Якщо воно розходиться з
     * сумою частин, людина бачить один розмір, а платить за інший.
     */
    @Test
    fun `оголошений розмір дорівнює сумі частин`() {
        listOf(ModelType.MOONSHINE_UK, ModelType.SUPERTONIC).forEach { type ->
            val sum = type.parts.sumOf { it.bytes ?: 0L }
            assertEquals(
                "${type.id}: оголошено ${type.downloadBytes}, частини важать $sum",
                sum,
                type.downloadBytes,
            )
        }
    }

    /**
     * Порожня база — це «не встановлено, і качати нема звідки», а не тихий
     * відкіт на huggingface: саме такий відкіт 05.09 тягнув ~398 МБ назовні
     * на першій же немiряній мережі, без жодного ключа й без згоди.
     */
    @Test
    fun `без бази власний файл не має адреси`() {
        val manager = ModelManager(
            client = HttpClient(MockEngine { respondOk() }),
            rootDir = tmp.newFolder(),
            modelsBaseUrl = { "  " },
        )
        val part = ModelType.SUPERTONIC.parts.first()
        val boom = runCatching { manager.resolvePartUrl(part) }.exceptionOrNull()
        assertNotNull("порожня база мусить відмовляти вголос", boom)
        assertTrue(
            "відмова мусить називати причину, а не падати абищо: ${boom?.message}",
            boom?.message?.contains("MODELS_BASE_URL") == true,
        )
    }

    /**
     * Шов `verifyDownloads = false` існує для тестів, які міряють не
     * цілісність. Якщо він колись опиниться у складанні застосунку, звірка
     * зникне мовчки: завантаження далі «успішне», просто ніщо більше не
     * відрізняє наш файл від будь-якого іншого під тим самим іменем.
     */
    @Test
    fun `вимикач звірки не потрапляє у складання застосунку`() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("не знайшов корінь дерева")
        val wiring = java.io.File(root, "app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        assertTrue("не знайшов AppContainer.kt — дерево переїхало", wiring.isFile)
        assertTrue(
            "AppContainer згадує verifyDownloads — звірку сум вимкнено у справжній збірці",
            !wiring.readText().contains("verifyDownloads"),
        )
    }

    /**
     * Головне, заради чого все це: байти, що не збіглися з маніфестом, НЕ
     * стають встановленою моделлю. Присутність тут — це `exists() && length
     * > 0`, тож обірваний або підмінений файл, перейменований у ціль,
     * читався б як «встановлено» назавжди.
     */
    @Test
    fun `підмінені байти не стають моделлю`() = kotlinx.coroutines.runBlocking {
        val root = tmp.newFolder()
        val manager = ModelManager(
            client = HttpClient(
                MockEngine {
                    respond(
                        content = ByteArray(1024) { 7 },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, "1024"),
                    )
                },
            ),
            rootDir = root,
            modelsBaseUrl = { "https://example.test/models" },
        )
        manager.startDownload(ModelType.MOONSHINE_UK).join()
        assertTrue(
            "модель із чужих байтів позначилась присутньою",
            manager.states.value[ModelType.MOONSHINE_UK]?.isPresent != true,
        )
        val installed = java.io.File(root, ModelType.MOONSHINE_UK.fileName)
            .listFiles().orEmpty().filterNot { it.name.endsWith(".part") }
        assertTrue("на диску лишились файли: ${installed.map { it.name }}", installed.isEmpty())
    }

    /**
     * Досяжність, а не правильність. Качалка, суми й стани були написані й
     * покриті тестами — і при цьому НЕДОСЯЖНІ з екрана: `AssetsPane` малював
     * кнопку завантаження за умовою `downloadUrl != null`, а саме в наших двох
     * моделей вона порожня (адреси лежать у `parts`). Тобто рівно ті моделі,
     * які виїхали з пакунка, не мали в інтерфейсі жодного способу приїхати.
     */
    @Test
    fun `моделі, що виїхали з пакунка, лишаються досяжними з екрана`() {
        listOf(ModelType.MOONSHINE_UK, ModelType.SUPERTONIC).forEach { type ->
            assertTrue(
                "${type.id}: жодного джерела — екран не покаже кнопки завантаження",
                type.isFetchable,
            )
            assertTrue(
                "${type.id}: wired=false прибрав би кнопку навіть за наявних адрес",
                type.wired,
            )
            assertNotNull(
                "${type.id}: без downloadBytes рядок «нема на пристрої» мовчить про ціну",
                type.downloadBytes,
            )
        }
    }

    @Test
    fun `база склеюється з ключем без подвійної скіски`() {
        val manager = ModelManager(
            client = HttpClient(MockEngine { respondOk() }),
            rootDir = tmp.newFolder(),
            modelsBaseUrl = { "https://example.test/m/" },
        )
        assertEquals(
            "https://example.test/m/models/supertonic/vocoder.onnx",
            manager.resolvePartUrl(ModelType.SUPERTONIC.parts.first { it.relPath == "vocoder.onnx" }),
        )
    }
}
