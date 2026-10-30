package local.phantom.companion.feature.precision

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.sensor.nav.LocalRouter
import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.core.sensor.nav.RoadMeshStore
import local.phantom.companion.core.sensor.nav.RoadProfile
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ЧИ ДОЇХАВ ПАКЕТ ДО МАРШРУТУ — на живому апараті, без мережі.
 *
 * Тут доводили «функція працює» модульним тестом і мали зелене на дорозі,
 * якою ніхто не їде. Цей тест міряє інше: що САМЕ ТОЙ файл, який пече
 * `mesh_bake.py` і віддає ПК, пройшовши САМЕ ТОЙ імпортер, дає маршрутизатору
 * швидкість ІЗ ДЖЕРЕЛА, а не пласке припущення.
 *
 * Чому не юніт-тест: `bulkImport` — це SQLite на реальному сховищі телефона,
 * а стеля `MAX_ASTAR_NODES` видно лише на справжній густині міста.
 *
 * Міряються КІЛЬКА відстаней навмисно. `LocalRouter` має стелю розгортання
 * A* у 60 000 вузлів, і коментар при `snapFor` фіксує, що на пакеті З ТЕГАМИ
 * фронт розпухає легше, ніж на беззнаковому. Одне «так/ні» на одну відстань
 * сказало б, що маршрут є або що його нема, і приховало б, ДЕ саме проходить
 * межа. Довжина, на якій маршрут зникає, — це число продукту, не тесту.
 *
 * Пакет НЕ лежить у репозиторії (50 МБ):  scripts/measure-roadpack-on-device.sh
 */
@RunWith(AndroidJUnit4::class)
class RoadPackReachesTheRouterTest {

    private data class Leg(val name: String, val toLat: Double, val toLon: Double)

    @Test
    fun a_baked_kyiv_pack_imports_and_gives_the_router_a_real_speed_basis() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = File(ctx.getExternalFilesDir(null), PACK_NAME)
        Assume.assumeTrue(
            "покладіть пакет у ${pack.absolutePath} — scripts/measure-roadpack-on-device.sh",
            pack.exists() && pack.length() > 0,
        )

        val importedAt = System.currentTimeMillis()
        val result = RoadPackImporter.importFromFile(ctx, pack, deleteAfter = false)
        val importMs = System.currentTimeMillis() - importedAt

        assertTrue("імпорт не пройшов: $result", result is RoadPackImporter.Result.Success)
        val ok = result as RoadPackImporter.Result.Success
        Log.i(TAG, "import: ways=${ok.wayCount} hasTags=${ok.hasTags} v=${ok.formatVersion} ms=$importMs")
        assertTrue("пакет приїхав без тегів — міряти клас швидкості нема на чому", ok.hasTags)

        val store = RoadMeshStore(ctx)
        val router = LocalRouter()
        var bestBasis: RoadProfile.SpeedBasis? = null
        var longestRoutedKm = 0.0
        var graphWays = 0

        for (leg in LEGS) {
            // Коридор на КОЖЕН відрізок, як це робить AppContainer: цілий граф
            // міста не влазить у купу застосунку, і продукт його не будує.
            val graph = RoadGraph(
                store.waysIn(
                    minOf(FROM_LAT, leg.toLat) - PAD,
                    minOf(FROM_LON, leg.toLon) - PAD,
                    maxOf(FROM_LAT, leg.toLat) + PAD,
                    maxOf(FROM_LON, leg.toLon) + PAD,
                ),
            )
            graphWays = graph.wayCount
            val r = router.route(
                graph, FROM_LAT, FROM_LON, leg.toLat, leg.toLon,
                // Пішки знак не діє за побудовою (RoadProfile: «A posted limit
                // is a car's ceiling»), тож клас швидкості міряє лише авто.
                footProfile = false,
            )
            Log.i(TAG, "leg ${leg.name}: corridor ways=$graphWays")
            if (r == null) {
                Log.i(TAG, "leg ${leg.name}: NULL (маршруту нема)")
                continue
            }
            val km = r.distanceMeters / 1000.0
            Log.i(
                TAG,
                "leg ${leg.name}: basis=${r.speedBasis} posted=${"%.3f".format(r.postedShare)} " +
                    "dist=${"%.2f".format(km)}km dur=${r.durationSeconds.toInt()}s pts=${r.points.size}",
            )
            if (km > longestRoutedKm) longestRoutedKm = km
            if (bestBasis == null || r.speedBasis.ordinal < bestBasis!!.ordinal) {
                bestBasis = r.speedBasis
            }
        }
        store.close()

        Log.i(TAG, "verdict: bestBasis=$bestBasis longestRouted=${"%.2f".format(longestRoutedKm)}km")

        assertTrue(
            "жоден із ${LEGS.size} відрізків Києва не проклався офлайн — " +
                "пакет у сховищі є ($graphWays доріг в останньому коридорі), а маршруту нема",
            bestBasis != null,
        )
        assertNotEquals(
            "маршрут повернувся з пласким припущенням швидкості — теги пакета " +
                "до маршрутизатора не доїхали",
            RoadProfile.SpeedBasis.ASSUMED,
            bestBasis,
        )
    }

    private companion object {
        const val TAG = "RoadPackReach"
        const val PACK_NAME = "phantom_road_mesh.kyiv-v3.db"

        // Дім власника — та сама точка, на якій 30.08 заміряли «11,36 км на
        // старому пакеті і жодного на новому» (коментар при LocalRouter.snapFor).
        const val FROM_LAT = 50.504668
        const val FROM_LON = 30.410170

        val LEGS = listOf(
            Leg("1_akademmistechko", 50.4650, 30.3550), // ~5 км
            Leg("2_shuliavka", 50.4547, 30.4460), // ~6.5 км
            Leg("3_centre_maidan", 50.4501, 30.5234), // ~11 км дорогою
            Leg("4_livyi_bereh", 50.4420, 30.6020), // ~15 км
        )

        const val PAD = 0.03
    }
}
