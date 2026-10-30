package local.phantom.companion.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.GateOutcome
import local.phantom.companion.core.tool.permission.InMemoryToolPermissionStore
import local.phantom.companion.core.tool.permission.ToolPermissionGate
import local.phantom.companion.data.BodyLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SymbioteOrdersTest {

    private class MemShelf : BodyLadder.Shelf {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private class FakeBody : SymbioteOrders.Body {
        val performed = mutableListOf<String>()
        val reported = mutableListOf<String>()
        val suggested = mutableListOf<String>()
        val findingShown = mutableListOf<Boolean>()
        var rings = 0
        var yes: CompletableDeferred<Boolean> = CompletableDeferred()

        override suspend fun perform(command: PhantomApi.SymbioteCommand): PhantomApi.ResultIn {
            performed += command.verb
            return PhantomApi.ResultIn(command.id, ok = true, detail = "зроблено")
        }

        override suspend fun askYes(command: PhantomApi.SymbioteCommand, what: String) = yes.await()
        override fun suggest(command: PhantomApi.SymbioteCommand, what: String) {
            suggested += what
        }
        override fun report(what: String) {
            reported += what
        }
        override suspend fun ringOnce() {
            rings++
        }
        override fun finding(on: Boolean) {
            findingShown += on
        }
    }

    private val shelf = MemShelf()
    private val ladder = BodyLadder(shelf)
    private val body = FakeBody()
    private val posted = mutableListOf<PhantomApi.ResultIn>()

    private fun cmd(verb: String, id: String = verb) = PhantomApi.SymbioteCommand(id = id, verb = verb)

    private fun TestScope.orders() = SymbioteOrders(ladder, body, backgroundScope, ringGapMs = 1_000)

    private fun PhantomApi.ResultIn.status() = payload["status"]

    @Test
    fun `після спарювання ПК лише бачить - діяти відмовлено людськими словами`() = runTest {
        val orders = orders()
        orders.handle(cmd("phone.ring")) { posted += it }
        orders.handle(cmd("phone.notify")) { posted += it }
        assertEquals(emptyList<String>(), body.performed)
        assertEquals(listOf("refused", "refused"), posted.map { it.status() })
        assertEquals("Для «Звук і голос» дозволено лише бачити", posted[0].detail)

        orders.handle(cmd("phone.locate")) { posted += it }
        assertEquals(listOf("phone.locate"), body.performed)
    }

    @Test
    fun `вимкнена сфера не віддає навіть місця`() = runTest {
        ladder.set(BodyLadder.Sphere.PLACE, BodyLadder.Rung.OFF)
        orders().handle(cmd("phone.locate")) { posted += it }
        assertEquals(emptyList<String>(), body.performed)
        assertEquals("refused", posted.single().status())
    }

    @Test
    fun `зупинити все сильніше за «мовчки» і переживає перезапуск`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.SILENT)
        ladder.stopAll()
        val orders = orders()
        orders.handle(cmd("phone.ring")) { posted += it }
        orders.handle(cmd("phone.locate")) { posted += it }
        assertEquals(emptyList<String>(), body.performed)
        assertTrue(posted.all { it.detail == "Усе зупинено на телефоні" })

        assertTrue("вимикач лежить у сховку", BodyLadder(shelf).stopped.value)
        orders.handle(cmd("phone.stop")) { posted += it }
        assertTrue("«перестань» не питає дозволу", posted.last().ok)
    }

    @Test
    fun `знайти телефон дзвонить, доки не прийде phone stop`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.SILENT)
        val orders = orders()
        orders.handle(cmd("phone.find")) { posted += it }
        runCurrent()
        advanceTimeBy(5_500)
        assertTrue("дзвонить уже ${body.rings} разів", body.rings >= 5)
        assertTrue(orders.ringing.value)

        orders.handle(cmd("phone.stop")) { posted += it }
        runCurrent()
        val after = body.rings
        advanceTimeBy(10_000)
        assertEquals(after, body.rings)
        assertFalse(orders.ringing.value)
        assertEquals(listOf(true, false), body.findingShown)
    }

    @Test
    fun `дотик на телефоні теж зупиняє дзвін`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.SILENT)
        val orders = orders()
        orders.handle(cmd("phone.find")) { posted += it }
        advanceTimeBy(2_500)
        orders.stopRinging()
        runCurrent()
        val after = body.rings
        advanceTimeBy(10_000)
        assertEquals(after, body.rings)
    }

    @Test
    fun `зупинити все глушить дзвін без жодного слова від ПК`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.SILENT)
        val orders = orders()
        orders.handle(cmd("phone.find")) { posted += it }
        advanceTimeBy(1_500)
        ladder.stopAll()
        runCurrent()
        val after = body.rings
        advanceTimeBy(10_000)
        assertEquals(after, body.rings)
        assertFalse(orders.ringing.value)
    }

    @Test
    fun `робити з так чекає людину`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.ASK)
        val orders = orders()
        orders.handle(cmd("phone.speak")) { posted += it }
        runCurrent()
        assertEquals(listOf("asked"), posted.map { it.status() })
        assertEquals(emptyList<String>(), body.performed)

        body.yes.complete(true)
        runCurrent()
        assertEquals(listOf("phone.speak"), body.performed)
        assertTrue(posted.last().ok)
    }

    @Test
    fun `на ні нічого не робиться`() = runTest {
        ladder.set(BodyLadder.Sphere.NOTICES, BodyLadder.Rung.ASK)
        orders().handle(cmd("phone.notify")) { posted += it }
        body.yes.complete(false)
        runCurrent()
        assertEquals(emptyList<String>(), body.performed)
        assertEquals("declined", posted.last().status())
    }

    @Test
    fun `зупинка під час питання гасить прохання`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.ASK)
        val orders = orders()
        orders.handle(cmd("phone.ring")) { posted += it }
        runCurrent()
        ladder.stopAll()
        orders.halt()
        runCurrent()
        assertEquals("stopped", posted.last().status())
        assertEquals(emptyList<String>(), body.performed)
    }

    @Test
    fun `пропонувати і звітувати`() = runTest {
        ladder.set(BodyLadder.Sphere.SOUND, BodyLadder.Rung.SUGGEST)
        ladder.set(BodyLadder.Sphere.NOTICES, BodyLadder.Rung.REPORT)
        val orders = orders()
        orders.handle(cmd("phone.ring")) { posted += it }
        assertEquals(listOf("подати звук"), body.suggested)
        assertEquals("suggested", posted.last().status())

        orders.handle(cmd("phone.notify")) { posted += it }
        assertEquals(listOf("phone.notify"), body.performed)
        assertEquals(listOf("показати сповіщення"), body.reported)
    }

    // --- SEE/ACT: очі й руки симбіота на телефоні ---

    @Test
    fun `читання екрана дозволено за замовчуванням, а дотик - ні`() = runTest {
        val orders = orders()
        // Сфера «Екран» після спарювання стоїть на «бачити».
        orders.handle(cmd("phone.screen.read")) { posted += it }
        assertEquals(listOf("phone.screen.read"), body.performed)

        orders.handle(cmd("phone.tap")) { posted += it }
        assertEquals("refused", posted.last().status())
        assertEquals("Для «Екран» дозволено лише бачити", posted.last().detail)
    }

    @Test
    fun `вимкнена сфера екрана не дає навіть прочитати`() = runTest {
        ladder.set(BodyLadder.Sphere.SCREEN, BodyLadder.Rung.OFF)
        orders().handle(cmd("phone.screen.read")) { posted += it }
        assertEquals(emptyList<String>(), body.performed)
        assertEquals("refused", posted.single().status())
    }

    @Test
    fun `набір тексту завжди питає людину - навіть на «мовчки»`() = runTest {
        // Клавіатура незворотна: вище за «так» не підіймається. Навіть REPORT/SILENT → питати.
        ladder.set(BodyLadder.Sphere.KEYBOARD, BodyLadder.Rung.SILENT)
        val orders = orders()
        orders.handle(cmd("phone.type")) { posted += it }
        runCurrent()
        assertEquals(listOf("asked"), posted.map { it.status() })
        assertEquals(emptyList<String>(), body.performed)

        body.yes.complete(true)
        runCurrent()
        assertEquals(listOf("phone.type"), body.performed)
    }

    @Test
    fun `зупинити все глушить і руки - дотик відмовлено`() = runTest {
        ladder.set(BodyLadder.Sphere.SCREEN, BodyLadder.Rung.SILENT)
        ladder.stopAll()
        orders().handle(cmd("phone.tap")) { posted += it }
        assertEquals(emptyList<String>(), body.performed)
        assertEquals("Усе зупинено на телефоні", posted.single().detail)
    }

    @Test
    fun `зупинити все на драбині зупиняє і власного асистента телефона, а resume повертає`() = runTest {
        val permStore = InMemoryToolPermissionStore().apply {
            setDecision("settings", Decision.ALWAYS)
        }
        val gate = ToolPermissionGate(store = permStore, ask = { Decision.ONCE })
        val readTool = object : Tool {
            override val id = "weather.now"
            override val description = "Погода"
            override val argsSchema = JsonObject(emptyMap())
            override val permissions = emptySet<ToolPermission>()
            override val risk = ToolRisk.READ
            override val streaming = false
            override val manifest = FeatureManifest(id = "tool.weather.now", tier = 1, displayName = "Погода")
            override suspend fun execute(
                args: JsonObject,
                context: ToolContext,
                emit: suspend (ToolEvent) -> Unit,
            ): ToolResult = ToolResult.Ok("ok", null, 0L)
        }
        val alwaysDeviceTool = object : Tool {
            override val id = "settings"
            override val description = "Налаштування"
            override val argsSchema = JsonObject(emptyMap())
            override val permissions = setOf(ToolPermission.SETTINGS_WRITE)
            override val risk = ToolRisk.DEVICE
            override val streaming = false
            override val manifest = FeatureManifest(id = "tool.settings", tier = 1, displayName = "Налаштування")
            override suspend fun execute(
                args: JsonObject,
                context: ToolContext,
                emit: suspend (ToolEvent) -> Unit,
            ): ToolResult = ToolResult.Ok("ok", null, 0L)
        }

        ladder.stopAll()
        assertTrue(
            "stopAll на BodyLadder мусить блокувати навіть READ-інструменти асистента телефона",
            gate.check(readTool, "{}") is GateOutcome.Denied,
        )
        assertTrue(
            "stopAll на BodyLadder мусить блокувати навіть інструменти зі збереженим ALWAYS",
            gate.check(alwaysDeviceTool, "{}") is GateOutcome.Denied,
        )

        // Переживає перезапуск процесу (нова BodyLadder на тій самій полиці):
        val restartedLadder = BodyLadder(shelf)
        assertTrue(restartedLadder.stopped.value)
        assertTrue(gate.check(readTool, "{}") is GateOutcome.Denied)

        // Лише явний resume повертає дозвіл:
        restartedLadder.resume()
        assertTrue(gate.check(readTool, "{}") is GateOutcome.Granted)
        assertTrue(gate.check(alwaysDeviceTool, "{}") is GateOutcome.Granted)
    }
}
