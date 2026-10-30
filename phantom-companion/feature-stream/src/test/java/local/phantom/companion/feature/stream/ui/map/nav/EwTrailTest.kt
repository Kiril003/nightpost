package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.ui.map.HEADING_FLOOR_MPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LEVIATHAN-2 — the trail's promises: crumbs only for real movement, sky in two words. */
class EwTrailTest {

    /** Один крок пішки: 1.3 м/с — реальний темп, добра точність. */
    private fun EwTrail.walk(lat: Double, lon: Double, t: Long, hostileDead: Boolean = false, trust: LocationTrust? = LocationTrust.TRUSTED) =
        onFix(lat, lon, dead = hostileDead, trust = trust, nowMs = t, speedMps = 1.3f, accuracyM = 5f)

    @Test
    fun `standing still leaves no trail`() {
        val trail = EwTrail()
        var t = 0L
        repeat(30) {
            t += 1000
            trail.onFix(50.45, 30.52, false, LocationTrust.TRUSTED, t, speedMps = 0f, accuracyM = 5f)
        }
        assertEquals(EwTrail.EMPTY, trail.geoJson.value)
    }

    /**
     * Знімок 17:14: телефон лежав нерухомо, ±85 м шуму GNSS штовхали позицію
     * >2 м щосекунди, і слід сипався на місці — «подряпини» через пів екрана.
     * Крок є, швидкості немає: крихти бути не сміє.
     */
    @Test
    fun `gnss noise at a standstill is not a path`() {
        val trail = EwTrail()
        var t = 0L
        repeat(40) { i ->
            t += 1000
            // ~11 м стрибка туди-сюди, як під ±85 м.
            val jitter = if (i % 2 == 0) 0.0001 else -0.0001
            trail.onFix(50.45 + jitter, 30.52, false, LocationTrust.DEGRADED, t, speedMps = 0.2f, accuracyM = 85f)
        }
        assertEquals(EwTrail.EMPTY, trail.geoJson.value)
    }

    @Test
    fun `an unknown speed is not proof of movement`() {
        val trail = EwTrail()
        var t = 0L
        repeat(10) { i ->
            t += 2000
            trail.onFix(50.45 + i * 0.0005, 30.52, false, LocationTrust.TRUSTED, t, speedMps = null, accuracyM = 5f)
        }
        assertEquals(EwTrail.EMPTY, trail.geoJson.value)
    }

    /** Поріг руху тут і поріг курсу шеврона — одне й те саме число. */
    @Test
    fun `the movement floor is the heading floor`() {
        assertEquals(HEADING_FLOOR_MPS, EwTrail.MOVING_FLOOR_MPS)
    }

    @Test
    fun `movement paints segments under a live sky`() {
        val trail = EwTrail()
        var t = 0L
        repeat(10) { i ->
            t += 2000
            trail.walk(50.45 + i * 0.0001, 30.52, t)
        }
        val json = trail.geoJson.value
        assertTrue(json.contains("\"sky\":\"${EwTrail.SKY_CLEAR}\""))
        assertTrue(json.contains("LineString"))
        assertFalse("вирок більше не фарбує слід", json.contains("#"))
    }

    @Test
    fun `a jammed stretch is hostile and a degraded one is not`() {
        val trail = EwTrail()
        var t = 0L
        repeat(4) { i ->
            t += 2000
            trail.walk(50.45 + i * 0.0001, 30.52, t, trust = LocationTrust.DEGRADED)
        }
        repeat(4) { i ->
            t += 2000
            trail.walk(50.4504 + i * 0.0001, 30.52, t, hostileDead = true, trust = LocationTrust.JAMMED)
        }
        val json = trail.geoJson.value
        assertTrue(json.contains("\"sky\":\"${EwTrail.SKY_CLEAR}\""))
        assertTrue(json.contains("\"sky\":\"${EwTrail.SKY_HOSTILE}\""))
    }

