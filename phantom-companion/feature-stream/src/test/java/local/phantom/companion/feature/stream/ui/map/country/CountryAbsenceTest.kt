package local.phantom.companion.feature.stream.ui.map.country

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.country.AlarmFeedRole
import local.phantom.companion.core.data.country.CountryPack
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.country.MapHome
import local.phantom.companion.core.data.country.PackBounds
import local.phantom.companion.feature.stream.geo.MapGroundLayers
import local.phantom.companion.feature.stream.ui.map.AirAlertWatch
import local.phantom.companion.feature.stream.ui.map.AlertFreshness
import local.phantom.companion.feature.stream.ui.map.AlertState
import local.phantom.companion.feature.stream.ui.map.SkyVerdict
import local.phantom.companion.feature.stream.ui.map.atlas.ChromeSlot
import local.phantom.companion.feature.stream.ui.map.atlas.LensEngine
import local.phantom.companion.feature.stream.ui.map.atlas.LensInput
import local.phantom.companion.feature.stream.ui.map.verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-3 — за кордоном застосунок мовчить, бо НЕ ЗНАЄ, а не тому, що тихо.
 *
 * Найдорожча помилка тут не порожній екран, а свіжий порожній знімок: він
 * читається пігулкою як «тиша», і турист у Барселоні дізнається про спокій,
 * якого ніхто не перевіряв.
 */
class CountryAbsenceTest {

    private val kyiv = LocationFix(50.4501, 30.5234)

    // Сторож бере власний годинник: знімок публікується стінним часом, тож і
    // судиться ним — інакше «свіжо» залежало б від застиглої константи.
    private fun nowMs() = System.currentTimeMillis()
    private val warsaw = 52.2297 to 21.0122

    @After
    fun tearDown() = CountryPackRegistry.reset()

    /**
     * Суха прогонка другої країни на боці екрана. Пак Польщі — рамка й дім,
     * і жодної стрічки: рівно те, що приїде першим завантаженим паком.
     */
    private object PolandPack : CountryPack {
        override val id = "PL"
        override val bounds = PackBounds(49.0, 14.1, 54.9, 24.2)
        override val home = MapHome(52.2297, 21.0122, 12.0)
        override val regionGeometryAsset: String? = null
        override val regionIndexAsset: String? = null
        override val alarmFeeds = emptyList<local.phantom.companion.core.data.country.AlarmFeed>()
        override val curfewRules = emptyList<local.phantom.companion.core.data.law.CurfewRule>()
        override val transitFeeds = emptyList<local.phantom.companion.core.data.transit.GtfsCityFeed>()
        override val rememberedRegions = emptySet<String>()
    }

    private fun standInWarsaw() {
        CountryPackRegistry.install(listOf(PolandPack))
        CountryPackRegistry.locate(warsaw.first, warsaw.second)
    }

    private fun watch(
        skyDeclared: (LocationFix?) -> Boolean,
        base: suspend () -> Map<String, AlertState>?,
    ) = AirAlertWatch(
        scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        fetchBase = base,
        oblastAt = { _, _ -> "Київська область" },
        skyDeclared = skyDeclared,
        locationSource = { kyiv },
    )

    @Test
    fun `нема пака — знімок типізовано відсутній, а не порожньо-свіжий`() = runTest {
        var fetched = 0
        val w = watch(skyDeclared = { false }, base = { fetched++; emptyMap() })
        w.pollOnce()

        val sky = w.sky.value
        assertEquals(AlertFreshness.UNKNOWN, sky.freshnessAt(nowMs()))
        assertNull("свіжість без даних — це «не знаю»", sky.ownActiveAt(nowMs()))
        assertNull(sky.ownOblast)
        assertTrue(sky.oblasts.isEmpty())
        assertEquals("стрічку країни не чіпаємо взагалі", 0, fetched)
    }

    @Test
    fun `перетин кордону стирає чуже небо, а не лишає його висіти`() = runTest {
        var declared = true
        val w = watch(
            skyDeclared = { declared },
            base = { mapOf("Київська область" to AlertState(active = true, changedEpochMs = 1L)) },
        )
        w.pollOnce()
        assertEquals(true, w.sky.value.ownActiveAt(nowMs()))

        declared = false
        w.pollOnce()
        assertTrue("українська тривога не має пережити кордон", w.sky.value.oblasts.isEmpty())
        assertNull(w.sky.value.ownActiveAt(nowMs()))
    }

