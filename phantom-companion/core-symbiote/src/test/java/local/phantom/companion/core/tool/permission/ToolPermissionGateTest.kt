package local.phantom.companion.core.tool.permission

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ворота судять за [ToolRisk]. Кожне твердження тут перевіряє НАСЛІДОК —
 * чи спитали, чи записали рішення — а не те, що виклик не впав.
 */
class ToolPermissionGateTest {

    private lateinit var store: InMemoryToolPermissionStore
    private val asked = mutableListOf<ToolAsk>()

    @Before
    fun setUp() {
        SymbioteStop.resume()
        store = InMemoryToolPermissionStore()
        asked.clear()
    }

    @After
    fun tearDown() {
        SymbioteStop.resume()
    }

    private fun gate(answer: Decision?): ToolPermissionGate {
        // Тип виписано навмисно: без нього Kotlin виводить звичайну Function1,
        // і підстановка в suspend-параметр не компілюється.
        val asker: (suspend (ToolAsk) -> Decision)? =
            if (answer == null) null else { req -> asked += req; answer }
        return ToolPermissionGate(store = store, ask = asker)
    }

    private val readTool = FakeTool("weather.now", emptySet(), ToolRisk.READ)
    private val deviceTool = FakeTool("settings", setOf(ToolPermission.SETTINGS_WRITE), ToolRisk.DEVICE)
    private val outwardTool = FakeTool("shell", setOf(ToolPermission.SHELL_EXEC), ToolRisk.OUTWARD)

    // ── Читання не питає ──────────────────────────────────────────────────

    @Test
    fun `read tool runs without asking anyone`() = runTest {
        val outcome = gate(Decision.NEVER).check(readTool, "{}")
        assertTrue(outcome is GateOutcome.Granted)
        assertTrue("читання не мусить питати", asked.isEmpty())
    }

    // ── Дія питає ─────────────────────────────────────────────────────────

    @Test
    fun `device tool asks before running`() = runTest {
        val outcome = gate(Decision.ONCE).check(deviceTool, """{"action":"flashlight"}""")
        assertTrue(outcome is GateOutcome.Granted)
        assertEquals(1, asked.size)
        assertEquals("settings", asked[0].toolId)
        assertEquals(ToolRisk.DEVICE, asked[0].risk)
    }

    @Test
    fun `the ask carries the arguments, not just the tool name`() = runTest {
        gate(Decision.ONCE).check(deviceTool, """{"action":"flashlight","value":true}""")
        assertTrue(
            "питання без аргументів не дає відповісти по суті",
            asked.single().argsPreview.contains("flashlight"),
        )
    }

    @Test
    fun `outward tool asks before running`() = runTest {
        gate(Decision.ONCE).check(outwardTool, "rm -rf /")
        assertEquals(ToolRisk.OUTWARD, asked.single().risk)
    }

    // ── Памʼять рішення ───────────────────────────────────────────────────

    @Test
    fun `ONCE grants but stores nothing`() = runTest {
        val outcome = gate(Decision.ONCE).check(deviceTool, "{}")
        assertTrue(outcome is GateOutcome.Granted)
        assertEquals("«цього разу» не мусить запамʼятовуватись", Decision.ASK, store.decision("settings"))
    }

    @Test
    fun `ALWAYS is stored and stops the next ask`() = runTest {
        gate(Decision.ALWAYS).check(deviceTool, "{}")
        assertEquals(Decision.ALWAYS, store.decision("settings"))
        asked.clear()
        val again = gate(Decision.NEVER).check(deviceTool, "{}")
        assertTrue(again is GateOutcome.Granted)
        assertTrue("після «завжди» питати вдруге не можна", asked.isEmpty())
    }

    @Test
    fun `NEVER is stored and denies the next call without asking`() = runTest {
        val first = gate(Decision.NEVER).check(outwardTool, "{}")
        assertTrue(first is GateOutcome.Denied)
        assertEquals(Decision.NEVER, store.decision("shell"))
        asked.clear()
        val again = gate(Decision.ALWAYS).check(outwardTool, "{}")
        assertTrue(again is GateOutcome.Denied)
        assertTrue("заборонене не мусить питати знову", asked.isEmpty())
    }

