package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRegionTest {

    @Test
    fun resolveSkipsWartimeOutsideUa() {
        val wartimeCat = MapDiscovery.all.first { it.ukraineWartime && it.ukKeywords.isNotEmpty() }
        val query = wartimeCat.ukKeywords.first()
        assertNull(MapDiscovery.resolve(query, showsUkraineWartime = false))
        assertEquals(wartimeCat, MapDiscovery.resolve(query, showsUkraineWartime = true))
    }

    @Test
    fun byGroupForFilters() {
        val filtered = MapDiscovery.byGroupFor(showsUkraineWartime = false)
        assertTrue(filtered.flatMap { it.second }.none { it.ukraineWartime })
        val full = MapDiscovery.byGroupFor(showsUkraineWartime = true)
        assertTrue(full.flatMap { it.second }.any { it.ukraineWartime })
    }
}
