package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.data.CircleWhere
import local.phantom.companion.data.PeerLogSlot
import local.phantom.companion.data.PeerRosterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebMessengerCirclesTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private class FakeWhere : CircleWhere {
        val active = HashMap<String, Long>()
        override fun granted(id: String): Boolean = id in active
        override fun grant(id: String, untilMs: Long): Boolean {
            active[id] = untilMs
            return true
        }
        override fun revoke(id: String) {
            active.remove(id)
        }
    }

    @Test
    fun `list create delete members and share routes respond with expected envelopes`() {
        val store = PeerRosterStore(FakeSlot())
        val where = FakeWhere()
        store.bindWhere(where)
        val knownIds = setOf("p1", "p2", "g1")
        val route = WebMessengerCircles(store) { it in knownIds }

        assertTrue(route.claims(listOf("messenger", "circles")))
        assertFalse(route.claims(listOf("messenger", "backpack")))

        val list0 = route.handle("GET", listOf("messenger", "circles"), null)
        assertTrue(list0.contains(""""ok":true"""))
        assertTrue(list0.contains(""""max_custom":8"""))
        assertTrue(list0.contains(""""id":"personal""""))
        assertTrue(list0.contains(""""id":"work""""))

        val badName = route.handle("POST", listOf("messenger", "circles"), """{"name":"   "}""")
        assertTrue(badName.contains(""""status":400"""))
        assertTrue(badName.contains(""""code":"bad_circle_name""""))

        val createdResp = route.handle("POST", listOf("messenger", "circles"), """{"name":"Runners"}""")
        assertTrue(createdResp.contains(""""ok":true"""))
        assertTrue(createdResp.contains(""""name":"Runners""""))
        val customId = Regex(""""id":"(u:[0-9a-f]{8})"""").find(createdResp)!!.groupValues[1]

        repeat(7) { idx ->
            val r = route.handle("POST", listOf("messenger", "circles"), """{"name":"C$idx"}""")
            assertTrue(r.contains(""""ok":true"""))
        }
        val ninth = route.handle("POST", listOf("messenger", "circles"), """{"name":"Overflow"}""")
        assertTrue(ninth.contains(""""status":409"""))
        assertTrue(ninth.contains(""""code":"too_many_circles""""))

        val delBuiltin = route.handle("DELETE", listOf("messenger", "circles", "work"), null)
        assertTrue(delBuiltin.contains(""""status":400"""))
        assertTrue(delBuiltin.contains(""""code":"builtin_circle""""))

        val delMissing = route.handle("DELETE", listOf("messenger", "circles", "u:deadbeef"), null)
        assertTrue(delMissing.contains(""""status":404"""))
        assertTrue(delMissing.contains(""""code":"no_circle""""))

        val addUnknownPeer = route.handle(
            "POST",
            listOf("messenger", "circles", "work", "members"),
            """{"peer_id":"stranger"}""",
        )
        assertTrue(addUnknownPeer.contains(""""status":404"""))
        assertTrue(addUnknownPeer.contains(""""code":"no_conversation""""))

        val addMissingCircle = route.handle(
            "POST",
            listOf("messenger", "circles", "u:deadbeef", "members"),
            """{"peer_id":"p1"}""",
        )
        assertTrue(addMissingCircle.contains(""""status":404"""))
        assertTrue(addMissingCircle.contains(""""code":"no_circle""""))

        val added1 = route.handle(
            "POST",
            listOf("messenger", "circles", "work", "members"),
            """{"peer_id":"p1"}""",
        )
        assertTrue(added1.contains(""""circles":["work"]"""))

        val added2 = route.handle(
            "POST",
            listOf("messenger", "circles", customId.replace(":", "%3A"), "members"),
            """{"peer_id":"p1"}""",
        )
        assertTrue(added2.contains(""""work"""") && added2.contains(customId))

        val membersResp = route.handle("GET", listOf("messenger", "circles", "work", "members"), null)
        assertTrue(membersResp.contains(""""members":["p1"]"""))

        val badShare = route.handle(
            "POST",
            listOf("messenger", "circles", "work", "share"),
            """{"until_ms":100}""",
        )
        assertTrue(badShare.contains(""""status":400"""))
        assertTrue(badShare.contains(""""code":"bad_until""""))

        val future = System.currentTimeMillis() + 120_000L
        val okShare = route.handle(
            "POST",
            listOf("messenger", "circles", "work", "share"),
            """{"until_ms":$future}""",
        )
        assertTrue(okShare.contains(""""shared":1"""))
        assertEquals(future, where.active["p1"])

        val removed = route.handle("DELETE", listOf("messenger", "circles", "work", "members", "p1"), null)
        assertTrue(removed.contains(""""circles":["$customId"]"""))

        val delCustom = route.handle("DELETE", listOf("messenger", "circles", customId.replace(":", "%3A")), null)
        assertTrue(delCustom.contains(""""ok":true"""))
        assertTrue(store.circlesOf("p1").isEmpty())
    }

    @Test
    fun `bridge and container mount circles route and policy`() {
        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        val container = File(root, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()

        assertTrue("circlesRoute property missing in WebMessengerBridge.kt", "private val circlesRoute by lazy { WebMessengerCircles(container.peers)" in bridge)
        assertTrue("circlesRoute dispatch missing in WebMessengerBridge.kt", "if (circlesRoute.claims(seg)) return circlesRoute.handle(method, seg, body)" in bridge)
        assertTrue("circles array missing in WebMessengerBridge.kt", """.put("circles", JSONArray(container.peers.circlesOf(""" in bridge)
        assertTrue("bindWhere missing in AppContainer.kt", "peers.bindWhere(HubWhere(hub))" in container)
    }
}