    @Test
    fun `пак Польщі — дім Варшава, і небо ніхто не читає`() {
        standInWarsaw()
        assertEquals(52.2297, countryHome().lat, 1e-4)
        assertEquals(21.0122, countryHome().lon, 1e-4)
        // Пак є, тривог у ньому нема — це різні відсутності, і саме тому
        // «нема пака» й «пак без стрічок» мають давати ту саму тишу.
        assertNull(CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE))
    }

    @Test
    fun `у Варшаві знімок неба типізовано відсутній, а стрічку ніхто не смикає`() = runTest {
        standInWarsaw()
        var fetched = 0
        val w = watch(
            skyDeclared = { CountryPackRegistry.alarmFeed(AlarmFeedRole.REGION_STATE) != null },
            base = { fetched++; emptyMap() },
        )
        w.pollOnce()
        assertEquals(AlertFreshness.UNKNOWN, w.sky.value.freshnessAt(nowMs()))
        assertNull(w.sky.value.ownActiveAt(nowMs()))
        assertEquals(0, fetched)
    }

    /**
     * Найтонше місце сухої прогонки: пігулка й виклик укриттів висять не на
     * країні, а на ЗДАТНОСТЯХ, які дає небо. Пак без стрічок їх не дає — тож
     * плашка мовчить і Лінза нікого не викликає, без жодної гілки «якщо PL».
     */
    @Test
    fun `без неба пігулка мовчить і укриття не викликаються`() {
        standInWarsaw()
        val frame = LensEngine().frame(LensInput(zoom = 15.0, countryPackPresent = true))
        assertFalse(frame.allows(ChromeSlot.ALERT_PILL))
        assertFalse(MapGroundLayers.SHELTERS in frame.summons)
    }

    // ── ЧЕХІЯ, 13.09 — «не знаю» теж є показом ──────────────────────────────
    //
    // Власник за кордоном: «коли немає даних про тривоги в країні, де я
    // знаходжусь, плашка має не показуватись ВЗАГАЛІ, а не показувати
    // помилку». Тести вище доводили лише, що знімок порожній; порожній знімок
    // давав `SkyVerdict.NOFEED`, а NOFEED — `feedSilent`, тобто пігулку зі
    // словами «про тривоги не знаю». Відсутність стрічки й мовчання стрічки
    // були одним вироком.

    @Test
    fun `країна без стрічки має власний вирок, а не мовчання каналу`() = runTest {
        val w = watch(skyDeclared = { false }, base = { emptyMap() })
        w.pollOnce()
        val v = w.sky.value.verdict(nowMs())
        assertEquals(SkyVerdict.NO_COUNTRY_FEED, v)
        assertFalse(
            "«тут стрічки немає» не є мовчазною стрічкою — інакше пігулка знову " +
                "скаже «про тривоги не знаю» над країною, де питання не стоїть",
            v.feedSilent,
        )
    }

    @Test
    fun `українське небо не отримує вироку відсутності`() = runTest {
        val w = watch(
            skyDeclared = { true },
            base = { mapOf("Київська область" to AlertState(active = false, changedEpochMs = 1L)) },
        )
        w.pollOnce()
        assertEquals(SkyVerdict.QUIET, w.sky.value.verdict(nowMs()))
    }

    /**
     * Повернення додому: вирок відсутності не має пережити кордон навіть тоді,
     * коли перший тік удома впав в офлайн і знімок лише перепублікували.
     */
    @Test
    fun `вирок відсутності знімається на першому ж тіку вдома`() = runTest {
        var declared = false
        var online = true
        val w = AirAlertWatch(
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            fetchBase = { mapOf("Київська область" to AlertState(active = true, changedEpochMs = 1L)) },
            oblastAt = { _, _ -> "Київська область" },
            skyDeclared = { declared },
            locationSource = { kyiv },
            online = { online },
        )
        w.pollOnce()
        assertEquals(SkyVerdict.NO_COUNTRY_FEED, w.sky.value.verdict(nowMs()))

        declared = true
        online = false
        w.pollOnce()
        assertFalse(
            "офлайн удома — це «стрічка мовчить», а не «стрічки тут немає»",
            w.sky.value.verdict(nowMs()) == SkyVerdict.NO_COUNTRY_FEED,
        )
    }

    /**
     * Лінза тримає ту саму межу, і тримає її ЗАБОРОНОЮ: навіть коли область
     * оператора відома й десь голосно (українська мапа, розгорнута за
     * кордоном памʼяттю), відсутність стрічки гасить пігулку.
     */
    @Test
    fun `Лінза гасить пігулку відсутністю стрічки, а не браком фактів`() {
        val lens = LensEngine()
        assertFalse(
            lens.frame(
                LensInput(
                    zoom = 15.0,
                    ownOblastKnown = true,
                    activeAlarmCount = 3,
                    skyFeedSilent = true,
                    skyFeedDeclared = false,
                ),
            ).allows(ChromeSlot.ALERT_PILL),
        )
        assertTrue(
            "а з оголошеною стрічкою пігулка лишається на місці",
            lens.frame(
                LensInput(zoom = 15.0, ownOblastKnown = true, skyFeedDeclared = true),
            ).allows(ChromeSlot.ALERT_PILL),
        )
    }

    /**
     * Дзеркало на джерелі, як у `MapUiStateReachTest`: композиції тут не
     * видно, тож доводиться сама рання відмова пігулки. Без неї Лінза ховала
     * б слот, а `AlertBanner` усе одно малював би себе там, де його кличуть
     * поза Лінзою.
     */
    @Test
    fun `рання відмова пігулки знає про країну без стрічки`() {
        val body = codeOf("ui/map/MapChromeParts.kt")
            .substringAfter("internal fun AlertBanner(")
            .substringBefore("val feedSilent =")
        assertTrue(
            "пігулка знову не має ранньої відмови на NO_COUNTRY_FEED — за кордоном " +
                "вона знову скаже «про тривоги не знаю»:\n$body",
            "SkyVerdict.NO_COUNTRY_FEED" in body && "return" in body,
        )
    }

    /** Коментар — не код: сторож, що читає його, ловить власний доказ. */
    private fun codeOf(rel: String): String =
        java.io.File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/$rel")
            .readLines()
            .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "корінь репозиторію не знайдено" }
    }
}
