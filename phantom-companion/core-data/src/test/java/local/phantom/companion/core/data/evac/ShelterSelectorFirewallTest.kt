package local.phantom.companion.core.data.evac

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один контракт — одне оголошення.
 *
 * Офлайновий і онлайновий шляхи розійшлись саме тому, що «що таке укриття»
 * було написано в чотирьох місцях. Тут стоїть межа: рядок `amenity=shelter`
 * (навіс) не має права зʼявитись у жодному запиті про укриття, а сам контракт
 * живе у [ShelterTruth] і більше ніде.
 */
class ShelterSelectorFirewallTest {

    private val modules = listOf(
        "core-data", "core-nav", "core-net", "core-symbiote", "feature-stream", "feature-car", "app",
    )

    private fun sources(): List<File> = modules
        .map { File("../$it/src/main") }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    /** Лише те, що справді піде запитом — рядкові літерали, не пояснення в KDoc. */
    private val stringLiteral = Regex("\"([^\"\\n]*)\"")

    @Test
    fun `no shelter query asks for a canopy`() {
        val offenders = sources().filter { file ->
            stringLiteral.findAll(file.readText()).any { "amenity=shelter" in it.groupValues[1] }
        }
        assertTrue(
            "`amenity=shelter` — це навіс; сюди він потрапляє лише як запит: " +
                offenders.joinToString { it.path },
            offenders.isEmpty(),
        )
    }

    /**
     * Піч і телефон домовились про те, що є навісом, у ДВОХ місцях: Kotlin і
     * Python. Розійдуться — лічильник у заголовку пакета збреше, а телефон
     * повірить йому й скаже «сховищ поруч немає».
     */
    @Test
    fun `the bake and the app agree on what a canopy is`() {
        val bake = File("../scripts/fetch_kyiv_poi.py")
        if (!bake.exists()) return
        val block = Regex("CANOPY_SHELTER_TYPES = \\{([^}]*)}").find(bake.readText())
        assertTrue("у печі немає CANOPY_SHELTER_TYPES", block != null)
        val fromBake = Regex("\"([a-z_]+)\"").findAll(block!!.groupValues[1])
            .map { it.groupValues[1] }.toSet()
        assertEquals(ShelterTruth.CANOPY_SHELTER_TYPES, fromBake)
    }

    @Test
    fun `the bomb-shelter selector is declared exactly once`() {
        val declarations = sources().filter { file ->
            stringLiteral.findAll(file.readText()).any { it.groupValues[1] == "shelter_type=bomb_shelter" }
        }
        assertTrue(
            "друге оголошення контракту укриттів: " + declarations.joinToString { it.path },
            declarations.size <= 1,
        )
        // Порожньо означало б, що перевірка дивиться не туди.
        assertTrue("контракт укриттів не знайдено в дереві", declarations.size == 1)
        assertTrue("контракт мусить жити у ShelterTruth", declarations.single().name == "ShelterTruth.kt")
    }
}
