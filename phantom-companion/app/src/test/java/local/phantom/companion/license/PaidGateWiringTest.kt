package local.phantom.companion.license

import java.io.File
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.core.license.LicenseModel
import local.phantom.companion.core.license.LicenseTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ліцензія без жодного місця, яке її питає, — це рядок на екрані, а не товар.
 *
 * SafetyFloorFirewallTest боронить нижню межу: безпеку питати не можна. Ця
 * перевірка боронить верхню: кожне платне право мусить мати справжні ворота в
 * коді, і саме там, де в цю здатність заходять.
 */
class PaidGateWiringTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val sourceRoots = listOf(
        "app/src/main", "core-design/src/main", "core-voice/src/main",
        "feature-stream/src/main",
    )

    private fun sources(): List<File> = sourceRoots
        .map { File(repoRoot(), it) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun askSitesFor(feature: LicenseFeature): List<String> {
        val needle = Regex("""has\s*\(\s*LicenseFeature\.${feature.name}\b""")
        return sources().flatMap { file ->
            file.readLines().withIndex().mapNotNull { (i, line) ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapNotNull null
                if (needle.containsMatchIn(line)) "${file.name}:${i + 1}" else null
            }
        }
    }

    /** Право → файл, у якому в цю здатність заходять. */
    private val entryPoints = mapOf(
        // 04.10: двері «Пристрої → Компʼютер» переїхали зі SettingsRealm у PairEntry.
        LicenseFeature.BridgePair to setOf("PairScreen.kt", "PairEntry.kt"),
        LicenseFeature.MapTerrain3d to setOf("MapCanvas.kt"),
        // VoicePremium вибув з мапи платних воріт 03.09: голос вільний назавжди.
        LicenseFeature.ExportReports to setOf("ReportSurface.kt"),
    )

    @Test
    fun `each paid feature has a real gate at the door of the thing it sells`() {
        val naked = entryPoints.filter { (feature, files) ->
            val sites = askSitesFor(feature).map { it.substringBefore(':') }.toSet()
            !sites.containsAll(files)
        }.map { (feature, files) ->
            "${feature.name} очікувався у ${files.joinToString(", ")}, " +
                "знайдено у ${askSitesFor(feature).ifEmpty { listOf("ніде") }}"
        }

        assertEquals(
            "платне право без воріт — це обіцянка, за яку взяли гроші:\n" +
                naked.joinToString("\n") { "    $it" },
            emptyList<String>(),
            naked,
        )
    }

    @Test
    fun `long-term memory is gated on the read path, not by deleting anything`() {
        // MemoryLongterm читається не в композиції, а в AppContainer, який
        // рахує горизонт згадування для шляху відновлення.
        val sites = askSitesFor(LicenseFeature.MemoryLongterm)
        assertTrue(
            "довготривала памʼять ніде не питає ліцензію",
            sites.any { it.startsWith("AppContainer.kt") },
        )

        val container = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/data/AppContainer.kt",
        ).readText()
        // Ворота на ЧИТАННЯ: складання фактів і прибирання сховища не сміють
        // залежати від ключа, інакше замок нищив би вже прожите.
        val consolidation = container.substringAfter("suspend fun consolidateMemory")
            .substringBefore("\n    // ── DATA-SYSTEMS")
        assertTrue(
            "складання памʼяті поставили в залежність від ключа — це вже видалення, а не ворота",
            !consolidation.contains("LicenseFeature."),
        )
    }

    @Test
    fun `the free forever set is never asked about anywhere`() {
        val free = LicenseModel.grants(LicenseTier.Free)
        val offenders = free.flatMap { feature ->
            askSitesFor(feature).map { "${feature.name} @ $it" }
        }

        assertEquals(
            "ліцензію питають про вільну функцію:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nЧат, мапа, навігація, тривоги, сховище й голос вільні назавжди.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `GnssBlackbox was pulled from the sellable list and stays ungated`() {
        assertEquals(
            "GnssBlackbox знову продають: його запис не має читача й крутиться " +
                "лише поки відкритий екран діагностики",
            emptyList<String>(),
            askSitesFor(LicenseFeature.GnssBlackbox),
        )
    }

    @Test
    fun `the licence screen is not the only place that reads the model`() {
        // Саме та поломка, заради якої ця перевірка існує: has() із нульовою
        // кількістю викликів і список галочок як єдиний покупний товар.
        val gated = listOf(
            LicenseFeature.BridgePair, LicenseFeature.MemoryLongterm,
            // VoicePremium тут немає з 03.09: голос у вільному наборі назавжди,
            // тож його ніде й не питають — перший тест цього класу це доводить.
            LicenseFeature.MapTerrain3d,
            LicenseFeature.ExportReports,
        )
        val outsideLicenceScreen = gated.associateWith { feature ->
            askSitesFor(feature).filterNot { it.startsWith("LicenseScreen.kt") }
        }

        outsideLicenceScreen.forEach { (feature, sites) ->
            assertTrue(
                "${feature.name} питають лише на екрані ліцензії — це малюнок замка, не замок",
                sites.isNotEmpty(),
            )
        }
    }

    @Test
    fun `the scan actually reaches the modules it claims to`() {
        val missing = sourceRoots.filterNot { File(repoRoot(), it).isDirectory }
        assertEquals("перейменований модуль тихо вивів би себе з-під перевірки", emptyList<String>(), missing)
        assertTrue("скан майже порожній — він уже нічого не доводить", sources().size > 300)
    }
}
