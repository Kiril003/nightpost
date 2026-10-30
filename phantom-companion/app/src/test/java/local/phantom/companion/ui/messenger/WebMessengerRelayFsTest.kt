package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Двері «Для розробника» мосту: стан як є, перемикач — справжній, решта — 404. */
class WebMessengerRelayFsTest {

    private class Port : WebMessengerRelayFs.Port {
        var on = false
        var boom = false
        override fun view(): String {
            check(!boom)
            return """{"enabled":$on,"my_spk":null,"contacts":[]}"""
        }
        override fun setEnabled(on: Boolean) { this.on = on }
    }

    private fun seg(path: String) = path.trim('/').split('/')

    @Test fun `стан — те, що каже порт, у конверті мосту`() {
        val route = WebMessengerRelayFs(Port())
        assertTrue(route.claims(seg("messenger/relayfs")))
        assertFalse(route.claims(seg("messenger/backpack")))
        assertEquals(
            """{"ok":true,"status":200,"data":{"enabled":false,"my_spk":null,"contacts":[]}}""",
            route.handle("GET", seg("messenger/relayfs")),
        )
    }

    @Test fun `увімкнути й вимкнути — справді міняє прапорець і віддає новий стан`() {
        val port = Port()
        val route = WebMessengerRelayFs(port)
        assertTrue(route.handle("POST", seg("messenger/relayfs/on")).contains(""""enabled":true"""))
        assertTrue(port.on)
        assertTrue(route.handle("POST", seg("messenger/relayfs/off")).contains(""""enabled":false"""))
        assertFalse(port.on)
    }

    @Test fun `GET на перемикач, чужий шлях і збій — чесні коди, прапорець не чіпано`() {
        val port = Port()
        val route = WebMessengerRelayFs(port)
        assertTrue(route.handle("GET", seg("messenger/relayfs/on")).contains(""""status":404"""))
        assertTrue(route.handle("POST", seg("messenger/relayfs/maybe")).contains(""""status":404"""))
        assertFalse(port.on)
        port.boom = true
        assertTrue(route.handle("GET", seg("messenger/relayfs")).contains(""""status":500"""))
    }
}
