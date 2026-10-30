package local.phantom.companion.core.net.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import local.phantom.companion.core.net.geosrc.GeoHttp

/**
 * Пакети нового покоління їдуть ОКРЕМИМ файлом `forge-catalog.json`.
 *
 * Не через впертість: [WorldPackKind] — kotlinx-enum без запасного значення,
 * і один незнайомий рядок обнулив би ВЕСЬ `world-catalog.json` на телефонах,
 * що вже в полі. Другий файл читають лише збірки, які знають ці роди.
 */
/**
 * Роди кузні — ІМЕНАМИ, ЯКІ РОЗДАЧА СПРАВДІ ПУБЛІКУЄ.
 *
 * ЩО БУЛО ЗЛАМАНО, виміряно 16.09 на живому
 * `try.phantom-os.dev/packs/forge-catalog.json`. Каталог несе 49 підписаних
 * рядків із родами `GEOCODER` (26), `DEM` (18), `BASEMAP_PMTILES` (4) і
 * `TRANSIT_GTFS` (1). Ця сторона оголошувала `geocoder`, `terrain_dem`,
 * `basemap_pmtiles` — інший регістр, інше слово, і `TRANSIT_GTFS` не знала
 * взагалі. kotlinx звіряє [SerialName] дослівно, а незнайомий рід валить
 * розбір ЦІЛКОМ (саме тому цей файл окремий від світового каталогу) — тож
 * `ForgeCatalogWire.parseCatalog` віддавав null, а картка «Хмарні пакети»
 * казала «зіпсований каталог кузні». Жоден із 49 пакетів не було видно
 * НІКОЛИ.
 *
 * Лікується ТУТ, а не в печі, і причина не в зручності: 49 рядків уже лежать
 * підписані приватним ключем власника, якого на цій машині немає й не буде.
 * Перепідписання каталогу — дія власника; зміна одного рядка в застосунку —
 * ні.
 *
 * Сторож цієї домовленості ([ForgeCatalogWireTest]) звіряється з ДОСЛІВНИМИ
 * рядками живого каталогу, а не сам із собою: попередній звірявся сам із
 * собою і тому був зелений весь час, поки телефон не бачив нічого.
 */
@Serializable
enum class ForgePackKind {
    @SerialName("GEOCODER")
    GEOCODER,

    @SerialName("DEM")
    TERRAIN_DEM,

    @SerialName("BASEMAP_PMTILES")
    BASEMAP_PMTILES,

    @SerialName("TRANSIT_GTFS")
    TRANSIT_GTFS,

    /**
     * МІСЦЯ Й ВУЛИЦІ ОДНІЄЇ КРАЇНИ — рід, заради якого 24 спечені країни
     * нарешті мають куди їхати.
     *
     * На відміну від решти родів, цей рядок каталогу НЕ називає одного файла
     * на `<база>/<id>/<id>.bin`. Його `id` — це ISO-3166-1 alpha-2 країни,
     * тобто адреса ПАКА КРАЇНИ: `<база>/<ISO2>/manifest.json` з підписаним
     * манiфестом і кількома файлами під ним. Так і має бути — корпус місць
     * мусить приїхати разом із рамкою, домом і ліцензією, а не окремим
     * блобом, який нема кому приписати.
     */
    @SerialName("COUNTRY_PLACES")
    COUNTRY_PLACES,
}

@Serializable
data class ForgePackCatalogEntry(
    val id: String,
    val kind: ForgePackKind,
    val country: String,
    val labelUa: String,
    val bytes: Long,
    val bakedAt: String = "",
    val minAppBuild: Int = 0,
) {
    val wellFormed: Boolean get() = id.isNotBlank() && labelUa.isNotBlank() && bytes > 0L
}

@Serializable
private data class ForgeCatalogDoc(val packs: List<ForgePackCatalogEntry> = emptyList())

object ForgeCatalogWire {

    const val FILE_NAME = "forge-catalog.json"

    /**
     * Null — увесь документ нечитний. Рядок із НЕВІДОМИМ родом теж валить
     * розбір цілком, і саме тому цей файл окремий від світового каталогу.
     */
    fun parseCatalog(text: String): List<ForgePackCatalogEntry>? =
        runCatching {
            GeoHttp.json.decodeFromString(ForgeCatalogDoc.serializer(), text).packs
        }.getOrNull()
}
