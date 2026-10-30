package local.phantom.companion.privacy

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЧОТИРИ ВІДПОВІДІ ОДНОГО ДІАЛОГУ.
 *
 * 12.09 три тестувальники: «падает полностью при выборе дать доступ к гпс
 * только при использовании приложения». Відтворено на emulator-5554 —
 * «Approximate» + «While using the app». FINE відмовлено, COARSE дано; далі
 * застосунок питав FINE знову, ОС відповідала миттєвою відмовою, і за 2,4 с
 * сторожова служба не встигала вийти на передній план — процес гинув.
 *
 * Тобто вада була не в тому, що дозволу нема, а в тому, що ВІДПОВІДЬ «приблизне»
 * не рахували за відповідь.
 */
class LocationGrantTest {

    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @Test
    fun `точне — це PRECISE`() {
        assertEquals(
            LocationGrant.PRECISE,
            LocationGrants.ofResult(mapOf(fine to true, coarse to true)),
        )
    }

    @Test
    fun `лише під час використання з приблизним — це APPROXIMATE, не відмова`() {
        val grant = LocationGrants.ofResult(mapOf(fine to false, coarse to true))
        assertEquals(LocationGrant.APPROXIMATE, grant)
        assertTrue("приблизне місце теж веде до укриття", LocationGrants.usable(grant))
    }

    @Test
    fun `лише цього разу приходить тим самим виміром, що й постійний дозвіл`() {
        assertEquals(
            LocationGrants.ofResult(mapOf(fine to true, coarse to true)),
            LocationGrants.of(fineHeld = true, coarseHeld = true),
        )
    }

    @Test
    fun `відмова — це NONE і нічим не користуємось`() {
        val grant = LocationGrants.ofResult(mapOf(fine to false, coarse to false))
        assertEquals(LocationGrant.NONE, grant)
        assertFalse(LocationGrants.usable(grant))
    }

    @Test
    fun `приблизне рахується за відповідь`() {
        assertTrue(LocationGrants.answered(mapOf(fine to false, coarse to true)))
        assertTrue(LocationGrants.answered(mapOf(fine to false, coarse to false)))
        assertFalse(LocationGrants.answered(mapOf(Manifest.permission.RECORD_AUDIO to true)))
    }

    @Test
    fun `після будь-якої відповіді діалог більше не показуємо`() {
        assertFalse(
            "повторний запит після «залишити приблизне» — невидимий цикл",
            LocationGrants.shouldAsk(LocationGrant.NONE, answeredBefore = true),
        )
        assertFalse(LocationGrants.shouldAsk(LocationGrant.APPROXIMATE, answeredBefore = false))
        assertFalse(LocationGrants.shouldAsk(LocationGrant.PRECISE, answeredBefore = false))
        assertTrue(LocationGrants.shouldAsk(LocationGrant.NONE, answeredBefore = false))
    }
}
