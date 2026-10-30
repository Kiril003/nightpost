package local.phantom.companion.core.data.country

import android.content.Context
import java.io.File

/**
 * Звідки читаються файли пака.
 *
 * Вбудований пак лежить у збірці; завантажений — у власному каталозі під
 * `files/country/<id>/`. Один тип замість двох гілок `if (bundled)` по
 * читачах: коли з R2 приїде пак із власною геометрією, читач уже вміє.
 */
sealed interface PackAssetSource {
    data object Bundled : PackAssetSource
    data class Directory(val path: String) : PackAssetSource
}

/** Текст асета пака або null. Пак без файлу — не помилка, а відсутність. */
fun CountryPack.readAsset(context: Context, name: String): String? = runCatching {
    when (val src = assetSource) {
        PackAssetSource.Bundled ->
            context.assets.open(name).bufferedReader().use { it.readText() }
        is PackAssetSource.Directory ->
            File(src.path, name).takeIf { it.isFile }?.readText()
    }
}.getOrNull()

/**
 * Потік асета пака — для файлів, які шкода тримати одним рядком у памʼяті
 * ([CountryPack.streetsAsset] і [CountryPack.placesAsset]: сотні тисяч рядків,
 * читані один раз при сіянні й одразу забуті). [readAsset] лишається для
 * файлів, які й так живуть розібраними цілком ([CountryPack.poiAsset]).
 *
 * ІМʼЯ НА `.gz` РОЗПАКОВУЄТЬСЯ ТУТ, і причина не в охайності, а в лічильнику
 * роумінгу. Німеччина — 51 МБ місць і 59 МБ вулиць сирим TSV, і 17,1 + 16,6 МБ
 * тим самим TSV під gzip (виміряно 16.09 на спеченому корпусі): 110 МБ проти
 * 34 МБ за ту саму країну. Покластись натомість на `Content-Encoding: gzip`
 * було б помилкою — `PackFetcher` докачує хвіст заголовком `Range`, а той
 * рахує байти ЗАКОДОВАНОГО потоку, тож перерваний пак докачався б зі зсувом
 * у чужому місці й розійшовся з власним sha256. Стиснений файл, названий
 * стисненим, лишає докачку, хеш і розмір у манiфесті чесними — вони всі про
 * те, що справді їде дротом.
 *
 * Розпакування тут, а не в розбирачах: [OfflinePlacePack]/[OfflineStreetPack]
 * бачать `BufferedReader` і не мусять знати, як файл лежить на диску.
 */
fun CountryPack.readAssetStream(context: Context, name: String): java.io.InputStream? = runCatching {
    val raw = when (val src = assetSource) {
        PackAssetSource.Bundled -> context.assets.open(name)
        is PackAssetSource.Directory ->
            File(src.path, name).takeIf { it.isFile }?.let { java.io.FileInputStream(it) }
    }
    when {
        raw == null -> null
        name.endsWith(".gz") -> java.util.zip.GZIPInputStream(raw, 64 * 1024)
        else -> raw
    }
}.getOrNull()

/**
 * Розібраний асет АКТИВНОГО пака.
 *
 * Ключ кешу — пара (пак, шлях), а не сам факт «уже розбирали»: виїхати з
 * країни означає віддати її памʼять (4 МБ київських POI не мають лежати в
 * телефоні над Варшавою), а повернутись — перечитати. Нема пака або пак не
 * оголосив цього асета — [current] віддає null, і споживач бачить типізовану
 * відсутність, а не порожній пакет, який читається як «тут нічого нема».
 *
 * Розбір іде під замком: він коштує сотні мілісекунд і десятки мегабайт
 * тимчасових алокацій, і робити його двічі паралельно — найдорожчий спосіб
 * отримати той самий результат.
 */
class PackAsset<T>(
    private val path: (CountryPack) -> String?,
    private val read: (CountryPack, String) -> String?,
    private val parse: (String) -> T,
) {
    private var key: Pair<String, String>? = null
    private var value: T? = null

    @Synchronized
    fun current(): T? {
        val pack = CountryPackRegistry.current() ?: return drop()
        val name = path(pack) ?: return drop()
        val wanted = pack.id to name
        if (wanted == key) return value
        val raw = read(pack, name) ?: return drop()
        val parsed = runCatching { parse(raw) }.getOrNull() ?: return drop()
        key = wanted
        value = parsed
        return parsed
    }

    private fun drop(): T? {
        key = null
        value = null
        return null
    }
}

/**
 * Те саме, що [PackAsset], але для двійкових асетів (GTFS-розклад — це zip).
 *
 * Окремий клас, а не параметр: читання через `String` пропустило б zip крізь
 * декодер UTF-8 і зіпсувало б кожен байт, що не є літерою. Потік закривається
 * тут — розбирач бачить його вже відкритим і не зобовʼязаний прибирати за
 * паком.
 */
class PackBinaryAsset<T>(
    private val path: (CountryPack) -> String?,
    private val open: (CountryPack, String) -> java.io.InputStream?,
    private val parse: (java.io.InputStream) -> T,
) {
    private var key: Pair<String, String>? = null
    private var value: T? = null

    @Synchronized
    fun current(): T? {
        val pack = CountryPackRegistry.current() ?: return drop()
        val name = path(pack) ?: return drop()
        val wanted = pack.id to name
        if (wanted == key) return value
        val parsed = runCatching {
            open(pack, name)?.use { parse(it) }
        }.getOrNull() ?: return drop()
        key = wanted
        value = parsed
        return parsed
    }

    private fun drop(): T? {
        key = null
        value = null
        return null
    }
}
