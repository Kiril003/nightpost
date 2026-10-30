package local.phantom.companion.self

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Тап по нагадуванню чи візиту PHANTOM мусить відчиняти застосунок.
 *
 * Намір був НЕЯВНИЙ (`phantom://peer/…` + `setPackage`), а debug-збірка ловить
 * лише `phantom-debug://`: на A52 (02.10) тап по «Нагадуванні» не робив нічого
 * — шторка закривалась, застосунок не зʼявлявся. Сповіщення про лист від
 * людини адресує MainActivity явно — тепер і це.
 */
class VisitTapOpensAppTest {

    @Test
    fun `намір сповіщення PHANTOM явний — на MainActivity`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь дерева не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/self/PhantomVisits.kt")
        val open = f.readText().substringAfter("private fun openPhantom(").substringBefore("}.getOrNull()")
        assertTrue("намір PHANTOM знову неявний", "MainActivity::class.java" in open)
        assertFalse("намір PHANTOM шукає фільтр за пакетом", ".setPackage(" in open)
    }
}
