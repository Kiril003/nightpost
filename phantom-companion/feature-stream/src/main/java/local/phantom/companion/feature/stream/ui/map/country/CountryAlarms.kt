package local.phantom.companion.feature.stream.ui.map.country

import android.content.Context
import local.phantom.companion.core.data.country.AlarmFeed
import local.phantom.companion.core.data.country.AlarmFeedRole
import local.phantom.companion.core.data.country.CountryPack
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.feature.stream.ui.map.AirAlertClient
import local.phantom.companion.feature.stream.ui.map.AlertState
import local.phantom.companion.feature.stream.ui.map.AlertsInUaClient
import local.phantom.companion.feature.stream.ui.map.OblastShapes
import local.phantom.companion.feature.stream.ui.map.RegionIndex
import local.phantom.companion.feature.stream.ui.map.SirenClient
import local.phantom.companion.feature.stream.ui.map.SirenOblast

/**
 * ATLAS-3 — єдине місце, де оголошення стрічки пака стає живим клієнтом.
 *
 * До цього `AppContainer` і екран «День» будували ubilling/siren/alerts.in.ua
 * власними руками: три адреси України, вшиті в застосунок, який має працювати
 * і в Польщі. Тепер споживач питає пак, а не клієнта країни — і коли пака
 * нема, кожен фетчер відповідає `null`, тобто «не знаю», а не порожній мапою,
 * тобто «тихо».
 *
 * Клієнти кешуються за адресою: у ubilling всередині ktor-клієнт, і будувати
 * його на кожен тік опитування означало б новий пул сокетів раз на 30 секунд.
 */
class CountryAlarms(
    context: Context,
    /** Ключ бере пак; сховище лише відповідає значенням або нічим. */
    private val secret: suspend (String) -> String?,
    private val fallbackPack: () -> CountryPack? = { null },
) {
    private val appContext = context.applicationContext

    private var cachedState: Pair<String, AirAlertClient>? = null
    private var cachedTyped: Pair<String, SirenClient>? = null
    private var cachedDetail: Pair<String, AlertsInUaClient>? = null

    /** Чи є взагалі чиє небо читати. Нема — це не «тиша», це «не знаю». */
    fun skyAvailable(): Boolean = feed(AlarmFeedRole.REGION_STATE) != null

    suspend fun regionState(): Map<String, AlertState>? {
        val feed = feed(AlarmFeedRole.REGION_STATE) ?: return null
        val schema = feed.schema ?: return null
        return stateClient(feed.endpoint) { AirAlertClient(schema) }.fetch()
    }

    suspend fun typed(): Map<String, SirenOblast>? {
        val feed = feed(AlarmFeedRole.TYPED) ?: return null
        return typedClient(feed.endpoint) {
            SirenClient(
                refOf = { id -> regionIndex().refOf(id) },
                endpoint = feed.endpoint,
                userAgent = feed.userAgent,
            )
        }.fetch()
    }

    suspend fun detail(): Map<String, AlertState>? {
        val feed = feed(AlarmFeedRole.DETAIL) ?: return null
        val key = feed.tokenKey ?: return null
        return detailClient(feed.endpoint) {
            AlertsInUaClient(
                getToken = { secret(key) },
                endpoint = feed.endpoint,
                userAgent = feed.userAgent,
            )
        }.fetch()
    }

    // Мапа й екран «День» тягнуть стрічку паралельно; без замка обидва
    // збудували б власного ktor-клієнта, і той, хто програв гонку, лишив би
    // по собі пул сокетів, який ніхто вже не закриє.
    @Synchronized
    private fun stateClient(endpoint: String, build: () -> AirAlertClient): AirAlertClient =
        cachedState?.takeIf { it.first == endpoint }?.second
            ?: build().also { cachedState = endpoint to it }

    @Synchronized
    private fun typedClient(endpoint: String, build: () -> SirenClient): SirenClient =
        cachedTyped?.takeIf { it.first == endpoint }?.second
            ?: build().also { cachedTyped = endpoint to it }

    @Synchronized
    private fun detailClient(endpoint: String, build: () -> AlertsInUaClient): AlertsInUaClient =
        cachedDetail?.takeIf { it.first == endpoint }?.second
            ?: build().also { cachedDetail = endpoint to it }

    /** Області оператора: полігони теж належать паку, не застосунку. */
    fun regionShapes(): OblastShapes = OblastShapes.load(appContext)

    private fun regionIndex(): RegionIndex = RegionIndex.load(appContext)

    private fun feed(role: String): AlarmFeed? =
        CountryPackRegistry.alarmFeed(role) ?: fallbackPack()?.alarmFeed(role)
}
