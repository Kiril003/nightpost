package local.phantom.companion.ui.tools

import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.permission.ToolAsk
import local.phantom.companion. data.BodyLadder
import local.phantom.companion.core.tool.permission.SymbioteStop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolConsentModelTest {

    @Before
    fun setUp() {
        SymbioteStop.resume()
    }

    @After
    fun tearDown() {
        SymbioteStop.resume()
    }

    @Test
    fun `duplicate header field kinds are marked to display their raw key name`() {
        val ask = ToolAsk(
            toolId = "nav.start",
            description = "Навігація",
            risk = ToolRisk.OUTWARD,
            argsPreview = """{"to":"Вул. Хрещатик 1","destination":"Вул. Грушевського 5"}""",
            allowedKeys = setOf("to", "destination", "profile"),
        )
        val model = buildToolConsentModel(ask)
        val recipients = model.headerFields.filter { it.kind == ToolAsk.FieldKind.RECIPIENT }
        assertEquals(2, recipients.size)
        assertTrue(
            "коли є два поля одного виду (RECIPIENT), showRawKeyFor має містити цей вид",
            ToolAsk.FieldKind.RECIPIENT in model.duplicateHeaderKinds,
        )
        assertEquals("Отримувач (to)", formatHeaderFieldLabel("Отримувач", recipients[0], model))
        assertEquals("Отримувач (destination)", formatHeaderFieldLabel("Отримувач", recipients[1], model))
    }

    @Test
    fun `single header field kind does not append raw key name to label`() {
        val ask = ToolAsk(
            toolId = "people.draft",
            description = "Чернетка",
            risk = ToolRisk.OUTWARD,
            argsPreview = """{"who":"Олена","text":"Привіт"}""",
            allowedKeys = setOf("who", "text"),
        )
        val model = buildToolConsentModel(ask)
        val recipient = model.headerFields.single { it.kind == ToolAsk.FieldKind.RECIPIENT }
        assertFalse(ToolAsk.FieldKind.RECIPIENT in model.duplicateHeaderKinds)
        assertEquals("Отримувач", formatHeaderFieldLabel("Отримувач", recipient, model))
    }

    @Test
    fun `BodyLadder syncStopFromRaw sets SymbioteStop synchronously before BodyLadder instance exists`() {
        SymbioteStop.resume()
        assertFalse(SymbioteStop.stopped.value)

        BodyLadder.syncStopFromRaw("1")
        assertTrue("stop_all = '1' має синхронно вмикати SymbioteStop", SymbioteStop.stopped.value)

        BodyLadder.syncStopFromRaw("0")
        assertFalse("stop_all = '0' має вимикати SymbioteStop", SymbioteStop.stopped.value)
    }
}
