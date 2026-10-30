package local.phantom.companion.feature.stream.ui.map.dossier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** P1 — кеш досьє: LRU, TTL і відмова зберігати недозібране. */
class DossierCacheTest {

    private var now = 1_000_000L

    private fun target(lat: Double = 50.50717, lon: Double = 30.41257, name: String? = "Бібліотека") =
        DossierTarget(lat = lat, lon = lon, nameHint = name)

    private fun complete(target: DossierTarget) =
        instantDossier(target).copy(pending = emptySet())

    @Test
    fun `повне досьє повертається миттєво`() {
        val cache = DossierCache(clock = { now })
        val t = target()
        cache.put(t, complete(t))
        assertNotNull(cache.get(t))
    }

    @Test
    fun `недозібране досьє не кешується`() {
        val cache = DossierCache(clock = { now })
        val t = target()
        // Знімок T+0 — усі слоти ще в дорозі.
        cache.put(t, instantDossier(t))
        assertNull(cache.get(t))
    }

    @Test
    fun `протухле досьє викидається - години роботи не вічні`() {
        val cache = DossierCache(ttlMs = 1_000L, clock = { now })
        val t = target()
        cache.put(t, complete(t))
        now += 1_001L
        assertNull(cache.get(t))
        assertEquals(0, cache.size())
    }

    @Test
    fun `найдавніше використане витісняється першим`() {
        val cache = DossierCache(capacity = 2, clock = { now })
        val a = target(name = "A")
        val b = target(name = "B")
        val c = target(name = "C")
        cache.put(a, complete(a))
        cache.put(b, complete(b))
        // Дотик до A робить найдавнішим B.
        assertNotNull(cache.get(a))
        cache.put(c, complete(c))
        assertNotNull(cache.get(a))
        assertNull(cache.get(b))
        assertNotNull(cache.get(c))
    }

    @Test
    fun `те саме місце з мікроскопічно різних тапів дає один ключ`() {
        // Оператор відкриває бібліотеку з мапи і зі збережених — координати
        // відрізняються на сантиметри, місце те саме.
        assertEquals(
            dossierKey(target(lat = 50.507170, lon = 30.412570)),
            dossierKey(target(lat = 50.5071702, lon = 30.4125703)),
        )
    }

    @Test
    fun `два різні POI за метр один від одного не зливаються`() {
        assertEquals(false, dossierKey(target(name = "Кафе")) == dossierKey(target(name = "Аптека")))
    }
}
