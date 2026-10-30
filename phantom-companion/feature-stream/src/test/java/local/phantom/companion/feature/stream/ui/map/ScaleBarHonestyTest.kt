package local.phantom.companion.feature.stream.ui.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лінійка масштабу мусить бути підписана своєю справжньою довжиною.
 *
 * **Заміряно на Galaxy A52 20.09** (1080×2400, щільність 420 = ×2,625,
 * збірка 14). Три шпильки довгим дотиком, камера між ними не рухалась:
 *
 * ```
 * A (200,500)  → 50,47928 / 30,50112
 * B (900,500)  → 50,47928 / 30,54689   (та сама широта, що A)
 * C (200,1500) → 50,43765 / 30,50112   (та сама довгота, що A)
 * ```
 *
 * 700 px = 3243 м і 1000 px = 4626 м — тобто **216,0 px/км по обох осях**.
 * Смуга при цьому мала суцільний відрізок 205 px, тобто **0,95 км**, а підпис
 * казав **5,0 км**. Після подвійного дотику: 431,6 px/км, відрізок 163 px =
 * 0,378 км, підпис **2,0 км**. Похибка 5,27× і 5,30× — стала, отже не
 * округлення: 5,28 / 2,625 = 2,01.
 *
 * Це застосунок, у якому по мапі вирішують, чи добіжиш до укриття. Смуга, що
 * перебільшує впʼятеро, каже «встигнеш» там, де не встигнеш.
 *
 * **Чому лінійка тут своя.** Перша редакція цього тесту міряла відрізок тим
 * самим `metersPerDp`, який і підписує смугу, — і мовчки зеленіла зі старою
 * формулою, бо звіряла продукт сам із собою. Тому ярд тут виведено окремо й
 * рівно так, як його зняли шпильками: різниця довгот на 700 px, помножена на
 * метри в градусі довготи для цієї широти. Спільного з продуктом у нього лише
 * сфера.
 */
class ScaleBarHonestyTest {

    /** Щільність A52: 420 dpi / 160 — те, чим dp перетворюються на пікселі скла. */
    private val a52Density = 2.625

    /** Стеля ширини смуги в dp — стільки їй лишає платівка приладів. */
    private val maxDp = 88

    /** Метрів у градусі довготи на екваторі: 40 075 016,686 / 360. */
    private val metersPerDegLonAtEquator = 111_319.490_8

    /**
     * Скільки пікселів скла має світ на цьому зумі.
     *
     * MapLibre тримає **512 dp на тайл** — це і є те, чого не знала стара
     * формула смуги: вона рахувала на тайл 256 px.
     */
    private fun worldPx(zoom: Double, density: Double) = 512.0 * 2.0.pow(zoom) * density

    /** Ярд: метри на один піксель скла, виведені з геометрії Меркатора, не з продукту. */
    private fun yardMetersPerPx(lat: Double, zoom: Double, density: Double = a52Density): Double =
        (360.0 / worldPx(zoom, density)) * metersPerDegLonAtEquator * cos(Math.toRadians(lat))

    private data class Shot(
        val name: String,
        val lat: Double,
        val zoom: Double,
        /** Знято з апарата: скільки пікселів скла припадає на кілометр землі. */
        val pxPerKm: Double,
        /** Суцільний відрізок смуги, зміряний по яскравості. */
        val segmentPx: Int,
        /** Справжня довжина того відрізка в кілометрах. */
        val segmentKm: Double,
    )

    /**
     * Обидва масштаби з апарата. Другий рядок — після подвійного дотику, тобто
     * рівно на один зум ближче: 431,6 / 216,0 = 1,998.
     */
    private val shots = listOf(
        Shot("Київ, далекий масштаб", 50.47928, 12.0, 216.0, 205, 0.95),
        Shot("Київ, після подвійного дотику", 50.47928, 13.0, 431.6, 163, 0.378),
    )

    // ── Поверх перший: чи ярд узгоджений із самим апаратом ───────────────

    /**
     * Шпильки A і B лежать на одній широті за 700 px одна від одної. Якщо ярд
     * не відтворює їхню різницю довгот, далі міряти нічим.
     */
    @Test
    fun `ярд відтворює саму розстановку шпильок`() {
        val degPerPxMeasured = (30.54689 - 30.50112) / 700.0
        val degPerPxYard = 360.0 / worldPx(12.0, a52Density)
        assertEquals("градусів довготи на піксель", degPerPxMeasured, degPerPxYard, 1e-8)

        // Ті самі 700 px у метрах — і те саме по вертикалі, 1000 px.
        assertEquals("700 px по довготі", 3243.0, 700 * yardMetersPerPx(50.47928, 12.0), 15.0)
        assertEquals("1000 px по широті", 4626.0, 1000 * yardMetersPerPx(50.47928, 12.0), 20.0)
    }