    // ── Нема кому спитати ─────────────────────────────────────────────────

    @Test
    fun `risky tool is refused when there is no one to ask`() = runTest {
        val outcome = gate(answer = null).check(outwardTool, "{}")
        assertTrue("без питальника ризиковий мусить ВІДМОВИТИСЬ, а не виконатись", outcome is GateOutcome.Denied)
    }

    @Test
    fun `stored ALWAYS still works headless`() = runTest {
        store.setDecision("shell", Decision.ALWAYS)
        assertTrue(gate(answer = null).check(outwardTool, "{}") is GateOutcome.Granted)
    }

    @Test
    fun `read tool still works headless`() = runTest {
        assertTrue(gate(answer = null).check(readTool, "{}") is GateOutcome.Granted)
    }

    // ── Негативні контролі: тест мусить уміти почервоніти ─────────────────

    @Test
    fun `a tool that never asks would fail this suite`() = runTest {
        // Точний зліпок старих воріт: «завжди дозволено».
        val alwaysGrant = ToolPermissionGate(store, ask = { Decision.ALWAYS })
        gate(Decision.ONCE).check(deviceTool, "{}")
        assertFalse("якби ворота не питали, asked лишився б порожнім", asked.isEmpty())
        assertTrue(alwaysGrant.check(deviceTool, "{}") is GateOutcome.Granted)
    }

