package local.phantom.companion.data.backpack

import androidx.work.NetworkType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Рюкзак мусить ДОЛІТАТИ: від тривоги в моїй області — до збірки; від ночі на
 * Wi-Fi і зарядці — до повного пакета; від сховку Наміру — до маяка.
 */
class BackpackReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь не знайдено") as Nothing
    }

    private fun source(path: String): String {
        val f = File(repoRoot(), "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено", f.isFile)
        return f.readText()
    }

    @Test fun `нічний працівник — лише Wi-Fi без ліміту, зарядка й не низька батарея`() {
        val c = BackpackWorker.constraints()
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
    }

    @Test fun `тривога в моїй області збирає рюкзак, ніч — повний пакет`() {
        val alert = source("service/AirAlertService.kt")
        assertTrue("тривога не будить рюкзак", alert.contains("container.backpack.pack(local.phantom.companion.core.net.backpack.Backpack.Trigger.ALERT)"))
        assertTrue("рюкзак будиться не лише на початок тривоги", alert.contains("AirAlertNoticeKind.RAISED"))
        assertTrue("ніч не заплановано", source("data/ContainerBoot.kt").contains("BackpackWorker.schedule(applicationContext)"))
        assertTrue("контейнер не тримає рюкзак", source("data/AppContainer.kt").contains("BackpackRunner(this)"))
    }

    @Test fun `рюкзак бере мапу тим самим завантажувачем і маяк із сайту`() {
        val r = source("data/backpack/BackpackRunner.kt")
        assertTrue(r.contains("container.offlineRegions.createRegion("))
        assertTrue("після тривоги будь-яка мережа, уночі лише без ліміту", r.contains("allowMetered = trigger != Backpack.Trigger.NIGHT"))
        assertTrue("маяк не з сайту", r.contains("\${BuildConfig.LICENSE_BASE_URL}/api/alerts/snapshot"))
        assertTrue("маяк приймається без перевірки", r.contains("siren.offer(raw)"))
        assertTrue("сховок не забирається", r.contains("container.mesh.pollRelayNow()"))
        assertTrue("маяк із відповіді Наміру не доходить до сховку", source("data/intent/IntentWiring.kt").contains("container.backpack.siren.offer(raw)"))
    }

    @Test fun `«Профіль › Офлайн-мапи» читає рюкзак через міст`() {
        assertTrue(source("ui/messenger/WebMessengerBridge.kt").contains("backpackRoute.handle(method, seg)"))
        assertTrue(source("ui/messenger/WebMessengerBackpack.kt").contains("container.backpack.view()"))
    }
}
