package local.phantom.companion.core.license

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafetyFloorFirewallTest {

    private val roots = listOf(
        "app/src/main", "core-design/src/main", "core-geo/src/main",
        "feature-stream/src/main", "feature-onboarding/src/main",
        "feature-precision/src/main", "feature-ambient/src/main",
        "feature-car/src/main", "core-nav/src/main",
    )

    private val floorNames = LicenseModel.SAFETY_FLOOR.map { it.name }.toSet()

    private val askSite = Regex(
        """(?:has|hasFeature|requires|gate)\s*\(\s*LicenseFeature\.(\w+)""",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    @Test
    fun `nothing asks the licence for a safety feature`() {
        val root = repoRoot()
        val offenders = roots.map { File(root, it) }.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readText().lineSequence().withIndex().mapNotNull { (i, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapNotNull null
                        val feature = askSite.find(line)?.groupValues?.get(1)
                            ?: return@mapNotNull null
                        if (feature !in floorNames) null
                        else "${file.name}:${i + 1}  ${trimmed.take(80)}"
                    }
                }
        }

        assertEquals(
            "ліцензію питають про безпекову функцію:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nМапа, навігація, тривоги й сховище працюють на будь-якому " +
                "рівні — це закон продукту, а не налаштування.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the scan actually covers the modules it claims to`() {
        val root = repoRoot()
        val missing = roots.filterNot { File(root, it).isDirectory }
        val scanned = roots.map { File(root, it) }.filter { it.isDirectory }
            .sumOf { dir -> dir.walkTopDown().count { it.isFile && it.extension == "kt" } }

        assertEquals("перейменований модуль тихо вивів би себе з-під перевірки", emptyList<String>(), missing)
        assertTrue("скан майже порожній ($scanned файлів) — він уже нічого не доводить", scanned > 300)
    }

    @Test
    fun `the detector sees an offender when there is one`() {
        val offending = listOf(
            "if (license.has(LicenseFeature.Map)) show()",
            "requires( LicenseFeature.Vault )",
            "gate(LicenseFeature.Alerts)",
        )

        offending.forEach { line ->
            val hit = askSite.find(line)?.groupValues?.get(1)
            assertTrue("детектор не бачить: $line", hit != null && hit in floorNames)
        }
        val paid = askSite.find("if (license.has(LicenseFeature.CrewSync)) sync()")!!.groupValues[1]
        assertEquals("CrewSync", paid)
        assertTrue("платні функції ворота питати можуть", paid !in floorNames)
    }

    @Test
    fun `the floor itself is not quietly shrunk`() {
        assertEquals(
            setOf(
                LicenseFeature.Map,
                LicenseFeature.Nav,
                LicenseFeature.Alerts,
                LicenseFeature.Vault,
            ),
            LicenseModel.SAFETY_FLOOR,
        )
    }

    // Окремого права на шифрування у переліку нема: Chat покриває і лист, і його
    // захист. Тому винести захист у платне не вийде, не зваливши цю перевірку.
    @Test
    fun `chat and voice are free, and the encryption comes with them`() {
        assertEquals(
            "вільний рівень змінився. Розширити його можна, звузити — ні: " +
                "захист листа не буває товаром, а зайняті руки — це небезпека, " +
                "не зручність. Будь-яка зміна тут переписує сторінку цін.",
            // VoicePremium тут з 03.09: живий голос переїхав у вільний ярус.
            // Розширення вільного — саме той напрямок, який ця перевірка
            // дозволяє; звуження лишається забороненим.
            LicenseModel.SAFETY_FLOOR + setOf(
                LicenseFeature.Chat,
                LicenseFeature.Voice,
                LicenseFeature.VoicePremium,
            ),
            LicenseModel.DEFAULT_FREE,
        )
        assertEquals(LicenseModel.DEFAULT_FREE, LicenseModel.grants(LicenseTier.Free))
    }

    @Test
    fun `what money buys is exactly five things, and none of them keeps a person alive`() {
        val paid = LicenseModel.grants(LicenseTier.Personal) - LicenseModel.grants(LicenseTier.Free)

        assertEquals(
            // Було шість; VoicePremium пішов у вільне 03.09.
            setOf(
                LicenseFeature.BridgePair,
                LicenseFeature.MemoryLongterm,
                LicenseFeature.MapTerrain3d,
                LicenseFeature.GnssBlackbox,
                LicenseFeature.ExportReports,
            ),
            paid,
        )
        // Тривога про підміну GPS живе в Alerts і лишається вільною; гроші
        // беремо лише за запис і розбір польотів (GnssBlackbox).
        assertTrue("платне перетнулося з безпекою", (paid intersect LicenseModel.SAFETY_FLOOR).isEmpty())
    }

    @Test
    fun `each tier gives strictly more than the one below it`() {
        val ladder = listOf(LicenseTier.Free, LicenseTier.Personal, LicenseTier.Crew, LicenseTier.Subunit)

        ladder.zipWithNext { lower, higher ->
            val below = LicenseModel.grants(lower)
            val above = LicenseModel.grants(higher)
            assertTrue("$higher забрав те, що дає $lower", above.containsAll(below))
            assertTrue("$higher не дає нічого понад $lower — рівні злилися", above.size > below.size)
        }

        assertEquals(
            "функція, якої не дає навіть найвищий рівень, недосяжна нікому",
            LicenseFeature.entries.toSet(),
            LicenseModel.grants(LicenseTier.Subunit),
        )
    }
}
