package local.phantom.companion.core.data.country

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.license.SignedDocument

/**
 * Паки на диску: `files/country/<id>/manifest.json` плюс асети того ж пака
 * поруч у тому ж каталозі.
 *
 * Підпис звіряється НА КОЖНОМУ читанні, не лише при встановленні: файл лежить
 * у приватному каталозі застосунку, але «лежить у нашому каталозі» — це не
 * доказ походження, а звичка вважати його доказом і є діра. Читання коштує
 * один Ed25519 на пак при старті.
 *
 * Вбудована Україна — підлога: вона в списку завжди й першою, хоч би що
 * лежало на диску. Пак із її id відхиляється ще на встановленні
 * ([PackReject.SHADOWS_BUNDLED]) — оновлення вбудованого пака дротом потребує
 * правила порівняння версій, якого в цій хвилі ще нема.
 */
class CountryPackStore(
    private val root: File,
    private val publicKeyHex: String,
    private val appBuild: Int,
    private val audit: (List<PackSource>) -> List<String>,
) {

    /** Усі валідні паки з диска. Невалідний пропускається, не ламає решту. */
    fun downloaded(): List<InstalledPack> {
        // Перше читання диска в процесі — і єдине місце, де перервана підміна
        // каталогу має шанс дочитатись до кінця. Див. [PackStaging].
        PackStaging.recover(root)
        return root.listFiles()?.filter { it.isDirectory }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { dir -> (readAt(dir) as? PackInstall.Accepted)?.pack }
    }

    /**
     * Те, чим живе застосунок: підлога, те, що приїхало, і континент насамкінець.
     *
     * ПОРЯДОК НЕСЕ ВЕСЬ СЕНС, бо [CountryPackRegistry.locate] бере ПЕРШИЙ пак,
     * чия рамка накриває фікс. Рамка [EuropePack] — це весь континент, тож
     * поставлений раніше він переміг би і Україну, і кожен завантажений
     * країнний пак: людина в Польщі з установленим паком Польщі отримала б
     * континентальний пак без жодного асета, тобто рівно ту порожню мапу, від
     * якої ця хвиля відштовхувалась. Останнім він робить те, чим є насправді —
     * підлогою «ти в Європі, країни своєї ще не завантажив».
     *
     * До 16.09 [EuropePack] тут не було ЗОВСІМ: реєстр мав його лише як
     * значення за замовчуванням, а перший же `install()` на старті його
     * викидав. Тобто континентального фолбека на живому телефоні не існувало.
     */
    fun packs(): List<CountryPack> = listOf(UkrainePack) + downloaded() + EuropePack

    /** Прочитати диск і віддати реєстру. Єдиний виклик, що міняє світ. */
    fun activate(): List<CountryPack> = packs().also { CountryPackRegistry.install(it) }

    /**
     * Покласти манiфест на диск. Асети пака кладе той, хто його завантажив —
     * ДО цього виклику: манiфест, прийнятий раніше за свої файли, робить пак,
     * який оголошує геометрію й не має її.
     */
    fun install(text: String): PackInstall {
        val staged = File(root, stagedIdOf(text) ?: return malformed())
        val outcome = PackInstaller.read(
            text = text,
            publicKeyHex = publicKeyHex,
            appBuild = appBuild,
            assetSource = PackAssetSource.Directory(staged.path),
            audit = audit,
            installedVersion = (readAt(staged) as? PackInstall.Accepted)?.pack?.version,
        )
        if (outcome is PackInstall.Accepted) {
            val missing = outcome.pack.declaredAssets().filterNot { File(staged, it).isFile }
            if (missing.isNotEmpty()) {
                return PackInstall.Rejected(
                    PackReject.MALFORMED,
                    "оголошені асети відсутні в каталозі: ${missing.joinToString()}",
                )
            }
            staged.mkdirs()
            File(staged, MANIFEST).writeText(text)
        }
        return outcome
    }

    /** Куди `PackFetcher` кладе файли. Читачам пакiв цей каталог не видно. */
    fun stagingDir(id: String): File = PackStaging.dir(root, id).also { it.mkdirs() }

    /**
     * Розібрати манiфест і звірити підпис, НЕ застосовуючи правило версій:
     * «те саме вже стоїть» — не відмова, і хто це вирішує, той і рахує.
     */
    fun inspect(text: String): PackInstall {
        val id = stagedIdOf(text) ?: return malformed()
        return PackInstaller.read(
            text = text,
            publicKeyHex = publicKeyHex,
            appBuild = appBuild,
            assetSource = PackAssetSource.Directory(File(root, id).path),
            audit = audit,
        )
    }

    /** Версія встановленого пака або null. */
    fun installedVersion(id: String): Int? =
        (readAt(File(root, id)) as? PackInstall.Accepted)?.pack?.version

    /**
     * Прийняти те, що приїхало дротом.
     *
     * Відрізняється від [install] рівно дорогою: файли вже лежать у
     * вистоюванні (їх привіз і звірив по sha256 `PackFetcher`), манiфест
     * дописується туди ж, і аж потім каталог стає видимим одним renameʼом.
     * Проміжного стану «манiфест нової версії над файлами старої» не буває —
     * не тому, що вікно коротке, а тому, що видимий каталог у цей час просто
     * інший обʼєкт.
     */
    fun installStaged(text: String): PackInstall {
        val id = stagedIdOf(text) ?: return malformed()
        val staged = PackStaging.dir(root, id)
        val live = File(root, id)
        val outcome = PackInstaller.read(
            text = text,
            publicKeyHex = publicKeyHex,
            appBuild = appBuild,
            assetSource = PackAssetSource.Directory(live.path),
            audit = audit,
            installedVersion = (readAt(live) as? PackInstall.Accepted)?.pack?.version,
        )
        if (outcome !is PackInstall.Accepted) return outcome
        val manifest = outcome.pack.manifest
        PackInstaller.unhashedAssets(manifest).takeIf { it.isNotEmpty() }?.let {
            return PackInstall.Rejected(
                PackReject.MALFORMED,
                "асети без хеша не везуться дротом: ${it.joinToString()}",
            )
        }
        // Розмір, а не повторний sha256: файли щойно звірені посильно, а
        // перечитувати десятки мегабайт удруге — платити секунди за той
        // самий доказ.
        manifest.files.firstOrNull { spec ->
            File(staged, spec.name).let { !it.isFile || it.length() != spec.bytes }
        }?.let {
            return PackInstall.Rejected(PackReject.INCOMPLETE, "«${it.name}» не вистояний")
        }
        return runCatching {
            File(staged, MANIFEST).writeText(text)
            if (PackStaging.promote(root, id)) outcome
            else PackInstall.Rejected(PackReject.INCOMPLETE, "каталог пака не підмінюється")
        }.getOrElse {
            PackInstall.Rejected(PackReject.INCOMPLETE, it.message ?: "манiфест не пишеться")
        }
    }

    /** Викинути вистояне цього пака. */
    fun discardStaged(id: String): Boolean = PackStaging.discard(root, id)

    fun remove(id: String): Boolean = File(root, id).deleteRecursively()

    private fun readAt(dir: File): PackInstall? {
        val text = File(dir, MANIFEST).takeIf { it.isFile }?.readText() ?: return null
        return PackInstaller.read(
            text = text,
            publicKeyHex = publicKeyHex,
            appBuild = appBuild,
            assetSource = PackAssetSource.Directory(dir.path),
            audit = audit,
        )
    }

    // Id потрібен ДО перевірки підпису — лише щоб знати каталог. Ніякої довіри
    // до нього нема: усе, що вирішує, перевіряє PackInstaller над тим же текстом.
    private fun stagedIdOf(text: String): String? {
        val raw = (SignedDocument.parse(text)?.get("id") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return null
        return DIR_SAFE.matchEntire(raw)?.value
    }

    private fun malformed() = PackInstall.Rejected(PackReject.MALFORMED, "id пака не читається")

    private companion object {
        const val MANIFEST = "manifest.json"

        /** Id стає іменем каталогу — жодних `..`, роздільників і сюрпризів. */
        val DIR_SAFE = Regex("[A-Za-z]{2}")
    }
}

/** Файли, які пак оголосив своїми. Порядок неважливий — важлива повнота. */
fun InstalledPack.declaredAssets(): List<String> = manifest.assetNames()
