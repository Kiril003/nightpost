package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Підлога підписів: площини й лінії — під назвами ґрунту, знаки — над.
 *
 * Дві половини. Чиста — план перенесення (без движка). Сторож досяжності —
 * читає MapGroundContributions.kt файлом: підлога, яку ніхто не кличе, — це
 * TLS-слухач, якого ніхто не кликав два дні (написане ≠ підʼєднане).
 * GATE LAW: сторож читає джерело, повз up-to-date Gradle — на валі з `--rerun`.
 */
class MapLabelFloorTest {

    private fun area(id: String) = LayerSlot(id, LayerKind.AREA_OR_LINE)
    private fun mark(id: String, labelled: Boolean = false) = LayerSlot(id, LayerKind.MARK, labelled)

    private val obsidian = listOf(
        LayerSlot("obsidian-ground", LayerKind.OTHER),
        area("obsidian-building"),
        area("obsidian-road-primary"),
        mark("obsidian-label-housenumber", labelled = true),
        mark("obsidian-label-road", labelled = true),
        mark("obsidian-label-city", labelled = true),
    )
    private val basemapIds = obsidian.mapTo(HashSet()) { it.id }

    @Test
    fun `anchor is the first basemap layer that writes text`() {
        assertEquals("obsidian-label-housenumber", labelFloorAnchor(obsidian))
    }

    @Test
    fun `an icon-only symbol in the middle of the roads is not the floor`() {
        // Чужий стиль: стрілка одностороннього руху — символ без тексту серед доріг.
        val liberty = listOf(area("road_minor"), mark("road_oneway"), area("bridge_major"), mark("place_city", labelled = true))
        assertEquals("place_city", labelFloorAnchor(liberty))
    }

    @Test
    fun `a style with no labels has no floor and nothing moves`() {
        val bare = listOf(area("water"), area("road"))
        assertNull(labelFloorAnchor(bare))
        assertEquals(emptyList<String>(), planLabelFloor(bare + area("phantom-route"), "missing", bare.mapTo(HashSet()) { it.id }))
    }

    @Test
    fun `our lines and areas above the labels go under, in their own order`() {
        val stack = obsidian + listOf(
            area("phantom-alert-oblasts-fill"),
            area("phantom-route-casing"),
            area("phantom-route"),
            mark("phantom-route-arrows"),
        )
        assertEquals(
            listOf("phantom-alert-oblasts-fill", "phantom-route-casing", "phantom-route"),
            planLabelFloor(stack, "obsidian-label-housenumber", basemapIds),
        )
    }

    @Test
    fun `marks stay above the labels`() {
        val stack = obsidian + listOf(mark("phantom-poi-layer"), mark("phantom-poi-label-layer", labelled = true), mark("phantom-poi-cluster-layer"))
        assertEquals(emptyList<String>(), planLabelFloor(stack, "obsidian-label-housenumber", basemapIds))
    }

    @Test
    fun `what is already under the floor is not moved again`() {
        // Ідемпотентність: другий прохід після першого — порожній план.
        val sunk = obsidian.toMutableList().apply { add(3, area("phantom-route")) }
        assertEquals(emptyList<String>(), planLabelFloor(sunk, "obsidian-label-housenumber", basemapIds))
    }

    @Test
    fun `the basemap's own areas above its first label are not ours to move`() {
        // Ліберті кладе будівлі після частини підписів — це його порядок, не наш.
        val liberty = listOf(mark("housenumber", labelled = true), area("building"), area("phantom-route"))
        assertEquals(listOf("phantom-route"), planLabelFloor(liberty, "housenumber", setOf("housenumber", "building")))
    }

    // ── Досяжність ────────────────────────────────────────────────────────

    private fun contributionsSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val file = File(
            dir ?: fail("repo root with feature-stream/ not found") as Nothing,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapGroundContributions.kt",
        )
        assertTrue("MapGroundContributions.kt не знайдено — сторож нічого не боронить", file.isFile)
        return file.readText()
    }

    @Test
    fun `the floor is laid around every install hook and every update hook`() {
        val src = contributionsSource()
        val install = Regex("""ContributionInstall \{[^}]*floor\.capture\(s\)[^}]*hook\(s\)[^}]*floor\.sink\(s\)""")
        val update = Regex("""ContributionUpdate \{[\s\S]*?floor\.capture\(s\)[\s\S]*?hook\(s, frame\)[\s\S]*?floor\.sink\(s\)""")
        assertTrue("встановлення йде повз підлогу підписів", install.containsMatchIn(src))
        assertTrue("оновлення йде повз підлогу підписів", update.containsMatchIn(src))
        assertEquals("підлога має бути ОДНА на реєстр", 1, Regex("""LabelFloor\(\)""").findAll(src).count())
    }
}
