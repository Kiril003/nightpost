package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Порожній аркуш мусить казати ЧОМУ.
 *
 * Репозиторій розрізняв «є де шукати, цього тут немає» і «шукати нема де» ще
 * від 06.09, але до ока доїжджав лише порожній список: людині з невстановленим
 * пакетом країни радили «спробуй категорію» — тобто відправляли робити марну
 * роботу, бо порожньо там було не через її слова.
 */
class SearchGapTest {

    @Test
    fun `порожній довідник — це пакет, а не запит`() {
        assertEquals(
            SearchGap.NO_PACK,
            searchGapOf(PoiRepository.OfflineSearch.NowhereToLook),
        )
    }

    @Test
    fun `повний довідник без цього місця — це запит, а не пакет`() {
        assertEquals(
            SearchGap.NOT_IN_CORPUS,
            searchGapOf(PoiRepository.OfflineSearch.NothingHere(corpusSize = 496_806)),
        )
    }

    @Test
    fun `знахідка причини не має`() {
        val hit = PoiHit(
            id = 1,
            lat = 50.45,
            lon = 30.52,
            name = "Хрещатик",
            category = "amenity",
            subcategory = "cafe",
            distanceMeters = 111.0,
        )
        assertNull(searchGapOf(PoiRepository.OfflineSearch.Hits(listOf(hit))))
    }
}
