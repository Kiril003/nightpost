package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRiskTest {

    private fun report(
        coverage: Double = 0.8,
        cameras: Int = 0,
        litFraction: Double? = null,
        groundShare: Double = 0.0,
        classShares: List<Pair<String, Double>> = emptyList(),
    ) = RouteIntelReport(
        classShares = classShares,
        surfaceShares = if (groundShare > 0) listOf("ґрунт/гравій" to groundShare) else emptyList(),
        litFraction = litFraction,
        maxspeedMax = null,
        coverage = coverage,
        cameras = (1..cameras).map { CameraSighting(50.0, 30.0, null) },
        elevations = null,
        ascentMeters = null,
        descentMeters = null,
    )

    @Test
    fun `no basis at all is null, not zero`() {
        assertNull(assessRouteRisk(intel = null, alertCrossings = 0, profile = RouteProfile.CAR))
        // Under-covered corridor = no corridor, still no basis.
        assertNull(assessRouteRisk(report(coverage = 0.1), 0, RouteProfile.CAR))
    }

    @Test
    fun `clean covered corridor scores zero with empty factors`() {
        val r = assessRouteRisk(report(litFraction = 1.0), 0, RouteProfile.CAR)!!
        assertEquals(0, r.score)
        assertTrue(r.factors.isEmpty())
    }

    @Test
    fun `alert crossing dominates and stacks with cap`() {
        val one = assessRouteRisk(null, 1, RouteProfile.CAR)!!
        assertEquals(30, one.score)
        val three = assessRouteRisk(null, 3, RouteProfile.CAR)!!
        assertEquals(45, three.score) // 30 + 15 + 15 capped at 45
        assertTrue(three.factors.single().first.contains("×3"))
    }

    @Test
    fun `cameras count for drivers, not for walkers`() {
        val car = assessRouteRisk(report(cameras = 3), 0, RouteProfile.CAR)!!
        assertEquals(12, car.score)
        val foot = assessRouteRisk(report(cameras = 3), 0, RouteProfile.FOOT)!!
        assertEquals(0, foot.score)
    }

    @Test
    fun `unlit and ground contribute proportionally with labels`() {
        val r = assessRouteRisk(
            report(litFraction = 0.2, groundShare = 0.5),
            0,
            RouteProfile.BIKE,
        )!!
        // unlit 0.8*15=12 + ground 0.5*12=6
        assertEquals(18, r.score)
        assertTrue(r.factors.any { it.first.contains("неосвітлено 80%") })
        assertTrue(r.factors.any { it.first.contains("ґрунт 50%") })
    }

    @Test
    fun `barriers contribute to risk and note private access`() {
        val r = assessRouteRisk(
            report().copy(
                barriers = listOf(
                    BarrierSighting(50.0, 30.0, "lift_gate", "private"),
                    BarrierSighting(50.0, 30.0, "gate", null),
                )
            ),
            0,
            RouteProfile.CAR,
        )!!
        assertTrue(r.score > 0)
        assertTrue(r.factors.any { it.first.contains("шлагбауми/ворота: 2 (1 приват.)") })
    }

    @Test
    fun `every point is explained by a factor`() {
        val r = assessRouteRisk(report(cameras = 2, litFraction = 0.5), 2, RouteProfile.CAR)!!
        assertEquals(r.score, r.factors.sumOf { it.second })
    }

    @Test
    fun `tiers split at 25 and 55`() {
        assertEquals(RiskTier.LOW, riskTier(24))
        assertEquals(RiskTier.ELEVATED, riskTier(25))
        assertEquals(RiskTier.ELEVATED, riskTier(54))
        assertEquals(RiskTier.HIGH, riskTier(55))
    }

    // ── ghost route ──

    private fun variant(durationS: Double) = RouteVariant(
        points = listOf(50.0 to 30.0, 50.01 to 30.0),
        distanceMeters = 1000.0,
        durationSeconds = durationS,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    @Test
    fun `ghost picks fewest cameras`() {
        val idx = ghostVariantIndex(
            listOf(
                variant(600.0) to report(cameras = 3),
                variant(700.0) to report(cameras = 0),
            ),
        )
        assertEquals(1, idx)
    }

    @Test
    fun `ghost refuses when any corridor is blind`() {
        assertNull(
            ghostVariantIndex(
                listOf(
                    variant(600.0) to report(cameras = 3),
                    variant(700.0) to null,
                ),
            ),
        )
        assertNull(
            ghostVariantIndex(
                listOf(
                    variant(600.0) to report(cameras = 3),
                    variant(700.0) to report(coverage = 0.1),
                ),
            ),
        )
    }

    @Test
    fun `ghost tie breaks on duration and needs two variants`() {
        val idx = ghostVariantIndex(
            listOf(
                variant(700.0) to report(cameras = 0),
                variant(600.0) to report(cameras = 0),
            ),
        )
        assertEquals(1, idx)
        assertNull(ghostVariantIndex(listOf(variant(600.0) to report())))
    }

    @Test
    fun `ghost penalizes magistral share`() {
        val idx = ghostVariantIndex(
            listOf(
                variant(600.0) to report(classShares = listOf("магістраль" to 0.9)),
                variant(650.0) to report(classShares = listOf("житлова" to 0.9)),
            ),
        )
        assertEquals(1, idx)
    }
}