    @Test
    fun `denial reason names a screen the operator can reach`() = runTest {
        val outcome = gate(Decision.NEVER).check(outwardTool, "{}") as GateOutcome.Denied
        assertTrue(
            "відмова мусить казати, де змінити рішення",
            outcome.reason.contains("Інструмент"),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private val shell = FakeTool("shell", setOf(ToolPermission.SHELL_EXEC), ToolRisk.OUTWARD, askEveryCall = true)

    @Test
    fun `shell asks on every call even after a stored always`() = runTest {
        store.setDecision("shell", Decision.ALWAYS)
        val outcome = gate(Decision.ONCE).check(shell, """{"cmd":"ls"}""")
        assertTrue(outcome is GateOutcome.Granted)
        assertEquals("збережене «завжди» не може мовчки пустити shell", 1, asked.size)
        assertTrue(asked.single().askEveryCall)
    }

    @Test
    fun `answering always to shell is not remembered`() = runTest {
        gate(Decision.ALWAYS).check(shell, """{"cmd":"ls"}""")
        assertFalse(store.decision("shell") == Decision.ALWAYS)
        val second = gate(null).check(shell, """{"cmd":"ls"}""")
        assertTrue("без питальника shell відмовляє", second is GateOutcome.Denied)
    }

    // ── N2 (G7): «Зупинити все» гасить і власного асистента телефона ──────

    @Test
    fun `stopped state refuses read tool, device tool, and stored ALWAYS tool without asking`() = runTest {
        store.setDecision("settings", Decision.ALWAYS)
        SymbioteStop.stopAll()

        val readOutcome = gate(Decision.ONCE).check(readTool, "{}")
        val deviceOutcome = gate(Decision.ONCE).check(deviceTool, """{"action":"flashlight"}""")
        val outwardOutcome = gate(Decision.ONCE).check(outwardTool, """{"cmd":"ls"}""")

        assertTrue("навіть READ мусить відмовити при stopAll", readOutcome is GateOutcome.Denied)
        assertTrue("навіть збережене ALWAYS мусить відмовити при stopAll", deviceOutcome is GateOutcome.Denied)
        assertTrue("OUTWARD мусить відмовити при stopAll", outwardOutcome is GateOutcome.Denied)
        assertTrue("під час зупинки питальник не викликається", asked.isEmpty())

        SymbioteStop.resume()
        assertTrue("після явного resume READ знову працює", gate(Decision.NEVER).check(readTool, "{}") is GateOutcome.Granted)
        assertTrue("після явного resume збережене ALWAYS знову діє", gate(null).check(deviceTool, "{}") is GateOutcome.Granted)
    }

    @Test
    fun `stopAll while consent prompt is open withdraws prompt and denies`() = runTest {
        val prompter = ToolConsentPrompter()
        val liveGate = ToolPermissionGate(store = store, ask = prompter::request)

        val checkDeferred = async {
            liveGate.check(deviceTool, """{"action":"flashlight","value":true}""")
        }
        testScheduler.runCurrent()
        assertTrue("питання мало зʼявитись у prompter", prompter.pending.value != null)

        SymbioteStop.stopAll()
        testScheduler.runCurrent()

        val outcome = checkDeferred.await()
        assertTrue("відкрите питання має завершитись відмовою при stopAll", outcome is GateOutcome.Denied)
        assertEquals("картка згоди має зникнути", null, prompter.pending.value)
        assertEquals("відкликання через stopAll не мусить записувати NEVER", Decision.ASK, store.decision("settings"))
    }

    // ── N4 (G5): http non-GET is askEveryCall; ALWAYS scoped to host+GET ──

    private val httpTool = FakeTool("http", setOf(ToolPermission.NETWORK), ToolRisk.OUTWARD)

    @Test
    fun `http ALWAYS is scoped to exact host and GET only`() = runTest {
        val firstGet = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://api.example.com/v1/items","method":"GET"}""",
        )
        assertTrue(firstGet is GateOutcome.Granted)
        assertEquals(1, asked.size)
        assertFalse("GET з валідним хостом дозволяє вибрати «завжди»", asked.single().askEveryCall)
        assertEquals("глобальний ключ http не мусить ставати ALWAYS", Decision.ASK, store.decision("http"))

        asked.clear()
        // Same host + GET (including omitted method, which defaults to GET) runs without asking:
        val secondGet = gate(null).check(
            httpTool,
            """{"url":"https://api.example.com/v1/other"}""",
        )
        assertTrue("повторний GET на той самий хост має проходити за збереженим ALWAYS", secondGet is GateOutcome.Granted)
        assertEquals(ConsentPath.ALWAYS, (secondGet as GateOutcome.Granted).via)

        // Different host must still ask (or fail headless):
        val otherHost = gate(null).check(
            httpTool,
            """{"url":"https://evil.example.org/v1/items","method":"GET"}""",
        )
        assertTrue("ALWAYS для одного хоста не дає дозволу іншому хосту", otherHost is GateOutcome.Denied)

        // Subdomain of that host must still ask (no implicit *.domain):
        val subdomain = gate(null).check(
            httpTool,
            """{"url":"https://sub.api.example.com/v1/items","method":"GET"}""",
        )
        assertTrue("ALWAYS не поширюється на піддомени", subdomain is GateOutcome.Denied)
    }

    @Test
    fun `http non-GET is askEveryCall even when GET on same host has ALWAYS`() = runTest {
        // Grant ALWAYS for GET on api.example.com
        gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://api.example.com/v1/items","method":"GET"}""",
        )
        asked.clear()

        // POST to the exact same host must still ask and mark askEveryCall = true
        val postOutcome = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://api.example.com/v1/items","method":"POST","body":"secret"}""",
        )
        assertTrue(postOutcome is GateOutcome.Granted)
        assertEquals("POST мусить спитати попри ALWAYS на GET для цього ж хоста", 1, asked.size)
        assertTrue("не-GET http мусить мати askEveryCall=true", asked.single().askEveryCall)
        assertEquals("навіть якщо відповіли ALWAYS на POST, це лише разовий дозвіл", ConsentPath.ONCE, (postOutcome as GateOutcome.Granted).via)

