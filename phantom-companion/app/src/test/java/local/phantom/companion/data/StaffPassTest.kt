package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.StaffWire
import local.phantom.companion.ui.messenger.WebMessengerStaff
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaffPassTest {
    private val owner = "owner_node"
    private val pass = StaffWire.Pass(
        "Кавʼярня «Зерно»",
        10L,
        listOf(
            StaffWire.Thread("c1", "Ірина", listOf(StaffWire.Line(false, "Столик на сьому?", 1L), StaffWire.Line(true, "Так", 2L))),
            StaffWire.Thread("c2", "Тарас", listOf(StaffWire.Line(false, "Замовлення 2 капучино", 3L))),
        ),
    )
    private fun frame(p: StaffWire.Pass) = StaffWire.encode(p)!!

    @Test
    fun `пропуск несе лише імʼя клієнта й рядки — і схований від стрічки`() {
        val body = frame(pass)
        assertEquals(PeerFrames.Kind.STAFF, PeerFrames.kindOf(body))
        assertFalse(PeerFrames.Kind.STAFF.reachesConversation)
        val keys = mutableSetOf<String>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> o.keys().forEach { k -> keys += k; walk(o.get(k)) }
                is org.json.JSONArray -> for (i in 0 until o.length()) walk(o.get(i))
            }
        }
        walk(JSONObject(body.removePrefix(StaffWire.TAG)))
        assertEquals(setOf("b", "rev", "t", "id", "c", "l", "m", "x", "at"), keys)
        assertEquals(pass, StaffWire.decode(body))
    }

    @Test
    fun `відкликаний працівник втрачає розмови з наступним пропуском`() {
        val inbox = StaffInbox { it == owner }
        assertTrue(inbox.absorb(owner, frame(pass)))
        assertEquals(listOf("c1", "c2"), inbox.rows().single().second.threads.map { it.id })
        assertTrue(inbox.absorb(owner, frame(pass.copy(rev = 11L, threads = emptyList()))))
        assertTrue(inbox.rows().isEmpty())
        assertTrue(inbox.absorb(owner, frame(pass.copy(rev = 9L))))
        assertTrue(inbox.rows().isEmpty())
    }

    @Test
    fun `старий пропуск не повертає забране, чужий не приймається`() {
        val inbox = StaffInbox { it == owner }
        inbox.absorb(owner, frame(pass))
        inbox.absorb(owner, frame(pass.copy(rev = 12L, threads = pass.threads.take(1))))
        inbox.absorb(owner, frame(pass.copy(rev = 11L)))
        assertEquals(listOf("c1"), inbox.rows().single().second.threads.map { it.id })
        assertTrue(inbox.absorb("stranger", frame(pass.copy(rev = 99L))))
        assertEquals(listOf(owner), inbox.rows().map { it.first })
        assertFalse(inbox.absorb(owner, "привіт"))
    }

    @Test
    fun `повний пропуск влазить у кадр`() {
        val line = StaffWire.Line(false, "я".repeat(StaffWire.TEXT_MAX), 1L)
        val big = pass.copy(threads = (1..StaffWire.THREADS_MAX).map { StaffWire.Thread("c$it", "Клієнт $it", List(StaffWire.LINES_MAX) { line }) })
        val body = frame(big)
        assertTrue(body.length <= StaffWire.FRAME_MAX)
        assertEquals(StaffWire.THREADS_MAX, StaffWire.decode(body)!!.threads.size)
    }

    @Test
    fun `міст шле пропуск лише контакту, а працівник бачить доручене`() {
        val sent = mutableListOf<Pair<String, String>>()
        val inbox = StaffInbox { it == owner }
        val route = WebMessengerStaff(inbox::rows, { it == "staff_node" }) { to, b -> sent += to to b; true }
        val push = JSONObject().put("to", "staff_node").put(
            "pass",
            JSONObject().put("business", "Зерно").put("rev", 5).put(
                "threads",
                org.json.JSONArray().put(JSONObject().put("id", "c1").put("client", "Ірина").put("phone", "+380").put(
                    "lines", org.json.JSONArray().put(JSONObject().put("mine", false).put("text", "Привіт").put("at", 1)),
                )),
            ),
        ).toString()
        assertEquals(409, JSONObject(route.handle("POST", listOf("messenger", "staff", "push"), push.replace("staff_node", "x"))).getInt("status"))
        assertTrue(JSONObject(route.handle("POST", listOf("messenger", "staff", "push"), push)).getBoolean("ok"))
        val (to, body) = sent.single()
        assertEquals("staff_node", to)
        assertFalse(body.contains("+380"))
        inbox.absorb(owner, body)
        val got = JSONObject(route.handle("GET", listOf("messenger", "staff"), null)).getJSONObject("data").getJSONArray("passes")
        assertEquals("Ірина", got.getJSONObject(0).getJSONArray("threads").getJSONObject(0).getString("client"))
        assertNotNull(route)
    }
}
