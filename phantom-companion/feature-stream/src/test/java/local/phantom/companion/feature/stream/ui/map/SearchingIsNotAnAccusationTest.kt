package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.nav.CoastBadge
import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.ui.map.atlas.TrustClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Небо ще не відповіло» мусить лишатись відсутністю відповіді на всьому шляху
 * до екрана. Якщо десь по дорозі воно набуде ваги тривоги — смуга повернеться
 * тим самим виглядом, лише іншим шляхом, і власник знову побачить крик там, де
 * нічого не сталось.
 */
class SearchingIsNotAnAccusationTest {

    @Test
    fun `пошук неба — це відсутність позиції, не поганий сигнал і не напад`() {
        assertEquals(
            TrustClass.BLIND,
            trustClassOf(LocationTrust.SEARCHING, CoastBadge.LIVE),
        )
    }

    @Test
    fun `пошук неба не важить як погіршення`() {
        assertEquals(0, trustSeverity(LocationTrust.SEARCHING))
        assertFalse(
            "поява пошуку неба не є новиною, що вимагає слова",
            verdictWorsens(LocationTrust.TRUSTED, LocationTrust.SEARCHING),
        )
        assertTrue(
            "а от справжнє глушіння після пошуку — новина",
            verdictWorsens(LocationTrust.SEARCHING, LocationTrust.JAMMED),
        )
    }
}