        // A second POST to the same host must ask again (denied when headless):
        val secondPostHeadless = gate(null).check(
            httpTool,
            """{"url":"https://api.example.com/v1/items","method":"POST","body":"secret2"}""",
        )
        assertTrue("POST ніколи не запамʼятовує ALWAYS", secondPostHeadless is GateOutcome.Denied)
    }

    @Test
    fun `http rejects userinfo, backslash, non-http scheme, and missing host`() = runTest {
        store.setDecision("http:GET:trusted.example.com", Decision.ALWAYS)

        val userInfo = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://trusted.example.com:80@evil.com/path","method":"GET"}""",
        )
        assertTrue("userinfo мусить відхилятись", userInfo is GateOutcome.Denied)
        assertTrue(asked.isEmpty())

        val backslash = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://trusted.example.com\\@evil.com/path","method":"GET"}""",
        )
        assertTrue("зворотний слеш мусить відхилятись", backslash is GateOutcome.Denied)
        assertTrue(asked.isEmpty())

        val ftp = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"ftp://trusted.example.com/file","method":"GET"}""",
        )
        assertTrue("не-http схема мусить відхилятись", ftp is GateOutcome.Denied)

        val missingHost = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"not-a-valid-url","method":"GET"}""",
        )
        assertTrue("невалідний URL мусить відхилятись", missingHost is GateOutcome.Denied)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `http NEVER blocks underscore host, IDN variants, and unescaped query characters without asking`() = runTest {
        // 1. Underscore host
        val firstUnderscore = gate(Decision.NEVER).check(
            httpTool,
            """{"url":"https://my_api.example.com/v1"}""",
        )
        assertTrue(firstUnderscore is GateOutcome.Denied)
        asked.clear()
        val secondUnderscore = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://my_api.example.com/v2"}""",
        )
        assertTrue("NEVER для хоста з підкресленням має блокувати без повторного питання", secondUnderscore is GateOutcome.Denied)
        assertTrue(asked.isEmpty())

        // 2. Unescaped query brackets / spaces
        val firstBlocked = gate(Decision.NEVER).check(
            httpTool,
            """{"url":"https://blocked.example.com/v1"}""",
        )
        assertTrue(firstBlocked is GateOutcome.Denied)
        asked.clear()
        val bracketQuery = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://blocked.example.com/path?filter=[1]&q=a b"}""",
        )
        assertTrue("NEVER не може обходитись через дужки/пробіли в query", bracketQuery is GateOutcome.Denied)
        assertTrue(asked.isEmpty())

        // 3. IDN / punycode normalization
        val unicodeDeny = gate(Decision.NEVER).check(
            httpTool,
            """{"url":"https://приклад.укр/api"}""",
        )
        assertTrue(unicodeDeny is GateOutcome.Denied)
        asked.clear()
        val punycodeAttempt = gate(Decision.ALWAYS).check(
            httpTool,
            """{"url":"https://XN--80AIKIFVH.XN--J1AMH/api"}""",
        )
        assertTrue("NEVER для IDN має блокувати і punycode/uppercase запис", punycodeAttempt is GateOutcome.Denied)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `prompter request aborts with ASK if stopAll and resume happen before await`() = runTest {
        val prompter = ToolConsentPrompter()
        val unregister = prompter.closeable
        try {
            val ask1 = ToolAsk("settings", "desc", ToolRisk.DEVICE, """{"action":"flashlight"}""")
            val ask2 = ToolAsk("settings", "desc", ToolRisk.DEVICE, """{"action":"volume"}""")

            val d1 = async { prompter.request(ask1) }
            testScheduler.runCurrent()
            assertTrue(prompter.pending.value != null)

            // Queue second request while mutex is held by d1
            val d2 = async { prompter.request(ask2) }
            testScheduler.runCurrent()

            // Operator hits Stop Everything and immediately resumes
            SymbioteStop.stopAll()
            SymbioteStop.resume()
            testScheduler.runCurrent()

            assertEquals(Decision.ASK, d1.await())
            assertEquals("другий запит з pre-stop покоління мусить скасуватись", Decision.ASK, d2.await())
            assertEquals(null, prompter.pending.value)
        } finally {
            unregister()
        }
    }

    class FakeTool(
        override val id: String,
        override val permissions: Set<ToolPermission>,
        override val risk: ToolRisk,
        override val askEveryCall: Boolean = false,
    ) : Tool {
        override val description = "Fake tool for tests"
        override val argsSchema = JsonObject(emptyMap())
        override val streaming = false
        override val manifest = FeatureManifest(
            id = id,
            tier = 0,
            displayName = "Fake $id",
        )

        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult = ToolResult.Ok("ok", null, 0L)
    }
}
