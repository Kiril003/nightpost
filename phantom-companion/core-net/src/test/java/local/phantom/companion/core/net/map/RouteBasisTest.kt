package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBasisTest {

    private fun variant(
        basis: String = "",
        posted: Double = 0.0,
        shoulder: Double = 0.0,
        fords: Int = 0,
    ) = RouteVariant(
        points = listOf(50.0 to 30.0, 50.1 to 30.1),
        distanceMeters = 5000.0,
        durationSeconds = 3600.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = RouteProfile.FOOT,
        speedBasis = basis,
        postedShare = posted,
        trunkShoulderMeters = shoulder,
        fordCrossings = fords,
    )

    /**
     * БРІД — сторож формулювання, не наявності рядка.
     *
     * Рівень води в OSM не записаний і не може бути: він міняється за годину
     * після дощу. Тому кінець рядка — «проїзність невідома», і жодне впевнене
     * слово («проїзний», «прохідний», «обережно, глибина») сюди не сміє
     * потрапити. Ціна помилки — людське життя; ту саму межу проєкт уже провів
     * щодо льодових переправ.
     */
    @Test
    fun `the ford wording says what is marked, never what will happen`() {
        val line = RouteBasis.fordLine(1)!!
        assertTrue(line, line.contains("позначено"))
        assertTrue(line, line.contains("проїзність невідома"))
        for (forbidden in listOf("проїзн" + "ий", "прохідн", "безпечн", "глибина")) {
            assertFalse("$line обіцяє те, чого дані не несуть: $forbidden",
                line.contains(forbidden))
        }
    }

    @Test
    fun `no ford, no line — and an online answer never claims it looked`() {
        assertEquals(null, RouteBasis.fordLine(0))
        assertEquals(null, RouteBasis.fordLine(-1))
        // OSRM бродів не повертає взагалі, тож типове значення нуль і рядка
        // немає — мовчання чесніше за вигадану впевненість.
        assertFalse(RouteBasis.lines(variant(basis = "CLASS")).any { it.contains("брід") })
    }

    @Test
    fun `more than one ford is counted, not summed into metres`() {
        assertTrue(RouteBasis.fordLine(3)!!.contains("(3)"))
        assertTrue(RouteBasis.lines(variant(basis = "ASSUMED", fords = 2)).any { it.contains("броди") })
    }

    @Test
    fun `an OSRM answer says nothing rather than implying it knew`() {
        assertEquals(emptyList<String>(), RouteBasis.lines(variant()))
        assertEquals(null, RouteBasis.summary(variant()))
    }

    @Test
    fun `posted, class and assumed are three different claims`() {
        assertTrue(RouteBasis.lines(variant(basis = "POSTED", posted = 1.0)).first().contains("за знаками"))
        assertTrue(RouteBasis.lines(variant(basis = "CLASS")).first().contains("за класом дороги"))
        assertTrue(RouteBasis.lines(variant(basis = "ASSUMED")).first().contains("припущення"))
    }

    /** Rounding a real signed stretch down to «0%» reads as «no signs at all». */
    @Test
    fun `a sliver of posted road never renders as zero percent`() {
        val line = RouteBasis.lines(variant(basis = "CLASS", posted = 0.001)).first()
        assertTrue(line, line.contains("1%"))
        assertFalse(line, line.contains("знаків нема"))
    }

    @Test
    fun `a junction crossed is not a highway walked`() {
        assertFalse(RouteBasis.lines(variant(shoulder = 149.0)).contains(RouteBasis.TRUNK_SHOULDER))
        assertTrue(RouteBasis.lines(variant(shoulder = 150.0)).contains(RouteBasis.TRUNK_SHOULDER))
    }

    /**
     * sidewalk is tagged on 216 of 20 907 trunk ways — presence is unknowable,
     * so the copy may never harden into a claim.
     */
    @Test
    fun `the shoulder wording stays unknowable`() {
        assertEquals(
            "частина шляху — узбіччям магістралі, чи є узбіччя — невідомо",
            RouteBasis.TRUNK_SHOULDER,
        )
    }
}
