package local.phantom.companion.ui.settings

import java.io.File
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.R
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.InMemoryToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsSettingsScreenTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайдено корінь сховища з feature-stream/")

    private fun strings(dir: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File(root, "app/src/main/res/$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun consentGatedRegistryIds(): Set<String> {
        val factory = File(root, "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        val classes = Regex("""core\.(?:tool\.impl|geo\.metro)\.(\w+Tool)\(""").findAll(factory)
            .map { it.groupValues[1] }.toSet()
        assertTrue("реєстр без інструментів — фабрика переїхала?", classes.size > 20)
        val sources = listOf("core-symbiote/src/main", "core-geo/src/main", "app/src/main").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        }
        val classDecl = Regex("""class\s+\w+Tool\b""")
        val gated = mutableSetOf<String>()
        for (cls in classes) {
            val decl = Regex("""class\s+$cls\b""")
            val body = sources.firstNotNullOfOrNull { text ->
                val start = decl.find(text)?.range?.first ?: return@firstNotNullOfOrNull null
                val next = classDecl.find(text, start + cls.length + 6)?.range?.first ?: text.length
                text.substring(start, next)
            } ?: continue
            val id = Regex("""override val id\s*(?::\s*String)?\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                ?: continue
            val risk = Regex("""override val risk\s*(?::\s*ToolRisk)?\s*=\s*ToolRisk\.(\w+)""").find(body)?.groupValues?.get(1)
                ?: continue
            if (risk != "READ") gated += id
        }
        return gated
    }

    private fun fakeTool(
        id: String,
        risk: ToolRisk,
        askEveryCall: Boolean = false,
        displayName: String = "Дія",
    ): Tool = object : Tool {
        override val id: String = id
        override val description: String = "Системний опис для моделі"
        override val argsSchema: JsonObject = JsonObject(emptyMap())
        override val permissions: Set<ToolPermission> = emptySet()
        override val risk: ToolRisk = risk
        override val askEveryCall: Boolean = askEveryCall
        override val streaming: Boolean = false
        override val manifest: FeatureManifest = FeatureManifest(
            id = "tool.$id",
            tier = 1,
            displayName = displayName,
        )

        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult = ToolResult.Ok("ok", null, 0L)
    }

    @Test
    fun `ToolLabels covers every consent-gated tool id in the registry with non-empty uk and en strings`() {
        val gatedIds = consentGatedRegistryIds()
        assertTrue("знайдено замало інструментів зі згодою: $gatedIds", gatedIds.size >= 25)

        val uk = strings("values-uk")
        val en = strings("values")
        val nameByResId = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val nameByToolIdFromSource = Regex(""""([^"]+)"\s*to\s*R\.string\.(\w+)""")
            .findAll(File(root, "app/src/main/java/local/phantom/companion/ui/settings/ToolLabels.kt").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val fieldNames = R.string::class.java.fields.map { it.name }.toSet()

        for (id in gatedIds) {
            val resId = ToolLabels.resId(id)
            assertNotNull("ToolLabels не має підпису для «$id»", resId)
            val resName = nameByToolIdFromSource[id] ?: nameByResId[resId!!]
            assertNotNull("не знайдено імені ресурсу для «$id» ($resId)", resName)
            assertTrue("R.string не містить поля $resName", resName!! in fieldNames)
            val ukText = uk[resName]?.trim().orEmpty()
            val enText = en[resName]?.trim().orEmpty()
            assertTrue("values-uk/strings.xml має порожній або відсутній $resName для «$id»", ukText.isNotEmpty())
            assertTrue("values/strings.xml має порожній або відсутній $resName для «$id»", enText.isNotEmpty())
            assertFalse("uk підпис для «$id» збігається з сирим id", ukText == id)
            assertFalse("en підпис для «$id» збігається з сирим id", enText == id)
        }
    }

    @Test
    fun `Tools screen filter excludes READ and askEveryCall tools and hides unknown tools without display name`() {
        val readTool = fakeTool("weather.now", ToolRisk.READ)
        val askEveryTool = fakeTool("shell", ToolRisk.OUTWARD, askEveryCall = true)
        val deviceTool = fakeTool("phone.alarm", ToolRisk.DEVICE)
        val outwardTool = fakeTool("http", ToolRisk.OUTWARD)
        val unknownNamed = fakeTool("custom.named", ToolRisk.DEVICE, displayName = "Власна дія")
        val unknownBlank = fakeTool("custom.blank", ToolRisk.DEVICE, displayName = "   ")
        val unknownRawId = fakeTool("custom.raw", ToolRisk.OUTWARD, displayName = "custom.raw")

        val decided = ToolLabels.decidable(
            listOf(readTool, askEveryTool, outwardTool, deviceTool, unknownNamed, unknownBlank, unknownRawId),
        )

        assertEquals(
            listOf("custom.named", "http", "phone.alarm"),
            decided.map { it.id },
        )
        assertEquals("Власна дія", ToolLabels.labelOrNull(unknownNamed) { "" })
        assertNull(ToolLabels.labelOrNull(unknownBlank) { "" })
        assertNull(ToolLabels.labelOrNull(unknownRawId) { "" })
    }

    @Test
    fun `ToolsSettingsScreen uses decidable filter, localized labels, and off-main-thread persistence`() {
        val code = File(root, "app/src/main/java/local/phantom/companion/ui/settings/ToolsSettingsScreen.kt")
            .readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

        assertTrue("екран не фільтрує інструменти через ToolLabels.decidable", "ToolLabels.decidable(" in code)
        assertFalse("група READ залишилась на екрані", "tools_group_read" in code || "ToolRisk.READ" in code)
        assertFalse("підзаголовок-пояснення залишився на екрані", "tools_screen_subtitle" in code)
        assertFalse("екран досі малює сирий tool.id або tool.description", "tool.description" in code || "text = tool.id" in code)
        assertTrue("рішення не завантажуються на Dispatchers.IO", "withContext(Dispatchers.IO)" in code)
        assertTrue("запис рішення не йде на Dispatchers.IO", "launch(Dispatchers.IO)" in code)
        assertFalse("екран досі перечитує всі ключі через reload++", "reload++" in code)
    }

    @Test
    fun `T56 - one choice for all rows, shown only when every row agrees`() {
        val ids = listOf("http", "phone.alarm", "sms.send")
        assertEquals(Decision.ASK, ToolBulk.common(emptyMap(), ids))
        assertNull(ToolBulk.common(mapOf("http" to Decision.ALWAYS), ids))
        assertEquals(Decision.NEVER, ToolBulk.common(ids.associateWith { Decision.NEVER }, ids))

        val store = InMemoryToolPermissionStore()
        ToolBulk.apply(store, ids, Decision.ALWAYS)
        assertTrue(ids.all { store.decision(it) == Decision.ALWAYS })
        ToolBulk.apply(store, ids, Decision.ASK)
        assertTrue(ids.all { store.decision(it) == Decision.ASK })
    }

    @Test
    fun `T55 - a change for all tools waits for a confirm, nothing is forgotten silently`() {
        val code = File(root, "app/src/main/java/local/phantom/companion/ui/settings/ToolsSettingsScreen.kt").readText()
        assertFalse("«Забути всі рішення» досі стирає без питання", "tools_reset_all" in code || ".clearAll()\n" in code.substringBefore("internal object ToolBulk"))
        val dialog = code.substringAfter("bulk?.let", "")
        assertTrue("зміна для всіх не питає підтвердження", "PhantomDialog(" in dialog && "R.string.tools_all_apply" in dialog)
        assertTrue("підтвердження нічого не застосовує", "ToolBulk.apply(" in dialog.substringAfter("onConfirm"))
        assertEquals("ToolBulk.apply поза підтвердженням", 1, Regex("""ToolBulk\.apply\(""").findAll(code).count())
    }
}
