package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-1 — the dot's promises: it glides, it never overshoots, it bridges
 * the 1 Hz gaps with the fix's own velocity, and it refuses to rubber-band
 * across a teleport.
 */
class FixFluxTest {

    private val kyivLat = 50.4501
    private val kyivLon = 30.5234

    /** Advance in 16 ms display frames, returning the last eased frame. */
    private fun FixFlux.run(fromMs: Long, toMs: Long): FixFlux.Eased {
        var last: FixFlux.Eased? = null
        var t = fromMs
        while (t <= toMs) {
            last = step(t)
            t += 16
        }
        return last!!
    }

    @Test
    fun `no target means no frame`() {
        assertNull(FixFlux().step(0))
    }

    @Test
    fun `first fix snaps instantly`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, speedMps = 0f, bearingDeg = null, uncertaintyM = 10f, atMs = 0)
        val f = flux.step(0)!!
        assertEquals(kyivLat, f.lat, 1e-9)
        assertEquals(kyivLon, f.lon, 1e-9)
    }

    @Test
    fun `converges onto a stepped target within half a second, without overshoot`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, null, 10f, atMs = 0)
        flux.run(0, 100)
        // The next 1 Hz fix lands 10 m north.
        val northLat = kyivLat + Math.toDegrees(10.0 / 6_371_000.0)
        flux.onTarget(northLat, kyivLon, 0f, null, 10f, atMs = 1000)

        var maxLat = kyivLat
        var t = 1000L
        while (t <= 1600) {
            val f = flux.step(t)!!
            if (f.lat > maxLat) maxLat = f.lat
            t += 16
        }
        val remainingM = Math.toRadians(northLat - maxLat) * 6_371_000.0
        // Critically damped: reaches the target (< 0.5 m away at ω=7 by
        // 600 ms) and never passes it.
        assertTrue("still $remainingM m short", remainingM < 0.5)
        assertTrue("overshot north of target", maxLat <= northLat + 1e-9)
    }

    @Test
    fun `bridges between fixes along the reported velocity`() {
        val flux = FixFlux()
        // Driving north at 10 m/s.
        flux.onTarget(kyivLat, kyivLon, speedMps = 10f, bearingDeg = 0f, uncertaintyM = 5f, atMs = 0)
        // Half a second later, no new fix has arrived — the dot should be
        // meaningfully north of the last published point, not parked on it.
        val f = flux.run(0, 500)
        val northM = Math.toRadians(f.lat - kyivLat) * 6_371_000.0
        assertTrue("only $northM m north after 500 ms at 10 m/s", northM > 2.0)
        // …but the bridge is capped: it never runs ahead of physics.
        assertTrue("ran ahead of the velocity itself: $northM m", northM <= 5.5)
    }

    @Test
    fun `teleport snaps instead of gliding across town`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, null, 10f, atMs = 0)
        flux.run(0, 200)
        // Reacquisition 2 km away.
        val farLat = kyivLat + Math.toDegrees(2000.0 / 6_371_000.0)
        flux.onTarget(farLat, kyivLon, 0f, null, 30f, atMs = 1000)
        val f = flux.step(1016)!!
        val offM = Math.toRadians(Math.abs(f.lat - farLat)) * 6_371_000.0
        assertTrue("dot is $offM m from the reacquired position — it glided", offM < 1.0)
    }

    @Test
    fun `settles when nothing moves`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, null, 10f, atMs = 0)
        val f = flux.run(0, 2000)
        assertTrue("still unsettled after 2 s on a static target", f.settled)
    }

    @Test
    fun `moving target never reports settled`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 10f, 0f, 5f, atMs = 0)
        val f = flux.run(0, 400)
        assertFalse(f.settled)
    }

    @Test
    fun `bearing eases by the shortest arc across north`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 2f, 350f, 5f, atMs = 0)
        flux.run(0, 1000)
        // Swing 20° clockwise through 0, not 340° the long way round.
        flux.onTarget(kyivLat, kyivLon, 2f, 10f, 5f, atMs = 1000)
        val mid = flux.step(1080)!!
        val b = mid.bearingDeg!!
        assertTrue(
            "bearing $b took the long way",
            b > 350f || b < 10.5f,
        )
        val end = flux.run(1096, 2000)
        assertEquals(10f, end.bearingDeg!!, 1.0f)
    }

    @Test
    fun `a fix without bearing keeps the last known bearing`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 2f, 90f, 5f, atMs = 0)
        flux.run(0, 500)
        // Operator stops at a crossing: speed 0, chip drops the bearing.
        flux.onTarget(kyivLat, kyivLon, 0f, null, 5f, atMs = 1000)
        val f = flux.run(1000, 1500)
        assertNotNull(f.bearingDeg)
        assertEquals(90f, f.bearingDeg!!, 1.0f)
    }

    @Test
    fun `uncertainty breathes toward the published radius`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, null, 10f, atMs = 0)
        flux.run(0, 100)
        flux.onTarget(kyivLat, kyivLon, 0f, null, 80f, atMs = 1000)
        val early = flux.step(1032)!!
        assertTrue("jumped straight to 80", early.uncertaintyM < 60f)
        val late = flux.run(1048, 4000)
        assertEquals(80f, late.uncertaintyM, 1.5f)
    }

    // ── CRITICAL FIELD FAILURE (Kyiv 2026-07-16) — trust-gated teleports ──

    private fun northOfM(m: Double) = kyivLat + Math.toDegrees(m / 6_371_000.0)

    private fun FixFlux.Eased.northM() = Math.toRadians(lat - kyivLat) * 6_371_000.0

    /** 50 teleports up to 12.8 km that drive — every one hurled the dot and
     *  the camera chasing it. An UNTRUSTED teleport must walk, never snap. */
    @Test
    fun `an untrusted teleport is slew-limited, not snapped`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, 0f, 10f, atMs = 0)
        flux.run(0, 100)

        // A spoofed estimate lands 5 km north, one second later.
        flux.onTarget(northOfM(5_000.0), kyivLon, 0f, 0f, 800f, atMs = 1000, trusted = false)
        val f = flux.run(1000, 2000)

        // In one second the display may advance at most SLEW_MAX_MPS worth
        // (plus the spring still converging on it) — not five kilometres.
        assertTrue(
            "the dot was hurled ${f.northM().toInt()} m by an untrusted fix",
            f.northM() < FixFlux.SLEW_MAX_MPS * 1.2 + 5.0,
        )
    }

    @Test
    fun `repeated untrusted teleports crawl, and a trusted fix snaps to truth`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, 0f, 10f, atMs = 0)
        flux.run(0, 100)

        // Ten seconds of a runaway estimate pinned 5 km north, at 1 Hz.
        var t = 1000L
        repeat(10) {
            flux.onTarget(northOfM(5_000.0), kyivLon, 0f, 0f, 900f, atMs = t, trusted = false)
            flux.run(t, t + 1000)
            t += 1000
        }
        val crawled = flux.step(t)!!
        // ~10 s × 45 m/s ceiling — the dot crawled toward it, far from arriving.
        assertTrue(
            "crawl exceeded the slew ceiling: ${crawled.northM().toInt()} m",
            crawled.northM() < FixFlux.SLEW_MAX_MPS * 10.0 * 1.2,
        )
        assertTrue("the dot must still be moving toward the estimate", crawled.northM() > 50.0)

        // The sky returns, TRUSTED: the operator never left home. The crawl
        // is hundreds of metres out — a trusted fix snaps straight back.
        flux.onTarget(kyivLat, kyivLon, 0f, 0f, 12f, atMs = t + 500, trusted = true)
        val f = flux.step(t + 516)!!
        assertEquals("a trusted re-acquisition snaps to the truth", 0.0, f.northM(), 2.0)
    }

    @Test
    fun `an untrusted jump inside the teleport radius still tracks normally`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, 0f, 0f, 10f, atMs = 0)
        flux.run(0, 100)
        // 60 m — inside TELEPORT_M: ordinary spring chase, trust irrelevant.
        flux.onTarget(northOfM(60.0), kyivLon, 0f, 0f, 40f, atMs = 1000, trusted = false)
        val f = flux.run(1000, 2500)
        assertEquals(60.0, f.northM(), 2.0)
    }

    // ── СТЕЛЯ ЧИСЛЕННЯ — КОЛО СУМНІВУ, А НЕ СЕКУНДИ (запис власника, 30.08) ──
    //
    // Заміряно на записі: намальована точка рухалась 1,6 с після кожного
    // оновлення (стара EXTRAP_CAP_S), а оновлення приходили з МЕДІАННИМ
    // проміжком 9 с. Тобто 18 % часу жива, 82 % мертва — «я їду, а точка
    // стоїть на місці, тільки телепортується».

    /** Скільки метрів проїхала б точка за старою стелею 1,6 с на 10 м/с —
     *  число, проти якого ці тести червоніли. */
    private val oldCapM = 1.6 * 10.0

    /**
     * ЧОМУ ЧЕРВОНІВ НА СТАРОМУ КОДІ: бридж обрубувався на 1,6 с, тож аім
     * замерзав на 16 м і точка стояла до кінця девʼяти секунд — заміри на
     * 2, 4 і 6 с давали те саме число (≈ 16 м), і перший же assert падав.
     */
    @Test
    fun `за девʼять секунд без оновлень точка проходить шлях, а не стоїть`() {
        val flux = FixFlux()
        // Їде на північ 10 м/с — і жодного наступного фікса, як у записі.
        flux.onTarget(kyivLat, kyivLon, speedMps = 10f, bearingDeg = 0f, uncertaintyM = 8f, atMs = 0)

        val at2s = flux.run(0, 2_000).northM()
        val at4s = flux.run(2_016, 4_000).northM()
        val at6s = flux.run(4_016, 6_000).northM()
        val at9s = flux.run(6_016, 9_008).northM()

        assertTrue("точка стала між 2 і 4 с: $at2s → $at4s м", at4s > at2s + 15.0)
        assertTrue("точка стала між 4 і 6 с: $at4s → $at6s м", at6s > at4s + 15.0)
        // Втричі більше шляху, ніж дозволяла стара стеля.
        assertTrue("за 9 с пройдено лише $at9s м (стара стеля давала $oldCapM)",
            at9s > oldCapM * 3.0)
        // …і жодного метра понад те, що дозволяє сама фізика: 9 с × 10 м/с.
        assertTrue("числення забігло поперед швидкості: $at9s м", at9s <= 90.0)
    }

    @Test
    fun `коло сумніву росте з часом числення і скидається на свіжому фіксі`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, speedMps = 10f, bearingDeg = 0f, uncertaintyM = 8f, atMs = 0)

        val a = flux.run(0, 1_008)
        val b = flux.run(1_024, 3_008)
        val c = flux.run(3_024, 5_008)

        // Вигадана частина росте монотонно…
        assertTrue("коло не росло: ${a.coastDoubtM} → ${b.coastDoubtM}", b.coastDoubtM > a.coastDoubtM)
        assertTrue("коло не росло: ${b.coastDoubtM} → ${c.coastDoubtM}", c.coastDoubtM > b.coastDoubtM)
        // …і чесне коло назовні росте разом із нею, понад виміряні 8 м.
        assertTrue("назовні коло не виросло: ${a.uncertaintyM} → ${c.uncertaintyM}",
            c.uncertaintyM > a.uncertaintyM + 5f)
        assertTrue("коло назовні дорівнює виміру — числення сховане",
            c.uncertaintyM > c.measuredUncertaintyM)
        // Вимір при цьому не поїхав: 8 м як опублікували, так і лишилось.
        assertEquals(8f, c.measuredUncertaintyM, 0.5f)

        // ЧОМУ ЧЕРВОНІВ: старий Eased не мав ні coastDoubtM, ні
        // measuredUncertaintyM, а uncertaintyM був лише згладженим виміром —
        // він стояв на 8 м усі пʼять секунд числення. Коло не росло взагалі.

        // Свіжий фікс — числення більше нічого не винне.
        flux.onTarget(northOfM(50.0), kyivLon, 10f, 0f, 8f, atMs = 5_008)
        val fresh = flux.step(5_008)!!
        assertEquals("свіжий фікс не скинув вигадане коло", 0f, fresh.coastDoubtM, 1e-6f)
        assertEquals("після скидання назовні мусить лишитись сам вимір",
            8f, fresh.uncertaintyM, 0.5f)
    }

    @Test
    fun `за порогом точка перестає бути точкою`() {
        val flux = FixFlux()
        flux.onTarget(kyivLat, kyivLon, speedMps = 10f, bearingDeg = 0f, uncertaintyM = 8f, atMs = 0)

        val inside = flux.run(0, 4_000)
        assertEquals("у межах бюджету кадр — ще точка", FixFlux.DrawAs.POINT, inside.drawAs)
        assertTrue("коло вже за бюджетом на 4 с: ${inside.coastDoubtM}",
            inside.coastDoubtM < FixFlux.DOUBT_BUDGET_M)

        val past = flux.run(4_016, 9_008)
        // ЧОМУ ЧЕРВОНІВ: у старому Eased не існувало стану взагалі — ані поля
        // drawAs, ані DrawAs.AREA; кадр за будь-якого віку лишався точкою, і
        // споживач не мав з чого дізнатись, що малює впевненість, якої немає.
        assertEquals("за бюджетом кадр мусить вимагати області",
            FixFlux.DrawAs.AREA, past.drawAs)
        assertTrue("стан змінився, а коло — ні: ${past.coastDoubtM}",
            past.coastDoubtM > FixFlux.DOUBT_BUDGET_M)

        // Пауза чесна, гладка брехня — ні: за бюджетом рух зупинено.
        val later = flux.run(9_024, 12_000)
        assertEquals("за бюджетом точка повзла далі", past.northM(), later.northM(), 0.5)
        // …але коло росте далі, бо оператор далі віддаляється.
        assertTrue("коло замерзло разом із точкою — це косметика",
            later.uncertaintyM > past.uncertaintyM)
        // …і кадр не сміє звітувати settled, інакше споживач перестане його
        // перемальовувати й область застигне неправильного розміру.
        assertFalse("область оголошено усталеною", later.settled)
    }

    @Test
    fun `нерухома людина не пливе`() {
        val flux = FixFlux()
        // Стоїть: швидкість 0, курс від компаса є, і 30 с без жодного фікса.
        flux.onTarget(kyivLat, kyivLon, speedMps = 0f, bearingDeg = 45f, uncertaintyM = 12f, atMs = 0)
        val f = flux.run(0, 30_000)

        assertEquals("нерухому точку понесло на північ", kyivLat, f.lat, 1e-12)
        assertEquals("нерухому точку понесло на схід", kyivLon, f.lon, 1e-12)
        // Числення нічого не малювало — отже й сумніву не додало. Вік чужого
        // виміру рахує рушій, що його опублікував, а не цей клас.
        assertEquals("додано сумнів там, де нічого не числили", 0f, f.coastDoubtM, 1e-6f)
        assertEquals("нерухому точку оголошено областю", FixFlux.DrawAs.POINT, f.drawAs)
        assertEquals(12f, f.uncertaintyM, 0.1f)

        // Те саме для доплерного шуму нижче порога бриджу.
        val jitter = FixFlux()
        jitter.onTarget(kyivLat, kyivLon, speedMps = 0.4f, bearingDeg = 200f, uncertaintyM = 12f, atMs = 0)
        val j = jitter.run(0, 30_000)
        assertEquals("шум швидкості 0,4 м/с потягнув точку", 0.0, j.northM(), 1e-9)
        assertEquals(0f, j.coastDoubtM, 1e-6f)
    }

    @Test
    fun `стеля числення міряється метрами сумніву, а не секундами`() {
        // Той самий бюджет дає РІЗНИЙ час для різних швидкостей: помилка курсу
        // коштує тим більше метрів, чим довший шлях. Саме це відрізняє
        // критерій «коло» від будь-якої фіксованої стелі в секундах — на якому
        // б числі її не поставили.
        fun horizonS(speedMps: Float): Double {
            val flux = FixFlux()
            flux.onTarget(kyivLat, kyivLon, speedMps, 0f, 5f, atMs = 0)
            var t = 0L
            while (t <= 30_000) {
                if (flux.step(t)!!.drawAs == FixFlux.DrawAs.AREA) return t / 1000.0
                t += 16
            }
            return Double.MAX_VALUE
        }

        val walk = horizonS(1.4f)    // пішки
        val city = horizonS(14f)     // 50 км/год
        val fast = horizonS(40f)     // 144 км/год

        assertTrue("міська швидкість не вкоротила числення: $walk с проти $city с", city < walk)
        assertTrue("траса не вкоротила числення: $city с проти $fast с", fast < city)
        // ЧОМУ ЧЕРВОНІВ: на старому коді всі три дорівнювали б нескінченності —
        // стану AREA не існувало, — а сам бридж завжди обрубувався на тих
        // самих 1,6 с для будь-якої швидкості.
        assertTrue("навіть на трасі числення мусить бути довшим за стару стелю 1,6 с: $fast с",
            fast > 3.0)
        assertTrue("числення розтягнулось понад медіанний проміжок запису: $walk с", walk < 9.0)
    }
}
