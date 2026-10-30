package local.phantom.companion.core.geo

import local.phantom.companion.core.geo.layers.LayerCatalog
import local.phantom.companion.core.geo.layers.LayerDomain
import local.phantom.companion.core.geo.layers.LayerTheme
import local.phantom.companion.core.geo.layers.UpdateMethod
import local.phantom.companion.core.geo.layers.defaultTtlMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Реєстр шарів карти.
 *
 * [LicenseFirewallTest] уже перевіряє юридичну чистоту каталогу. Тут — інше:
 * навігація по реєстру (byId, byTheme) і, головне, чесність прапорця «працює
 * офлайн». Останнє під РЕБ не косметика: показати `REALTIME_FEED` як доступний
 * без мережі означало б видати старі дані за актуальні, а це гірше, ніж чесно
 * сказати «зараз недоступно».
 */
class LayerCatalogTest {

    @Test
    fun `пошук за ідентифікатором знаходить шар і чесно відмовляє на невідомому`() {
        val cameras = LayerCatalog.byId("speed-cameras")
        assertNotNull("шар камер має бути в каталозі", cameras)
        assertEquals("byId повертає саме запитаний шар", "speed-cameras", cameras!!.id)
        assertEquals("тема шару камер — контроль", LayerTheme.ENFORCEMENT, cameras.theme)

        assertNull("неіснуючий шар — це null, а не перший-ліпший", LayerCatalog.byId("не-існує"))
    }

    @Test
    fun `вибірка за темою повертає всі шари теми і лише їх`() {
        val hazard = LayerCatalog.byTheme(LayerTheme.HAZARD)
        val ids = hazard.map { it.id }.toSet()

        assertTrue("усі повернені шари справді теми HAZARD", hazard.all { it.theme == LayerTheme.HAZARD })
        assertEquals(
            "тема небезпек — пожежі, повітря, землетруси",
            setOf("fires-firms", "air-quality-aqicn", "earthquakes-usgs"), ids,
        )
        // Дзеркальна перевірка: жоден шар іншої теми не просочився.
        assertTrue(
            "шар іншої теми не має потрапляти у вибірку HAZARD",
            LayerCatalog.byTheme(LayerTheme.BASE).none { it.theme == LayerTheme.HAZARD },
        )
    }

    @Test
    fun `розбивка за темами повна і без втрат`() {
        // Кожен шар потрапляє рівно в одну тематичну вибірку, а їх сума дорівнює
        // всьому каталогу: жоден шар не «зникає» з екрана «Джерела даних» і не
        // рахується двічі.
        val viaThemes = LayerTheme.entries.flatMap { LayerCatalog.byTheme(it) }
        assertEquals("сума всіх тем дорівнює каталогу", LayerCatalog.specs.size, viaThemes.size)
        assertEquals(
            "жоден шар не загубився і не задвоївся при розбивці за темами",
            LayerCatalog.specs.map { it.id }.toSet(), viaThemes.map { it.id }.toSet(),
        )
    }

    @Test
    fun `офлайновими вважаються рівно запечені в PMTiles шари`() {
        val offline = LayerCatalog.offlineCapable()

        assertTrue("офлайн-каталог не порожній — інакше карти без мережі немає", offline.isNotEmpty())
        assertTrue(
            "кожен офлайновий шар справді позначений як запечений PMTiles",
            offline.all { it.update == UpdateMethod.BAKED_PMTILES },
        )

        val offlineIds = offline.map { it.id }.toSet()
        assertEquals(
            "офлайн працюють базова підкладка, адреси та залізниця — усе, що печеться в пакет",
            setOf("overture-base-cdla", "overture-base-odbl", "overture-addresses", "railway-network"),
            offlineIds,
        )
    }

    @Test
    fun `стрічки реального часу не видаються за офлайнові`() {
        // Найдорожча помилка цього реєстру: показати рух транспорту чи пожежі як
        // доступні без мережі. Їх у офлайн-наборі бути не може за визначенням.
        val offlineIds = LayerCatalog.offlineCapable().map { it.id }.toSet()
        assertTrue("транзит реального часу — не офлайновий", "transit-realtime" !in offlineIds)
        assertTrue("пожежі FIRMS — не офлайнові", "fires-firms" !in offlineIds)

        val realtime = LayerCatalog.specs.filter { it.update == UpdateMethod.REALTIME_FEED }
        assertTrue("у каталозі мають бути стрічки реального часу", realtime.isNotEmpty())
        assertTrue("жодна стрічка реального часу не потрапила в офлайн-набір", realtime.none { it.id in offlineIds })
    }

