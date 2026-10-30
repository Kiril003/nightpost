package local.phantom.companion.core.data.country

import local.phantom.companion.core.data.law.CurfewRules
import local.phantom.companion.core.data.transit.GtfsFeedRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-3 — контракт пака країни: за кордоном мапа не носить Київ у кишені,
 * але й не бреше порожнім списком там, де просто нема даних.
 *
 * Реєстр — процесний обʼєкт, тож кожен тест повертає збірковий стан у [tearDown]:
 * інакше активація тут протікла б у сусідні тести модуля.
 */
class CountryPackRegistryTest {

    @After
    fun tearDown() = CountryPackRegistry.reset()

    private val kyiv = 50.4501 to 30.5234
    private val barcelona = 41.3874 to 2.1686
    private val saoPaulo = -23.5505 to -46.6333

    @Test
    fun `фікс усередині рамки активує пак`() {
        assertEquals(UkrainePack, CountryPackRegistry.locate(kyiv.first, kyiv.second))
        assertEquals("UA", CountryPackRegistry.current()?.id)
    }

    @Test
    fun `фікс за рамкою вимикає пак — це не липкість`() {
        CountryPackRegistry.locate(kyiv.first, kyiv.second)
        assertNull(CountryPackRegistry.locate(saoPaulo.first, saoPaulo.second))
        assertNull(CountryPackRegistry.current())
    }

    @Test
    fun `оператор повертається — пак вмикається знову`() {
        CountryPackRegistry.locate(saoPaulo.first, saoPaulo.second)
        assertEquals(UkrainePack, CountryPackRegistry.locate(kyiv.first, kyiv.second))
    }

    @Test
    fun `перший у житті запуск не вгадує країну`() {
        assertEquals(WORLD_VIEW, CountryPackRegistry.home())
    }

    @Test
    fun `холодний старт памʼятає країну минулого запуску`() {
        CountryPackRegistry.restore(rememberedId = "UA", manual = null)
        assertEquals(UkrainePack.home, CountryPackRegistry.home())
    }

    @Test
    fun `памʼять не липка — перший фікс за кордоном її стирає`() {
        CountryPackRegistry.restore(rememberedId = "UA", manual = null)
        assertNull(CountryPackRegistry.locate(saoPaulo.first, saoPaulo.second))
        assertEquals(WORLD_VIEW, CountryPackRegistry.home())
    }

    @Test
    fun `активний пак лягає в памʼять, і саме той, що активний`() {
        val seen = mutableListOf<String?>()
        CountryPackRegistry.rememberVia { seen += it }
        CountryPackRegistry.locate(kyiv.first, kyiv.second)
        CountryPackRegistry.locate(saoPaulo.first, saoPaulo.second)
        assertEquals(listOf("UA", null), seen)
    }

    @Test
    fun `ручний вибір не перебивається фіксом`() {
        CountryPackRegistry.setOverride("UA")
        assertEquals(UkrainePack, CountryPackRegistry.locate(barcelona.first, barcelona.second))
        assertEquals(UkrainePack.home, CountryPackRegistry.home())
        CountryPackRegistry.setOverride(null)
        assertEquals(EuropePack, CountryPackRegistry.locate(barcelona.first, barcelona.second))
    }

    @Test
    fun `ручний вибір невстановленого пака не приймається`() {
        CountryPackRegistry.setOverride("BR")
        assertNull(CountryPackRegistry.overrideId())
    }

