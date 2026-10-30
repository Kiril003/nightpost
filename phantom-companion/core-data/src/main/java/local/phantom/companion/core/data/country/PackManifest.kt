package local.phantom.companion.core.data.country

import kotlinx.serialization.Serializable
import local.phantom.companion.core.data.law.CurfewRule
import local.phantom.companion.core.data.law.CurfewScope
import local.phantom.companion.core.data.transit.GtfsCityFeed
import local.phantom.companion.core.net.map.AdapterSchema

/**
 * Пак у формі, яка вміє приїхати дротом.
 *
 * [CountryPack] — інтерфейс: його не можна ні завантажити, ні підписати. Тут
 * рівно ті самі поля, але як ДАНІ: серіалізовний запис, який хтось підписав і
 * поклав у R2, а телефон звірив і встановив. Розділення навмисне — вбудована
 * Україна лишається кодом (її ніхто не «встановлює»), а все, що прилетіло,
 * проходить [PackInstaller].
 *
 * Кожне поле-асет і кожна стрічка НЕЗАЛЕЖНО можуть бути відсутні: пак Польщі
 * без тривог і без геометрії — не урізаний пак, а повноцінний. Саме тому тут
 * `null`/порожні списки за замовчуванням, а не обовʼязкові поля з пустишками.
 *
 * Підпис живе поруч у полі `sig` самого JSON і сюди не потрапляє
 * ([kotlinx.serialization] його ігнорує) — його звіряє [PackInstaller] над
 * канонічними байтами документа.
 */
@Serializable
data class PackManifest(
    /** ISO-3166-1 alpha-2. Той самий ключ, що й `CurfewRule.regionId`. */
    val id: String,
    val title: String = "",
    /** Версія самого пака. Старша не має права затерти новішу. */
    val version: Int = 1,
    /** Нижче цієї збірки застосунок пак не розуміє й не вдає, що розуміє. */
    val minAppBuild: Int = 0,
    val bounds: BoundsSpec,
    val home: HomeSpec,
    val regionGeometryAsset: String? = null,
    val regionIndexAsset: String? = null,
    val poiAsset: String? = null,
    /**
     * Три асети, заради яких існує ця хвиля.
     *
     * До 16.09 манiфест знав лише [poiAsset], а `places`/`streets`/`settlements`
     * жили ХАРДКОДОМ в [UkrainePack] — тобто завантажуваний пак фізично не міг
     * привезти ані місця, ані вулиці. Наслідок був не теоретичний: 24 країни
     * Європи лежали спечені з 06.09, а людина в роумінгу бачила мапу без
     * жодного місця, бо [EuropePack] за власним оголошенням не має асетів
     * узагалі.
     *
     * Кожен із них — потоковий TSV, а не JSON, і саме тому вони тут окремими
     * полями, а не одним списком: [CountryPack.placesAsset] і
     * [CountryPack.streetsAsset] мають різних читачів
     * (`OfflinePlacePack`/`OfflineStreetPack`), і рід файла мусить бути видно
     * в манiфесті, а не вгадуватись із розширення.
     */
    val placesAsset: String? = null,
    val streetsAsset: String? = null,
    val settlementsAsset: String? = null,
    val metroAsset: String? = null,
    val surfaceAsset: String? = null,
    val alarmFeeds: List<AlarmFeedSpec> = emptyList(),
    val curfew: List<CurfewSpec> = emptyList(),
    val transit: List<GtfsSpec> = emptyList(),
    val rememberedRegions: List<String> = emptyList(),
    /** Ліцензія на кожне джерело даних пака — вхід аудиту. */
    val sources: List<PackSource> = emptyList(),
    /**
     * Файли, які пак везе дротом, із хешем на кожен. Порожньо — пак нічого не
     * везе (Польща зі самої рамки — повноцінний пак), але кожен ОГОЛОШЕНИЙ
     * асет мусить бути тут: асет без хеша неможливо звірити після завантаження,
     * а «завантажили й повірили» — це той самий дірявий доказ походження, що й
     * «лежить у нашому каталозі».
     */
    val files: List<PackFileSpec> = emptyList(),
) {
    fun fileFor(name: String): PackFileSpec? = files.firstOrNull { it.name == name }

    /**
     * Кожен файл, який цей манiфест оголосив своїм асетом.
     *
     * ОДИН перелік на весь код: `PackInstaller` звіряє по ньому хеші,
     * `CountryPackStore` — наявність на диску. Доти їх було ДВА, набрані
     * руками в двох файлах, і вони вже розійшлися б на цій хвилі: асет, який
     * забули дописати в один із них, їде дротом без хеша або приймається без
     * файла — обидві вади мовчазні.
     */
    fun assetNames(): List<String> = listOfNotNull(
        regionGeometryAsset, regionIndexAsset, poiAsset,
        placesAsset, streetsAsset, settlementsAsset,
        metroAsset, surfaceAsset,
    )
}

/**
 * Один файл пака. [name] — шлях усередині каталогу пака, він же хвіст URL на
 * хості. [bytes] додатний: без розміру відсоток завантаження був би вигаданим.
 */
