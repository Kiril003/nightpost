package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.GuidanceProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APPLE-LOCK — the pin's road lock. While a drive is live and the raw fix
 * agrees with the corridor, the DISPLAYED fix is the route snap with the
 * route's tangent as its bearing; every honest disagreement releases it.
 */
class GuidanceLockedFixTest {

    private fun fix(bearing: Float? = 90f, speedMps: Float? = 12f) = LocationFix(
        lat = 50.4500,
        lon = 30.5230,
        bearing = bearing,
        accuracyM = 14f,
        speedMps = speedMps,
        grade = FixGrade.NAVIGATION,
    )

    /** Їде вздовж коридору (88°) — звичайний випадок. */
    private val raw = fix()

    private fun progress(
        snappedLat: Double = 50.45005,
        snappedLon: Double = 30.52310,
        bearingDeg: Double = 88.0,
        offRoute: Boolean = false,
    ) = GuidanceProgress(
        snappedLat = snappedLat,
        snappedLon = snappedLon,
        bearingDeg = bearingDeg,
        maneuverType = "turn",
        maneuverModifier = "right",
        roadName = "Хрещатик",
        distanceToManeuverM = 180.0,
        remainingDistanceM = 900.0,
        remainingDurationS = 120.0,
        speedKmh = 43,
        limitKmh = 50,
        offRoute = offRoute,
    )

    @Test
    fun `on-route drive locks the pin to the snap and keeps the operator's own heading`() {
        val locked = guidanceLockedFix(raw, guiding = true, blind = false, progress = progress())
        assertNotNull(locked)
        assertEquals(50.45005, locked!!.lat, 1e-9)
        assertEquals(30.52310, locked.lon, 1e-9)
        // Позиція зшита (пін на дорозі), а напрямок — ВЛАСНИЙ, не дороги.
        // Скарга власника: «показує, що я їду в той бік, хоча це не так».
        assertEquals(90f, locked.bearing)
        // Honesty rides along: doubt, speed and provenance are the raw fix's.
        assertEquals(raw.accuracyM, locked.accuracyM)
        assertEquals(raw.speedMps, locked.speedMps)
        assertEquals(raw.grade, locked.grade)
    }

    @Test
    fun `no drive means no lock`() {
        assertNull(guidanceLockedFix(raw, guiding = false, blind = false, progress = progress()))
    }

    @Test
    fun `blind engine releases the lock`() {
        assertNull(guidanceLockedFix(raw, guiding = true, blind = true, progress = progress()))
    }

    @Test
    fun `off-route verdict releases the lock`() {
        assertNull(
            guidanceLockedFix(raw, guiding = true, blind = false, progress = progress(offRoute = true)),
        )
    }

    @Test
    fun `raw far from a held snap releases the lock`() {
        // ~550 m of longitude away: a held progress while the estimate runs
        // off must not glue the pin to a corridor the operator left.
        val far = progress(snappedLat = raw.lat, snappedLon = raw.lon + 0.0078)
        assertNull(guidanceLockedFix(raw, guiding = true, blind = false, progress = far))
    }

    @Test
    fun `no progress yet means no lock`() {
        assertNull(guidanceLockedFix(raw, guiding = true, blind = false, progress = null))
    }

    @Test
    fun `driving across the corridor releases the lock, however close`() {
        // Пін за 6 м від коридору — ближче нікуди. Але рух іде під 92° до
        // дороги: це сусідня вулиця, а не похибка зшивання.
        val across = guidanceLockedFix(
            fix(bearing = 180f),
            guiding = true,
            blind = false,
            progress = progress(),
        )
        assertNull("їзда поперек коридору мусить відпускати замок", across)
    }

    @Test
    fun `a standing operator keeps the corridor bearing, not GPS noise`() {
        // Нерухомий телефон має «напрямок», якого немає: це шум чипа.
        val standing = guidanceLockedFix(
            fix(bearing = 271f, speedMps = 0.2f),
            guiding = true,
            blind = false,
            progress = progress(),
        )
        assertNotNull(standing)
        assertEquals(88f, standing!!.bearing)
    }

    @Test
    fun `a fix without a bearing falls back to the corridor`() {
        val noBearing = guidanceLockedFix(
            fix(bearing = null),
            guiding = true,
            blind = false,
            progress = progress(),
        )
        assertNotNull(noBearing)
        assertEquals(88f, noBearing!!.bearing)
    }

    @Test
    fun `hysteresis - a 50 m gap holds an engaged lock but does not engage a new one`() {
        // ~50 m of longitude between raw and snap: inside RELEASE (60),
        // outside ENGAGE (45).
        val edge = progress(snappedLat = raw.lat, snappedLon = raw.lon + 0.00071)
        assertNull(
            "a new lock engaged past the engage threshold",
            guidanceLockedFix(raw, guiding = true, blind = false, progress = edge, wasLocked = false),
        )
        assertNotNull(
            "an engaged lock flapped open inside the release threshold",
            guidanceLockedFix(raw, guiding = true, blind = false, progress = edge, wasLocked = true),
        )
    }
}
