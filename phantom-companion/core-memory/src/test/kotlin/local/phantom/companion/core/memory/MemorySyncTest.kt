package local.phantom.companion.core.memory

import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Підробки тут не призупиняються, тож корутину можна прогнати без бібліотеки. */
internal fun <T> blocking(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return checkNotNull(out) { "suspended" }.getOrThrow()
}

/**
 * «ПК» тут — така сама репліка за протоколом `/api/v1/memory/changes` (SYNC.md):
 * правила в обох однакові (це доводить `MemoryVectorsTest`), тож сеанс синку
 * перевіряється наскрізно без мережі. [down] — обрив каналу на N-му запиті.
 */
private class FakePc(val ledger: Ledger = Ledger(LedgerStore(Seal.fromSecret("pc")), device = "pc-node")) : MemorySync.Transport {
    var down: Int? = null
    var calls = 0
    val pushedBodies = ArrayList<String>()

    private fun gate() {
        calls++
        if (down == calls) throw java.io.IOException("link dropped")
    }

    override suspend fun pull(since: Long, limit: Int): Pair<Int, String> {
        gate()
        val p = ledger.changes(since, limit)
        return 200 to buildJsonObject {
            put("node", "pc-node")
            put("cursor", p.cursor)
            put("more", p.more)
            put("changes", JsonArray(p.changes))
        }.toString()
    }

    override suspend fun push(body: String): Pair<Int, String> {
        gate()
        pushedBodies += body
        val o = Json.parseToJsonElement(body).jsonObject
        val r = ledger.applyChanges(o["changes"] as JsonArray, device = "phone-7")
        return 200 to buildJsonObject {
            put("applied", r.applied); put("ignored", r.ignored); put("cursor", r.cursor); put("node", "pc-node")
        }.toString()
    }
}

class MemorySyncTest {

    private var clock = 1_790_000_000_000L
    private fun pcNode() = FakePc(Ledger(LedgerStore(Seal.fromSecret("pc")) { clock }, device = "pc-node") { clock++ })
    private fun phone(file: File? = null) = Ledger(LedgerStore(Seal.fromSecret("phone"), file) { clock }, device = "phone-7") { clock++ }

    private fun said(ledger: Ledger, predicate: String, value: String, text: String, source: String = "owner_said") =
        ledger.rememberText(text, Ledger.Origin.OWNER_CHAT, predicate = predicate, value = value, source = source)

    private fun live(l: Ledger) = l.known().map { it.status to it.displayText() }.sortedBy { it.second }

    @Test fun `phone and PC end up remembering the same thing, both ways`() {
        val pc = pcNode()
        val ph = phone()
        said(ph, "pet", "пес Бублик", "У тебе є пес Бублик")
        pc.ledger.rememberText("Ти живеш у Вінниці", Ledger.Origin.OWNER_CHAT, predicate = "lives_in", value = "Вінниця")

        val r = blocking { MemorySync(ph, pc) { clock }.run() }
        assertTrue(r.error, r.ok)
        assertEquals(live(pc.ledger), live(ph))
        assertEquals(2, live(ph).size)

        // відлуння немає: другий сеанс нічого не шле й нічого не міняє
        val before = pc.pushedBodies.size
        val r2 = blocking { MemorySync(ph, pc) { clock }.run() }
        assertTrue(r2.ok)
        assertEquals(0, r2.pushed)
        assertEquals(before, pc.pushedBodies.size)
        assertEquals(0, r2.applied)
    }

    @Test fun `newer validity wins across devices and the loser is closed on both`() {
        val pc = pcNode()
        val ph = phone()
        pc.ledger.rememberText("Ти живеш у Вінниці", Ledger.Origin.OWNER_CHAT, predicate = "lives_in", value = "Вінниця")
        clock += 86_400_000
        said(ph, "lives_in", "Львів", "Ти живеш у Львові")
        repeat(2) { assertTrue(blocking { MemorySync(ph, pc) { clock }.run() }.ok) }

        for (l in listOf(ph, pc.ledger)) {
            val ctx = l.contextForPrompt("Де я живу?").text
            assertTrue(ctx, "Львов" in ctx)
            assertFalse(ctx, "Вінниц" in ctx)
        }
        assertEquals(live(pc.ledger), live(ph))
    }

    @Test fun `a tombstone beats a fact, and «неправда» on the phone blocks the PC from relearning it`() {
        val pc = pcNode()
        val ph = phone()
        val (_, id) = said(ph, "allergy", "мед", "У тебе алергія на мед")
        assertTrue(blocking { MemorySync(ph, pc) { clock }.run() }.ok)
        assertTrue(ph.forget(id)) // «неправда»
        // тим часом ПК ще раз «почув» те саме під іншим id
        assertTrue(blocking { MemorySync(ph, pc) { clock }.run() }.ok)
        assertEquals(Ledger.Outcome.BLOCKED, pc.ledger.rememberText("У тебе алергія на мед", Ledger.Origin.OWNER_CHAT,
            predicate = "allergy", value = "мед").first)
        assertTrue(ph.known().isEmpty() && pc.ledger.known().isEmpty())
    }