@Serializable
data class PackFileSpec(
    val name: String,
    val sha256: String,
    val bytes: Long,
) {
    val wellFormed: Boolean
        get() = name.isNotBlank() && bytes > 0L &&
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }
}

@Serializable
data class BoundsSpec(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    val valid: Boolean
        get() = minLat < maxLat && minLon < maxLon &&
            minLat >= -90.0 && maxLat <= 90.0 && minLon >= -180.0 && maxLon <= 180.0

    fun toBounds() = PackBounds(minLat, minLon, maxLat, maxLon)
}

@Serializable
data class HomeSpec(val lat: Double, val lon: Double, val zoom: Double = 12.0) {
    fun toHome() = MapHome(lat, lon, zoom)
}

/**
 * Ліцензійна картка одного джерела пака. Імена класу й ролі — рядки, бо
 * `LicenseClass`/`LayerRole` живуть у `core-geo`, а той залежить від
 * `core-data`: зворотного ребра нема й не буде. Розбір рядка в enum і сам
 * аудит робить сторона, яка бачить обидва модулі ([PackInstaller.audit]).
 */
@Serializable
data class PackSource(
    val id: String,
    val title: String,
    val source: String,
    /** Імʼя `LicenseClass`, напр. "ODBL". Невідоме імʼя — привід відмовити. */
    val license: String,
    /** Імʼя `LayerRole`, напр. "OVERLAY". */
    val role: String,
    val attribution: String = "",
    val joinsOsmTopology: Boolean = false,
    /** Асет пака, який ця ліцензія покриває, або null для стрічки. */
    val asset: String? = null,
)

@Serializable
data class AlarmFeedSpec(
    val role: String,
    val endpoint: String,
    val userAgent: String,
    val schema: SchemaSpec? = null,
    val tokenKey: String? = null,
) {
    fun toFeed() = AlarmFeed(
        role = role,
        endpoint = endpoint,
        userAgent = userAgent,
        schema = schema?.toSchema(endpoint, userAgent),
        tokenKey = tokenKey,
    )
}

/**
 * Інструкція універсального адаптера. `classificationRules` — LinkedHashMap за
 * побудовою kotlinx (порядок ключів JSON зберігається), і це не дрібниця:
 * і адаптер, і вільнотекстовий детектор беруть ПЕРШУ категорію зі збігом.
 */
@Serializable
data class SchemaSpec(
    val rootPath: String,
    val fieldMapping: Map<String, List<String>> = emptyMap(),
    val classificationRules: Map<String, List<String>> = emptyMap(),
    val timestampFormat: String? = null,
    val timeZoneId: String = "UTC",
) {
    fun toSchema(endpoint: String, userAgent: String) = AdapterSchema(
        sourceUrl = endpoint,
        rootPath = rootPath,
        fieldMapping = fieldMapping,
        classificationRules = classificationRules,
        timestampFormat = timestampFormat,
        timeZoneId = timeZoneId,
        userAgent = userAgent,
    )
}

@Serializable
data class CurfewSpec(
    val regionId: String,
    val scope: String = "NATIONAL",
    val zoneId: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val source: String,
    val verifiedAt: String,
) {
    fun toRule(): CurfewRule? {
        val parsed = CurfewScope.values().firstOrNull { it.name == scope } ?: return null
        if (startMinuteOfDay !in 0..1440 || endMinuteOfDay !in 0..1440) return null
        return CurfewRule(regionId, parsed, zoneId, startMinuteOfDay, endMinuteOfDay, source, verifiedAt)
    }
}

@Serializable
data class GtfsSpec(
    val city: String,
    val anchorLat: Double,
    val anchorLon: Double,
    val radiusKm: Double,
    val vehiclePositionsUrl: String,
) {
    fun toFeed() = GtfsCityFeed(city, anchorLat, anchorLon, radiusKm, vehiclePositionsUrl)
}

/**
 * Встановлений пак: той самий контракт, що й у вбудованої України, лише
 * зібраний із запису й привʼязаний до каталогу, з якого читаються його асети.
 */
class InstalledPack(
    val manifest: PackManifest,
    override val assetSource: PackAssetSource,
) : CountryPack {

    override val id = manifest.id
    val version = manifest.version
    override val bounds = manifest.bounds.toBounds()
    override val home = manifest.home.toHome()
    override val regionGeometryAsset = manifest.regionGeometryAsset
    override val regionIndexAsset = manifest.regionIndexAsset
    override val poiAsset = manifest.poiAsset
    override val placesAsset = manifest.placesAsset
    override val streetsAsset = manifest.streetsAsset
    override val settlementsAsset = manifest.settlementsAsset
    override val metroAsset = manifest.metroAsset
    override val surfaceAsset = manifest.surfaceAsset
    override val alarmFeeds = manifest.alarmFeeds.map { it.toFeed() }
    override val curfewRules = manifest.curfew.mapNotNull { it.toRule() }
    override val transitFeeds = manifest.transit.map { it.toFeed() }
    override val rememberedRegions = manifest.rememberedRegions.toSet()
    override val sources = manifest.sources

    override fun toString() = "InstalledPack($id v$version)"
}