    @Test
    fun `час життя шару випливає зі способу оновлення, а не з місця показу`() {
        // Шар, покладений з чату, з меню карти чи фоновим завданням, має
        // протухати однаково — інакше та сама станція «свіжа» в одному місці і
        // «стара» в іншому, і жодне з двох тверджень не варте довіри.
        assertNull(
            "запечені тайли не рухаються — вони не мають протухати взагалі",
            UpdateMethod.BAKED_PMTILES.defaultTtlMs,
        )
        assertNull("власні дані живуть, доки ми їх не змінимо", UpdateMethod.INTERNAL.defaultTtlMs)
        assertEquals(
            "витягнуте з Overpass на вимогу перепитується через шість годин",
            6 * 60 * 60_000L, UpdateMethod.OVERPASS_ON_DEMAND.defaultTtlMs,
        )
        assertEquals(
            "стрічка реального часу без мережі не існує — пʼять хвилин і все",
            5 * 60_000L, UpdateMethod.REALTIME_FEED.defaultTtlMs,
        )
    }

    @Test
    fun `жодна стрічка реального часу не живе на карті довше за хвилини`() {
        // Дзеркало попереднього тесту на рівні каталогу: якби комусь спало на
        // думку позначити пожежі як PERIODIC_IMPORT, шар почав би жити годинами
        // і показував би вчорашній вогонь як сьогоднішній.
        val realtime = LayerCatalog.specs.filter { it.update == UpdateMethod.REALTIME_FEED }
        assertTrue(
            "кожна стрічка реального часу має TTL у межах десяти хвилин",
            realtime.all { (it.update.defaultTtlMs ?: Long.MAX_VALUE) <= 10 * 60_000L },
        )
    }

    @Test
    fun `сфера-сип приземлилась у Рух і місцевість, а не де завгодно`() {
        // Ці три id мають лишатись стабільними — MapGroundLayers (feature-
        // stream) шле SpatialLayer з kind, що дорівнює саме цим id.
        val ids = LayerCatalog.byDomain(LayerDomain.MOVEMENT_TERRAIN).map { it.id }.toSet()
        assertTrue(
            "опори ЛЕП, канави/труби та броди мають бути в Рух і місцевість",
            setOf("power-towers", "drainage-crossings", "fords").all { it in ids },
        )
    }

    @Test
    fun `квік-доступ читається з реєстру, не з окремого списку`() {
        val quick = LayerCatalog.quickAccessLayers().map { it.id }.toSet()
        assertTrue("камери лишаються в тір 1", "speed-cameras" in quick)
        assertTrue("нові шари сфери-сипу теж у тір 1", quick.containsAll(setOf("power-towers", "drainage-crossings", "fords")))
    }

    @Test
    fun `жоден новий шар сфери-сипу не увімкнений без запиту`() {
        // Рішення default_active з ia-map-structure.md §3 Tier 0: тиша
        // небезпечна лише там, де вона зазвичай норма (тривога). Опори ЛЕП,
        // канави й броди — розвідувальна зручність, не сигнал безпеки.
        val fresh = listOf("power-towers", "drainage-crossings", "fords")
        assertTrue(
            "жоден із нових шарів не має default_active=true",
            fresh.all { LayerCatalog.byId(it)?.defaultActive == false },
        )
    }

    @Test
    fun `кожен шар має валідний діапазон зумів`() {
        // minZoom > maxZoom зробив би шар невидимим на всіх зумах — тихий дефект,
        // який не помітиш, поки шар просто «не показується».
        for (spec in LayerCatalog.specs) {
            assertTrue(
                "шар ${spec.id}: minZoom ${spec.minZoom} не може бути більшим за maxZoom ${spec.maxZoom}",
                spec.minZoom <= spec.maxZoom,
            )
        }
    }
}
