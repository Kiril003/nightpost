package local.phantom.companion.data

import java.io.File
import local.phantom.companion.core.constitution.ConstitutionRegistry
import local.phantom.companion.core.tool.ToolRegistry
import local.phantom.companion.core.tool.impl.BillSplitTool
import local.phantom.companion.core.tool.impl.MeetupCreateTool
import local.phantom.companion.ui.messenger.WebMessengerMeetupDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `meetup.create` і `bill.split` — рівно одні двері.
 *
 * `ToolRegistry` кладе інструменти в карту за ідентифікатором (`store[tool.id] = tool`):
 * другий інструмент із тим самим ім'ям МОВЧКИ витісняє перший. Міст сцени мав
 * свою просту пару цих інструментів (без звірки імен із реєстром і без «Не знайшов»);
 * зведена з помічником, вона стояла в списку фабрики останньою й тихо підміняла
 * справжніх — тести помічника були зелені, а на телефоні діяли б голі двері.
 */
class MeetupToolsSingleDoorTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    /** Усі `src/main`-джерела всіх модулів дерева — без збірок. */
    private fun mainSources(): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
        .toList()

    private val idDeclaration = Regex("""override\s+val\s+id\s*(?::\s*String\s*)?=\s*"([^"]+)"""")

    @Test
    fun `кожне ім'я інструмента в основному коді оголошене рівно один раз`() {
        val owners = mutableMapOf<String, MutableList<String>>()
        for (f in mainSources()) {
            idDeclaration.findAll(f.readText()).forEach { owners.getOrPut(it.groupValues[1]) { mutableListOf() } += f.name }
        }
        for (id in listOf("meetup.create", "bill.split")) {
            assertEquals("«$id» оголошено не в одному місці: ${owners[id]}", 1, owners[id].orEmpty().size)
        }
    }

    @Test
    fun `фабрика реєструє справжні інструменти помічника по одному разу й без чужих підмін`() {
        val factory = File(root, "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        assertEquals(1, Regex("""core\.tool\.impl\.MeetupCreateTool\(""").findAll(factory).count())
        assertEquals(1, Regex("""core\.tool\.impl\.BillSplitTool\(""").findAll(factory).count())
        assertTrue(
            "після списку інструментів фабрика дописує ще якісь («) + …all()»): вони витіснять списочні за ім'ям",
            !Regex("""\)\s*\+\s*[\w.]+\.all\(\)""").containsMatchIn(factory),
        )
    }

    @Test
    fun `обидва інструменти допущені до реєстру конституційними воротами`() {
        val words = WordsStub
        val registry = ToolRegistry(
            listOf(
                MeetupCreateTool(roster = { emptyList() }, open = { WebMessengerMeetupDraft.asked(it) }, words = { words }),
                BillSplitTool(open = { WebMessengerMeetupDraft.asked(it) }, words = { words }),
            ),
            ConstitutionRegistry(),
        )
        assertNotNull("meetup.create не допущено: ${registry.rejectionFor("meetup.create")}", registry.get("meetup.create"))
        assertNotNull("bill.split не допущено: ${registry.rejectionFor("bill.split")}", registry.get("bill.split"))
    }

    private object WordsStub : local.phantom.companion.core.tool.impl.MeetupWords {
        override fun drafted(people: List<String>, place: String?, at: String?) = ""
        override fun emptyDraft() = ""
        override fun notFound(name: String) = ""
        override fun which(name: String, candidates: List<String>) = ""
        override fun splitOpened(mode: String) = ""
        override fun rosterUnreadable() = ""
        override fun draftFailed() = ""
        override fun badMode(mode: String) = ""
    }
}