    @Test fun `«забудь» erases on both but does not block saying it again`() {
        val pc = pcNode()
        val ph = phone()
        val (_, id) = said(ph, "car", "Skoda Fabia", "Ти їздиш на Skoda Fabia")
        assertTrue(blocking { MemorySync(ph, pc) { clock }.run() }.ok)
        assertTrue(ph.forget(id, why = "forget"))
        assertTrue(blocking { MemorySync(ph, pc) { clock }.run() }.ok)
        val gone = pc.ledger.store.get(id)!!
        assertEquals("tombstone", gone.status)
        assertEquals("forget", gone.reason)
        assertEquals("", gone.text)
        assertEquals(Ledger.Outcome.ADDED, pc.ledger.rememberText("Ти їздиш на Skoda Fabia", Ledger.Origin.OWNER_CHAT,
            predicate = "car", value = "Skoda Fabia").first)
    }

    @Test fun `a dropped link resumes where it stopped, without losing or doubling anything`() {
        val pc = pcNode()
        repeat(25) { n ->
            pc.ledger.rememberText("Знайомий $n любить каву номер $n", Ledger.Origin.OWNER_CHAT, subject = "знайомий $n",
                predicate = "likes", value = "кава $n")
        }
        val dir = Files.createTempDirectory("memsync").toFile()
        val file = File(dir, "memory_ledger.json")
        val ph = phone(file)
        pc.down = 2 // перший pull пройде, на другому — обрив
        val first = blocking { MemorySync(ph, pc, pageSize = 10) { clock }.run() }
        assertFalse(first.ok)
        assertTrue(first.error!!, "link dropped" in first.error!!)
        assertEquals(10, ph.known().size)

        // процес помер і піднявся: курсор лежить на диску разом із репліками
        val again = phone(file)
        assertEquals(10, again.known().size)
        pc.down = null
        val second = blocking { MemorySync(again, pc, pageSize = 10) { clock }.run() }
        assertTrue(second.error, second.ok)
        assertEquals(15, second.pulled)
        assertEquals(25, again.known().size)
        assertNull(MemorySync(again, pc).state()["last_error"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it })
    }

    @Test fun `failure is reported honestly in the state`() {
        val pc = object : MemorySync.Transport {
            override suspend fun pull(since: Long, limit: Int) = 401 to """{"detail":"token expired"}"""
            override suspend fun push(body: String) = 200 to "{}"
        }
        val ph = phone()
        val r = blocking { MemorySync(ph, pc) { clock }.run() }
        assertFalse(r.ok)
        val st = MemorySync(ph, pc).state()
        assertTrue(st.toString(), "token expired" in st["last_error"].toString())
        assertEquals("null", st["last_ok_ms"].toString())
    }

    @Test fun `the replica file on disk holds no plaintext and reloads intact`() {
        val dir = Files.createTempDirectory("memdisk").toFile()
        val file = File(dir, "memory_ledger.json")
        val ph = phone(file)
        said(ph, "pet", "пес Бублик", "У тебе є пес Бублик")
        val (_, gone) = said(ph, "allergy", "мед", "У тебе алергія на мед")
        ph.forget(gone)
        val disk = file.readText()
        assertFalse(disk, "Бублик" in disk || "мед" in disk || "алергія" in disk)
        assertTrue(disk, "g1:" in disk)

        val back = phone(file)
        assertEquals(listOf("У тебе є пес Бублик"), back.known().map { it.text })
        assertEquals(Ledger.Outcome.BLOCKED, said(back, "allergy", "мед", "У тебе алергія на мед").first)
        // чужим ключем не відкрити нічого — і нічого не вигадується
        val stranger = Ledger(LedgerStore(Seal.fromSecret("someone-else"), file))
        assertTrue(stranger.known().isEmpty())
    }

    @Test fun `other people's private messages are never remembered without the owner's explicit ask`() {
        val ph = phone()
        try {
            ph.rememberText("Олег винен 200 грн", Ledger.Origin.PEER_MESSAGE)
            throw AssertionError("мало відмовити")
        } catch (_: Ledger.WriteRefused) {
        }
        assertTrue(ph.known().isEmpty())
        val (o, id) = ph.rememberText("Олег винен 200 грн", Ledger.Origin.PEER_MESSAGE, explicit = true, conversationId = "chat:olег")
        assertEquals(Ledger.Outcome.ADDED, o)
        assertEquals("owner_said", ph.store.get(id)!!.source)
        val (_, m) = ph.rememberText("Квитки на 12 жовтня куплено", Ledger.Origin.MISSION, conversationId = "mission:1")
        assertEquals("heard", ph.store.get(m)!!.source)
    }

    @Test fun `prompt cites items and with nothing relevant the phone says «не знаю»`() {
        val ph = phone()
        val (_, id) = said(ph, "pet", "пес Бублик", "У тебе є пес Бублик")
        val short = ph.store.get(id)!!.shortId
        assertTrue(ph.contextForPrompt("Як звати мого пса?").text.contains("[m:$short] У тебе є пес Бублик"))
        assertNull(ph.ungroundedAnswer("Як звати мого пса?"))
        assertEquals(Grounding.DONT_KNOW, ph.ungroundedAnswer("Яка марка мого ноутбука?"))
        assertNull(ph.ungroundedAnswer("Що мені взяти з собою в похід?"))
        assertEquals(listOf(id), ph.citations("Бублик [m:$short], [m:deadbeef]").map { it.id })
        assertEquals("Бублик.", Ledger.stripCitations("Бублик [m:$short]."))
        val exp = ph.export()
        assertEquals("phantom-memory/1", exp["format"]!!.toString().trim('"'))
        assertEquals(1, (exp["items"] as JsonArray).size)
    }
}