    @Test
    fun `фікс за кордоном дає глобус, а не чужу столицю`() {
        CountryPackRegistry.locate(saoPaulo.first, saoPaulo.second)
        assertEquals(WORLD_VIEW, CountryPackRegistry.home())
        assertNull(CountryPackRegistry.regionGeometryAsset())
        assertNull(CountryPackRegistry.regionIndexAsset())
        assertNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE))
    }

    @Test
    fun `Barcelona тепер покривається EuropePack`() {
        val pack = CountryPackRegistry.locate(barcelona.first, barcelona.second)
        assertEquals(EuropePack, pack)
        assertEquals("EU", CountryPackRegistry.current()?.id)
    }

    @Test
    fun `без жодного пака таблиці порожні, а не вигадані`() {
        CountryPackRegistry.install(emptyList())
        assertTrue(CurfewRules.rules.isEmpty())
        assertTrue(GtfsFeedRegistry.feeds.isEmpty())
        assertTrue(CountryPackRegistry.rememberedRegions().isEmpty())
        assertNull(CurfewRules.matchAt("UA", 0L))
        assertNull(GtfsFeedRegistry.feedFor(49.84, 24.03))
        assertEquals(WORLD_VIEW, CountryPackRegistry.home())
    }

    @Test
    fun `таблиці даних читаються з усіх пакiв, не лише з активного`() {
        // Оператор у Барселоні — окупована область лишається окупованою,
        // а львівський фід лишається львівським.
        CountryPackRegistry.locate(barcelona.first, barcelona.second)
        assertTrue("Донецька область" in CountryPackRegistry.rememberedRegions())
        assertNotNull(GtfsFeedRegistry.feedFor(49.84, 24.03))
        assertNotNull(CurfewRules.ruleFor("UA"))
    }

    @Test
    fun `стрічка тривог оголошена, а не написана`() {
        val state = UkrainePack.alarmFeed(AlarmFeedRole.REGION_STATE)
        assertNotNull("region-state має бути чистою декларацією", state?.schema)
        assertEquals(state?.endpoint, state?.schema?.sourceUrl)
        assertTrue(
            "словник загроз — дані пака, не літерал у коді",
            state?.schema?.classificationRules?.containsKey("SHAHED") == true,
        )
        // Найспецифічніше першим: «kn-23» не має тонути в ширшому «ракет».
        val order = state?.schema?.classificationRules?.keys?.toList().orEmpty()
        assertTrue(order.indexOf("BALLISTIC") < order.indexOf("MISSILE"))
    }

    @Test
    fun `токенна стрічка називає ключ, а не носить його`() {
        val detail = UkrainePack.alarmFeed(AlarmFeedRole.DETAIL)
        assertNotNull(detail?.tokenKey)
        assertNull("клієнт із кодом — не декларація", detail?.schema)
    }

    @Test
    fun `рамка пака покриває його власні дані`() {
        for (feed in UkrainePack.transitFeeds) {
            assertTrue(
                "${feed.city} поза рамкою пака",
                UkrainePack.bounds.contains(feed.anchorLat, feed.anchorLon),
            )
        }
        assertTrue(UkrainePack.bounds.contains(UkrainePack.home.lat, UkrainePack.home.lon))
        assertTrue(UkrainePack.curfewRules.all { it.regionId == UkrainePack.id })
    }

    /**
     * `GtfsRealtimeClient.decodeFeedEntity` читає лише поле `vehicle`, тож фід
     * TripUpdates або JSON-API віддає порожньо НАЗАВЖДИ і мовчки — на мапі це
     * не відрізнити від міста без транспорту. 08.09 таких стояло два з
     * чотирьох, під підписом «перевірено curl».
     */
    @Test
    fun `кожен фід позицій має форму VehiclePositions`() {
        for (pack in listOf(UkrainePack, EuropePack)) {
            for (feed in pack.transitFeeds) {
                val url = feed.vehiclePositionsUrl.lowercase()
                assertFalse(
                    "${feed.city}: ${feed.vehiclePositionsUrl} — це TripUpdates, декодер поверне порожньо назавжди",
                    "tripupdate" in url || "alerts" in url,
                )
                assertFalse(
                    "${feed.city}: ${feed.vehiclePositionsUrl} — JSON-API, а декодер чекає protobuf",
                    "api/action/" in url || "format=json" in url,
                )
            }
        }
    }

    @Test
    fun `рамка EuropePack покриває його власні дані`() {
        for (feed in EuropePack.transitFeeds) {
            assertTrue(
                "${feed.city} поза рамкою пака EU",
                EuropePack.bounds.contains(feed.anchorLat, feed.anchorLon),
            )
        }
        assertTrue(EuropePack.bounds.contains(EuropePack.home.lat, EuropePack.home.lon))
    }
}
