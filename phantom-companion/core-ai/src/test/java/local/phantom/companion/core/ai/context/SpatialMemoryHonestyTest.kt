package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A measurement may not be replaced by a constant.
 *
 * `NearbyMemorySummary` carries exactly one field, `distanceMeters`, and the
 * AI is handed it as fact: the prompt preamble becomes
 * `[SPATIAL_MEMORY: count=N closest=Xm]`, which the model reads as a metre
 * reading taken from the world.
 *
 * Until 30.08 the only construction site in the tree —
 * `StreamVmFactory`'s spatial provider — built every single summary with
 * `distanceMeters = 0.0`, a placeholder awaiting MAP-AI-7's haversine. It hurt
 * nobody only because `SpatialMemoryStore` has no writer anywhere, so the list
 * was always empty and the formatter always said «none». That is a fuse, not a
 * safeguard: give the store a writer for any reason, and the AI starts telling
 * the operator his nearest memory is zero metres away — for every pin, on the
 * whole earth — with no code change in sight of the person who wired it.
 *
 * This is the house's characteristic defect wearing its least visible face.
 * The fabricated 18°C in AtmosphericRadarTool is the same shape and was caught
 * because someone read the number. A zero cannot be read that way: it looks
 * like data.
 *
 * So the rule this pins is narrow and absolute: **nothing may construct a
 * NearbyMemorySummary from a literal distance.** A real one is computed, or
 * the provider is null and the block is omitted.
 *
 * ── WHAT THIS TEST CANNOT SEE ──────────────────────────────────────────
 * Written down because a firewall silently believed to be total is worse than
 * none — it grants a confidence it has not earned.
 *
 *  • **A constant that is not a literal.** `val d = 0.0` on one line and
 *    `distanceMeters = d` on the next reads as a variable and passes.
 *  • **A computed lie.** `abs(x - x)`, or a haversine fed the same point
 *    twice, is arithmetic and passes. This checks the *shape* of the value,
 *    never its truth.
 *  • **Other constructors of the same disease.** It knows one type. Every
 *    other summary handed to the AI as measurement is unguarded.
 *  • **Sources outside the modules walked below**, including anything
 *    generated at build time.
 *  • **Whether the field is honest at runtime.** Only a device answers that.
 *
 * It reads sibling modules' sources as raw file I/O, which Gradle's
 * up-to-date check cannot see — run it with `--rerun` or a stale green will
 * hide a reintroduction.
 */
class SpatialMemoryHonestyTest {

    /**
     * Walk up from this module's working directory to the repository root —
     * the directory holding `settings.gradle.kts`. Bounded, because a wrong
     * loop here would climb to `/` and scan the whole disk.
     */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError(
            "repository root not found within six levels of ${System.getProperty("user.dir")} — " +
                "this firewall cannot see the sources it is meant to guard, and a green run " +
                "would mean nothing. Fix the lookup rather than deleting the test."
        )
    }

    private val modules = listOf("app", "feature-stream", "core-ai", "core-symbiote")

    @Test
    fun `no NearbyMemorySummary is built from a literal distance`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()

        modules.forEach { module ->
            val src = File(root, "$module/src/main")
            if (!src.isDirectory) return@forEach
            src.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    if (!text.contains("NearbyMemorySummary")) return@forEach
                    text.lineSequence().forEachIndexed { idx, line ->
                        // A literal assignment to the one field the AI reads as
                        // a metre reading: `distanceMeters = <number>`.
                        val literal = Regex("""distanceMeters\s*=\s*-?\d+(\.\d+)?[fF]?\s*,?\s*$""")
                        if (literal.containsMatchIn(line)) {
                            offenders += "${file.relativeTo(root)}:${idx + 1}  ${line.trim()}"
                        }
                    }
                }
        }

        assertEquals(
            "a NearbyMemterSummary distance must be computed, never written in. " +
                "The AI is handed this number as a metre reading and repeats it to the " +
                "operator as fact; a placeholder here is a fabrication with a delayed " +
                "fuse — harmless while the store is empty, false the moment it is not. " +
                "Either compute the distance or pass no provider at all, so the prompt " +
                "block is omitted. Offenders:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The firewall above is a regex over sources. If it ever stops finding the
     * sources — a module renamed, the layout changed — it would pass by
     * scanning nothing at all, which is the failure mode that makes a guard
     * worse than useless. So: prove it is actually looking at something.
     */
    @Test
    fun `the firewall is reading real sources and not an empty set`() {
        val root = repoRoot()
        val kotlinFiles = modules
            .map { File(root, "$it/src/main") }
            .filter { it.isDirectory }
            .sumOf { dir -> dir.walkTopDown().count { it.isFile && it.extension == "kt" } }

        assertTrue(
            "the guarded modules yielded $kotlinFiles Kotlin files — far too few to be the " +
                "real tree. The firewall would then pass by looking at nothing, which is " +
                "the one outcome worse than failing.",
            kotlinFiles > 100,
        )
    }
}
