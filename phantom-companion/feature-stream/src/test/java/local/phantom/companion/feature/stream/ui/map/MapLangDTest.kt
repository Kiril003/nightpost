package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.dossier.DossierObjectFacts
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier
import local.phantom.companion.feature.stream.ui.map.dossier.entityRefOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangDTest {

    private fun dossier(tags: Map<String, String>): PlaceDossier {
        val entity = entityRefOf(tags = tags)
        return PlaceDossier(
            target = DossierTarget(lat = 50.45, lon = 30.52),
            title = entity.name ?: "?",
            kindLabel = null,
            category = entity.category,
            entity = entity,
            address = null,
            tags = tags,
            openStatus = null,
            rawHours = null,
            building = null,
            reference = null,
            transitLines = emptyList(),
            similar = emptyList(),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun `labels speak English under en locale`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("Diesel"), DossierObjectFacts.fuelKinds(mapOf("fuel:diesel" to "yes")))
        assertEquals(listOf("Takeaway"), DossierObjectFacts.dining(mapOf("takeaway" to "yes")))
        assertEquals(listOf("Defibrillator"), dossier(mapOf("emergency" to "defibrillator")).quickChips())
    }

    @Test
    @Config(qualifiers = "uk")
    fun `labels speak Ukrainian under uk locale`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("дизель"), DossierObjectFacts.fuelKinds(mapOf("fuel:diesel" to "yes")))
        assertEquals(listOf("з собою"), DossierObjectFacts.dining(mapOf("takeaway" to "yes")))
        assertEquals(listOf("дефібрилятор"), dossier(mapOf("emergency" to "defibrillator")).quickChips())
    }
}