    /**
     * Слабка точність — стан приладу, а не захід у зону глушіння. Якби
     * DEGRADED фарбував корал, ним була б пофарбована більшість реальних
     * маршрутів, і корал перестав би щось означати.
     */
    @Test
    fun `only a hostile sky is hostile`() {
        assertFalse(EwTrail.hostileSky(dead = false, trust = LocationTrust.DEGRADED))
        assertFalse(EwTrail.hostileSky(dead = false, trust = LocationTrust.TRUSTED))
        assertFalse(EwTrail.hostileSky(dead = false, trust = null))
        assertTrue(EwTrail.hostileSky(dead = true, trust = LocationTrust.TRUSTED))
        assertTrue(EwTrail.hostileSky(dead = false, trust = LocationTrust.JAMMED))
        assertTrue(EwTrail.hostileSky(dead = false, trust = LocationTrust.SPOOFED))
    }

    /**
     * Щільність крихт не має бути дрібнішою за власну похибку. Та сама
     * прогулянка на 222 м: під ±5 м слід лягає кожні 11 м, під ±85 м —
     * приблизно кожні 30 м, тобто не претендує на точність, якої немає.
     */
    @Test
    fun `crumb spacing scales with the fix's own error`() {
        fun walkSegments(accuracyM: Float): Int {
            val trail = EwTrail()
            var t = 0L
            repeat(20) { i ->
                t += 2000
                trail.onFix(50.45 + i * 0.0001, 30.52, false, LocationTrust.DEGRADED, t, 1.3f, accuracyM)
            }
            return trail.geoJson.value.split("\"type\":\"Feature\"").size - 1
        }
        val fine = walkSegments(5f)
        val coarse = walkSegments(85f)
        assertEquals("під ±5 м крок віддає поріг 2 м — крихта на кожен фікс", 19, fine)
        assertTrue("під ±85 м слід має рідшати, а він $coarse проти $fine", coarse < fine / 2)
        assertTrue("але не зникати: $coarse", coarse >= 4)
    }

    @Test
    fun `the buffer caps and sheds its oldest crumbs`() {
        val trail = EwTrail(maxPoints = 10)
        var t = 0L
        repeat(50) { i ->
            t += 2000
            trail.walk(50.45 + i * 0.0001, 30.52, t)
        }
        // 10 points → 9 segments → 9 features.
        val features = trail.geoJson.value.split("\"type\":\"Feature\"").size - 1
        assertEquals(9, features)
        // The very first crumb (lat exactly 50.45, serialized as [lon,lat]) is gone.
        assertFalse(trail.geoJson.value.contains(",50.45]"))
    }

    @Test
    fun `a teleport jump breaks the trail instead of drawing a line across the map`() {
        // Field report P0-C: a ~100 m reacquisition snap was drawn as a
        // confident straight line. Two short walked segments with a big jump
        // between them must yield TWO features, not three — the jump is not
        // connected.
        val trail = EwTrail()
        var t = 0L
        // A → B: ~22 m, a real step.
        trail.walk(50.4500, 30.52, t.also { t += 2000 })
        trail.walk(50.4502, 30.52, t.also { t += 2000 })
        // B → C: ~200 m — a teleport. Must NOT be connected.
        trail.walk(50.4520, 30.52, t.also { t += 2000 }, trust = LocationTrust.DEGRADED)
        // C → D: ~22 m, a real step again.
        trail.walk(50.4522, 30.52, t.also { t += 2000 }, trust = LocationTrust.DEGRADED)

        val json = trail.geoJson.value
        val features = json.split("\"type\":\"Feature\"").size - 1
        assertEquals("the teleport segment must be broken, leaving two walked ones", 2, features)
    }

    /** Слід — нитка позаду; він не має права сперечатися з точкою за увагу. */
    @Test
    fun `the trail never shouts`() {
        val trail = EwTrail()
        var t = 0L
        repeat(12) { i ->
            t += 2000
            trail.walk(50.45 + i * 0.0001, 30.52, t)
        }
        val alphas = Regex("\"alpha\":([0-9.]+)").findAll(trail.geoJson.value)
            .map { it.groupValues[1].toFloat() }.toList()
        assertTrue("слід не намалювався", alphas.isNotEmpty())
        assertTrue("найгучніша крихта ${alphas.max()} — це вже другий сюжет", alphas.max() <= 0.50f)
    }

    @Test
    fun `spoofed color outranks everything`() {
        assertEquals("#EF4444", EwTrail.colorFor(dead = true, trust = LocationTrust.SPOOFED))
        assertEquals("#F87171", EwTrail.colorFor(dead = true, trust = LocationTrust.TRUSTED))
        assertEquals("#64748B", EwTrail.colorFor(dead = false, trust = null))
    }
}
