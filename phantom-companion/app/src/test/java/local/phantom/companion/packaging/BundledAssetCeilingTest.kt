package local.phantom.companion.packaging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * СТЕЛЯ ВБУДОВАНИХ РЕСУРСІВ.
 *
 * 08.09 комітом d0f40393 у `app/src/main/assets/models` поклали 442 МБ
 * (supertonic 381, moonshine 62). Пакунок виріс із 56 МБ (реліз 29.08) до
 * 690 МБ, і на роздачу поїхала debug-збірка саме такого розміру. Гірше: ті
 * самі файли ще раз лягали в `filesDir` на першому старті — ~884 МБ на
 * диску апарата за одну модель голосу й одну слуху.
 *
 * Жодні ворота цього не побачили, бо перевірки не існувало: `AbiCeilingTest`
 * сторожить ABI, а про вміст `assets/` не питав ніхто. Ось вона.
 *
 * Правило: важкі моделі їдуть ЗАВАНТАЖЕННЯМ (ModelManager, суми з каталогу
 * всередині підписаного APK), а не в пакунку. Стеля на окремий файл — 8 МБ;
 * усе, що більше, — це модель, і їй місце в сховищі, а не в APK.
 */
class BundledAssetCeilingTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val assetsDir: File by lazy { File(repoRoot(), "app/src/main/assets") }

    /** Один файл — 8 МБ. Веб-бандл (найбільший чесний мешканець) під нею. */
    private val perFileCeiling = 8L * 1024 * 1024

    /** Уся тека — 32 МБ. Ловить «тисяча дрібних», яку стеля на файл пропустить. */
    private val totalCeiling = 32L * 1024 * 1024

    private fun assetFiles(): List<File> =
        assetsDir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `жодна модель не їде в пакунку`() {
        val models = File(assetsDir, "models")
        assertTrue(
            "app/src/main/assets/models існує знову. Саме ця тека 08.09 дала " +
                "690-МБ пакунок. Моделі качає ModelManager із MODELS_BASE_URL, " +
                "суми лежать у каталозі всередині підписаного APK.",
            !models.exists(),
        )
        val onnx = assetFiles().filter { it.extension.equals("onnx", ignoreCase = true) }
        // VAD веб-бандла — єдиний дозволений виняток, і він важить кілобайти.
        val heavy = onnx.filter { it.length() > perFileCeiling }
        assertTrue(
            "у assets/ лежать важкі .onnx: " +
                heavy.joinToString { "${it.relativeTo(assetsDir)} ${it.length() / 1024 / 1024} МБ" },
            heavy.isEmpty(),
        )
    }

    @Test
    fun `жоден вбудований файл не важчий за стелю`() {
        val over = assetFiles().filter { it.length() > perFileCeiling }
        assertTrue(
            "понад ${perFileCeiling / 1024 / 1024} МБ у assets/: " +
                over.joinToString { "${it.relativeTo(assetsDir)} ${it.length() / 1024 / 1024} МБ" } +
                ". Якщо це модель — їй місце у сховищі, а не в APK.",
            over.isEmpty(),
        )
    }

    @Test
    fun `уся тека ресурсів лишається в межах стелі`() {
        val total = assetFiles().sumOf { it.length() }
        assertTrue(
            "assets/ важить ${total / 1024 / 1024} МБ при стелі ${totalCeiling / 1024 / 1024} МБ",
            total <= totalCeiling,
        )
    }
}
