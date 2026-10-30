package local.phantom.companion.core.data.country

import local.phantom.companion.core.data.law.CurfewRule
import local.phantom.companion.core.data.transit.GtfsCityFeed
import local.phantom.companion.core.data.transit.GtfsScheduleFeed

/**
 * Єдиний вхід до пакiв країн. Активація — належність фіксу до рамки пака,
 * дешева перевірка з кешем на активному паку: типовий кадр не сканує нічого.
 *
 * Три стани:
 *  - оператор сам обрав країну → цей пак, і фікс його не перебиває;
 *  - фікс усередині пака (або ЗАПАМʼЯТОВАНИЙ з минулого запуску) → цей пак;
 *  - фікса не було НІКОЛИ і памʼяті нема → жодного пака, глобус, чесно.
 *
 * Гілка «фікса не було, але в збірці рівно один пак → цей пак» померла разом
 * із [restore]: вона вгадувала країну з того, що ми спакували, і мовчки
 * помилялась би на кожному другому паку. Памʼять чесніша за здогад — телефон,
 * який учора був у Києві, відкривається в Києві, бо він там БУВ, а не тому,
 * що Україна єдина в збірці.
 */
object CountryPackRegistry {

    @Volatile
    private var installed: List<CountryPack> = listOf(UkrainePack, EuropePack)

    @Volatile
    private var activeId: String? = null

    /** Ручний вибір оператора. Не null — фікс мовчить, поки вибір стоїть. */
    @Volatile
    private var manualId: String? = null

    @Volatile
    private var operatorRole: OperatorRole = OperatorRole.CIVILIAN

    @Volatile
    private var remember: (String?) -> Unit = {}

    val packs: List<CountryPack> get() = installed

    /** Вибір оператора, або null — «авто». */
    fun overrideId(): String? = manualId

    fun install(packs: List<CountryPack>) {
        installed = packs
        activeId = null
        manualId = null
    }

    /**
     * Стан із диска на старті: що було активним минулого разу і чи оператор
     * обрав країну руками. Викликається ДО першого фіксу.
     */
    fun restore(rememberedId: String?, manual: String?) {
        activeId = rememberedId
        manualId = manual
    }

    /** Куди складати активний id, щоб наступний запуск його памʼятав. */
    fun rememberVia(sink: (String?) -> Unit) {
        remember = sink
    }

    /** Ручний вибір країни; null повертає до автоматичного. */
    fun setOverride(id: String?) {
        manualId = id?.takeIf { candidate -> installed.any { it.id == candidate } }
    }

    /** Тільки для тестів: повернути збірковий стан. */
    fun reset() {
        installed = listOf(UkrainePack, EuropePack)
        activeId = null
        manualId = null
        operatorRole = OperatorRole.CIVILIAN
        remember = {}
    }

    /** Фіксований фікс → пак. Вихід за рамку вимикає пак: це не липкість. */
    fun locate(lat: Double, lon: Double): CountryPack? {
        manual()?.let { return it }
        active()?.let { if (it.bounds.contains(lat, lon)) return it }
        val found = installed.firstOrNull { it.bounds.contains(lat, lon) }
        if (found?.id != activeId) {
            activeId = found?.id
            remember(activeId)
        }
        return found
    }

    private fun manual(): CountryPack? = manualId?.let { id -> installed.firstOrNull { it.id == id } }

    private fun active(): CountryPack? = activeId?.let { id -> installed.firstOrNull { it.id == id } }

    /** Як застосунок має поводитись ЗАРАЗ. Null — глобус, без пака. */
    fun current(): CountryPack? = manual() ?: active()

    fun home(): MapHome = current()?.home ?: WORLD_VIEW

    /**
     * Куди дивиться мапа, поки фікса немає: ручний вибір країни → пояс
     * телефона → пак → глобус ([TimezoneHome], FR-7).
     */
    fun firstHome(timezoneId: String? = java.util.TimeZone.getDefault().id): MapHome =
        manual()?.home ?: TimezoneHome.of(timezoneId) ?: home()

    fun role(): OperatorRole = operatorRole

    fun setRole(role: OperatorRole) {
        operatorRole = role
    }

    /** Чи має сенс пропонувати «Укриття» й «Пункт незламності» цій людині. */
    fun showsUkraineWartime(): Boolean = OperatorStance.showsUkraineWartime(
        stance = OperatorStance(countryId = manualId, role = operatorRole),
        located = active()?.id,
        localeRegion = java.util.Locale.getDefault().country,
    )

    fun regionGeometryAsset(): String? = current()?.regionGeometryAsset

    fun regionIndexAsset(): String? = current()?.regionIndexAsset

    fun poiAsset(): String? = current()?.poiAsset

    fun metroAsset(): String? = current()?.metroAsset

    fun surfaceAsset(): String? = current()?.surfaceAsset

    fun officialFacilitiesAsset(): String? = current()?.officialFacilitiesAsset

    fun invincibilityAsset(): String? = current()?.invincibilityAsset

    fun alarmFeed(role: String): AlarmFeed? = current()?.alarmFeed(role)

    // Таблиці даних читаються з УСІХ встановлених пакiв, не з активного:
    // окупована область лишається окупованою, а фід Львова — львівським,
    // хоч би де стояв оператор. Гео-гейт у них власний (regionId, якір+радіус).
    fun curfewRules(): List<CurfewRule> = tables().curfew

    fun transitFeeds(): List<GtfsCityFeed> = tables().transit

    fun scheduleFeeds(): List<GtfsScheduleFeed> = tables().schedules

    fun rememberedRegions(): Set<String> = tables().remembered

    fun invincibilityCoveredRegions(): Set<String> = tables().invincibilityCovered

    private class Tables(val packs: List<CountryPack>) {
        val curfew: List<CurfewRule> = packs.flatMap { it.curfewRules }
        val transit: List<GtfsCityFeed> = packs.flatMap { it.transitFeeds }
        val schedules: List<GtfsScheduleFeed> = packs.flatMap { it.scheduleFeeds }
        val remembered: Set<String> = packs.flatMapTo(mutableSetOf()) { it.rememberedRegions }
        val invincibilityCovered: Set<String> =
            packs.flatMapTo(mutableSetOf()) { it.invincibilityCoveredRegions }
    }

    @Volatile
    private var tablesCache: Tables? = null

    // Зшивати таблиці на кожен виклик означало б новий Set на кожну перевірку
    // ґрунту в зонді забудови — алокація на кадр там, де змінюється лише
    // встановлений набір. Ідентичність списку і є ключем інвалідації.
    private fun tables(): Tables =
        tablesCache?.takeIf { it.packs === installed } ?: Tables(installed).also { tablesCache = it }
}
