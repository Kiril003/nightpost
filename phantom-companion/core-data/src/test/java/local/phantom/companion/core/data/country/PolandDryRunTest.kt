package local.phantom.companion.core.data.country

import java.io.File
import local.phantom.companion.core.data.law.CurfewRules
import local.phantom.companion.core.data.transit.GtfsFeedRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ATLAS-3 — СУХА ПРОГОНКА ДРУГОЇ КРАЇНИ.
 *
 * Польща тут навмисно порожня: рамка, дім у Варшаві й більше нічого. Це не
 * недороблений пак, а найкорисніша його форма — вона доводить, що відсутність
 * типізована ПОПОЛЬНО, а не однією гілкою «країна не наша». Пігулка тривог
 * мовчить, бо стрічки НЕ ОГОЛОШЕНО; укриття не викликаються, бо небо ніхто не
 * читає; комендантська тиха, бо правил нема; київської моделі не існує, бо
 * пак не оголосив жодного асета — і кожна з цих тиш має власну причину.
 *
 * Пак тестовий і в збірку не їде: ані фідів, ані GTFS Польщі ми не перевіряли.
 */
class PolandDryRunTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = PackSigner()
    private val warsaw = 52.2297 to 21.0122
    private val kyiv = 50.4501 to 30.5234

    @After
    fun tearDown() = CountryPackRegistry.reset()

    private fun store(root: File = temp.root) = CountryPackStore(
        root = root,
        publicKeyHex = signer.publicHex,
        appBuild = 100,
        audit = { emptyList() },
    )

    private fun installPoland(): CountryPackStore {
        val s = store()
        assertTrue(s.install(signer.sign(polandManifest())) is PackInstall.Accepted)
        s.activate()
        return s
    }

    @Test
    fun `встановлений пак стає активним за фіксом, а Україна лишається підлогою`() {
        installPoland()
        // EU стоїть ОСТАННІМ, і це не косметика: його рамка — весь континент,
        // тож раніше в списку він перебив би і Україну, і кожен завантажений
        // країнний пак. Останнім він є тим, чим є, — підлогою «ти в Європі,
        // своєї країни ще не завантажив».
        assertEquals(listOf("UA", "PL", "EU"), CountryPackRegistry.packs.map { it.id })
        assertEquals("PL", CountryPackRegistry.locate(warsaw.first, warsaw.second)?.id)
        // Підлога не зникає від того, що приїхав сусід.
        assertEquals("UA", CountryPackRegistry.locate(kyiv.first, kyiv.second)?.id)
    }

    /**
     * Осьові рамки сусідів перетинаються: рамка Польщі (до 24.2° сх. д.)
     * накриває Львів. Підлога стоїть у списку першою, і саме тому оператор у
     * Львові лишається в українському паку — з тривогами й комендантською, а
     * не в польському, де тиша. Порядок тут — не косметика.
     */
    @Test
    fun `у смузі перетину рамок виграє вбудований пак`() {
        installPoland()
        assertEquals("UA", CountryPackRegistry.locate(49.8397, 24.0297)?.id)
    }

    @Test
    fun `дім — Варшава, а не Київ і не глобус`() {
        installPoland()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        assertEquals(warsaw.first, CountryPackRegistry.home().lat, 1e-4)
        assertEquals(warsaw.second, CountryPackRegistry.home().lon, 1e-4)
    }

    @Test
    fun `небо Польщі не оголошене — пігулка мовчить, а не показує спокій`() {
        installPoland()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        assertNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE))
        assertNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.TYPED))
        assertNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.DETAIL))
        assertTrue(CountryPackRegistry.current()?.alarmFeeds?.isEmpty() == true)
    }

    @Test
    fun `геометрії регіонів нема — виклик укриттів нема з чого зробити`() {
        installPoland()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        assertNull(CountryPackRegistry.regionGeometryAsset())
        assertNull(CountryPackRegistry.regionIndexAsset())
    }

    @Test
    fun `київської мережі у Варшаві не існує — і цикл флоту стоїть`() {
        installPoland()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        assertNull(CountryPackRegistry.poiAsset())
        assertNull(CountryPackRegistry.metroAsset())
        assertNull(CountryPackRegistry.surfaceAsset())
    }

    @Test
    fun `комендантська Польщі тиха, а українська лишається українською`() {
        installPoland()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        assertNull("правила для PL не вигадуються", CurfewRules.ruleFor("PL"))
        // Таблиці читаються з УСІХ пакiв: закон України не залежить від того,
        // де стоїть оператор — його гейт власний, за regionId.
        assertNull(CurfewRules.matchAt("PL", 0L))
        assertTrue(CurfewRules.rules.any { it.regionId == "UA" })
    }

    @Test
    fun `GTFS Польщі порожній — і це не порожній транспорт, а відсутнє джерело`() {
        installPoland()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        assertNull(GtfsFeedRegistry.feedFor(warsaw.first, warsaw.second))
        // Львівський фід лишається львівським — він у своєму паку, зі своїм якорем.
        assertTrue(GtfsFeedRegistry.feeds.any { it.city == "Львів" })
    }

    @Test
    fun `ручний вибір Польщі тримається в Києві`() {
        installPoland()
        CountryPackRegistry.setOverride("PL")
        assertEquals("PL", CountryPackRegistry.locate(kyiv.first, kyiv.second)?.id)
        assertNull("тривог Польщі не оголошено — небо мовчить і в Києві", CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE))
    }

    @Test
    fun `манiфест переживає перезапуск, бо лежить на диску підписаним`() {
        installPoland()
        // Новий store над тим самим каталогом — саме те, що робить наступний старт.
        assertEquals(listOf("PL"), store().downloaded().map { it.id })
    }

    @Test
    fun `підмінений на диску манiфест не переживає перезапуск`() {
        installPoland()
        File(temp.root, "PL/manifest.json").let { it.writeText(it.readText().replace("52.2297", "41.9")) }
        assertTrue("підпис звіряється на кожному читанні", store().downloaded().isEmpty())
        assertEquals(listOf("UA", "EU"), store().activate().map { it.id })
    }

    @Test
    fun `пак, що оголосив асет і не привіз його, не встановлюється`() {
        val s = store()
        val text = signer.sign(polandManifest(extra = ""","poiAsset":"poi.json""""))
        val outcome = s.install(text)
        assertEquals(PackReject.MALFORMED, (outcome as PackInstall.Rejected).reason)
        assertTrue("poi.json" in outcome.detail)
    }

    @Test
    fun `привезений асет читається з каталогу пака, не зі збірки`() {
        val s = store()
        File(temp.root, "PL").mkdirs()
        File(temp.root, "PL/poi.json").writeText("""{"generated":1,"places":[]}""")
        val text = signer.sign(polandManifest(extra = ""","poiAsset":"poi.json""""))
        assertTrue(s.install(text) is PackInstall.Accepted)
        s.activate()
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
        val pack = CountryPackRegistry.current()!!
        assertEquals(PackAssetSource.Directory(File(temp.root, "PL").path), pack.assetSource)
    }
}