    @Test
    fun `ярд сходиться з пікселями на кілометр, знятими з апарата`() {
        shots.forEach { s ->
            val pxPerKm = 1000.0 / yardMetersPerPx(s.lat, s.zoom)
            assertEquals(
                "${s.name}: ярд дає ${"%.1f".format(pxPerKm)} px/км, апарат показав ${s.pxPerKm}",
                s.pxPerKm,
                pxPerKm,
                1.0,
            )
            // І навпаки: зміряний відрізок має свою зміряну довжину.
            assertEquals(
                "${s.name}: ${s.segmentPx} px",
                s.segmentKm,
                s.segmentPx * yardMetersPerPx(s.lat, s.zoom) / 1000.0,
                0.01,
            )
        }
    }

    // ── Поверх другий: підпис проти відрізка. Це і є ворота ──────────────

    @Test
    fun `підпис смуги дорівнює довжині відрізка на склі`() {
        shots.forEach { s ->
            val bar = scaleBarFor(metersPerDp(s.lat, s.zoom), maxDp)
            assertNotNull("${s.name}: смуги немає взагалі", bar)
            bar!!
            val drawnPx = bar.widthDp * a52Density
            val trueM = drawnPx * yardMetersPerPx(s.lat, s.zoom)
            // Ширина смуги — ціле число dp, тож на зрізанні губиться менше за
            // одне dp; усе, що більше, — це вже брехня, а не округлення.
            val slackM = a52Density * yardMetersPerPx(s.lat, s.zoom)
            assertTrue(
                "${s.name}: підпис ${bar.meters} м на відрізку ${"%.0f".format(trueM)} м " +
                    "(${bar.widthDp} dp = ${drawnPx.roundToInt()} px) — " +
                    "перебільшення в ${"%.2f".format(bar.meters / trueM)} раза",
                abs(bar.meters - trueM) <= slackM,
            )
        }
    }

    /**
     * Метри на градус довготи залежать від широти, і саме там ховаються такі
     * вади: зайвий сталий множник бреше однаково в Києві й Остраві, а
     * переплутаний косинус — по-різному. Тому обидві широти й уся драбина
     * масштабів, від міста до двору.
     */
    @Test
    fun `смуга чесна на обох широтах і на всій драбині масштабів`() {
        mapOf("Київ" to 50.45, "Острава" to 49.83).forEach { (city, lat) ->
            listOf(10.0, 12.0, 13.0, 15.0, 17.0, 18.5).forEach { zoom ->
                val bar = scaleBarFor(metersPerDp(lat, zoom), maxDp)
                assertNotNull("$city z$zoom: смуги немає", bar)
                bar!!
                val trueM = bar.widthDp * a52Density * yardMetersPerPx(lat, zoom)
                val slackM = a52Density * yardMetersPerPx(lat, zoom)
                assertTrue(
                    "$city z$zoom: підпис ${bar.meters} м проти відрізка ${"%.1f".format(trueM)} м",
                    abs(bar.meters - trueM) <= slackM,
                )
                assertTrue("$city z$zoom: ${bar.widthDp} dp ширше за стелю", bar.widthDp <= maxDp)
            }
        }
    }

    // ── Сторож формули: щоб при поверненні множника було видно ЧОМУ ──────

    /**
     * Стара формула брала `metersPerPixel(lat, zoom) * density` і помилялась
     * двічі в один бік: бракувало ділення на 2 (тайл MapLibre 512 px проти 256
     * у формулі) і множення на щільність було зайвим — величина вже логічна.
     * 2 × 2,625 = 5,25, і це рівно те, що видно на склі.
     */
    @Test
    fun `стара формула відтворює обидві брехливі смуги з апарата`() {
        val lat = 50.47928
        listOf(12.0, 13.0).forEach { zoom ->
            val old = metersPerPixel(lat, zoom) * a52Density
            assertEquals(
                "z$zoom: коефіцієнт похибки",
                2.0 * a52Density,
                old / metersPerDp(lat, zoom),
                1e-9,
            )
        }
        val far = scaleBarFor(metersPerPixel(lat, 12.0) * a52Density, maxDp)!!
        assertEquals("стара смуга підписувалась 5 км", 5_000.0, far.meters, 0.1)
        assertEquals("…на 78 dp, тобто 205 px", 205, (far.widthDp * a52Density).roundToInt())

        val near = scaleBarFor(metersPerPixel(lat, 13.0) * a52Density, maxDp)!!
        assertEquals("ближній масштаб підписувався 2 км", 2_000.0, near.meters, 0.1)
        assertEquals("…на 62 dp, тобто 163 px", 163, (near.widthDp * a52Density).roundToInt())
    }
}
