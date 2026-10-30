package local.phantom.companion.core.tool

import android.app.Application
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.jar.JarFile
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.tool.impl.WaitTool
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.GateOutcome
import local.phantom.companion.core.tool.permission.HttpTarget
import local.phantom.companion.core.tool.permission.InMemoryToolPermissionStore
import local.phantom.companion.core.tool.permission.SymbioteStop
import local.phantom.companion.core.tool.permission.ToolPermissionGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Інструменти беруться з байткоду `tool/impl`, а не зі списку в тесті: новий OUTWARD без прапорця червонить тут. */
class OutwardAlwaysRefusedTest {

    @Before
    fun setUp() = SymbioteStop.resume()

    @After
    fun tearDown() = SymbioteStop.resume()

    private fun implClasses(): List<Class<*>> {
        val pkg = WaitTool::class.java.name.substringBeforeLast('.')
        val path = pkg.replace('.', '/')
        val home = File(WaitTool::class.java.protectionDomain.codeSource.location.toURI())
        val names = if (home.isDirectory) {
            File(home, path).listFiles().orEmpty().map { it.name }
        } else {
            JarFile(home).use { jar ->
                jar.entries().toList().map { it.name }
                    .filter { it.startsWith("$path/") && '/' !in it.removePrefix("$path/") }
                    .map { it.substringAfterLast('/') }
            }
        }
        return names.filter { it.endsWith(".class") }
            .map { Class.forName("$pkg.${it.removeSuffix(".class")}", false, WaitTool::class.java.classLoader) }
            .filter {
                Tool::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) &&
                    !it.isAnonymousClass && !it.isLocalClass && !it.isSynthetic
            }
    }

    private val unsafe: Any by lazy {
        Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    }

    private fun stand(type: Class<*>): Any? = when {
        type == java.lang.Boolean.TYPE -> false
        type == Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        type == java.lang.Double.TYPE -> 0.0
        type == java.lang.Float.TYPE -> 0f
        type == String::class.java -> ""
        Context::class.java.isAssignableFrom(type) -> Application()
        type == HttpClient::class.java -> HttpClient(MockEngine { respondOk() })
        type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ ->
            if (m.returnType.isPrimitive && m.returnType != Void.TYPE) stand(m.returnType) else null
        }
        else -> unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    }

    private fun build(c: Class<*>): Tool {
        val ctor = c.declaredConstructors.filterNot { it.isSynthetic }.maxBy { it.parameterCount }
        ctor.isAccessible = true
        return ctor.newInstance(*ctor.parameterTypes.map(::stand).toTypedArray()) as Tool
    }

    private fun realTools(): Pair<List<Tool>, List<String>> {
        val built = implClasses().associate { it.simpleName to runCatching { build(it) } }
        return built.values.mapNotNull { it.getOrNull() } to
            built.filterValues { it.isFailure }.map { (name, r) -> "$name: ${r.exceptionOrNull()}" }
    }

    // ENGINE §2.7: the final tap happens in the dialer, SMS app, map or the draft itself.
    private val composers = setOf(
        "calendar.add", "nav.add_stop", "nav.exfil", "nav.retrace", "nav.start", "nav.stop",
        "people.draft", "phone.dial", "phone.sms",
    )

    private val asking = setOf(
        "calendar.remove", "fence.set", "file", "http", "map.prefetch", "medevac.handover", "memory.forget",
        "place.remove", "reminder.remove", "shell", "task.remove", "track.export", "world.report",
    )

    @Test
    fun `every outward tool outside the system composers asks on every call`() {
        val (tools, broken) = realTools()
        assertTrue("знайдено замало інструментів у tool/impl: ${tools.size}", tools.size + broken.size >= 60)
        val outward = tools.filter { it.risk == ToolRisk.OUTWARD }
        assertTrue("OUTWARD-інструментів замало: ${outward.map { it.id }}", outward.size >= 15)
        val loose = outward.filterNot { it.askEveryCall }.map { it.id }.toSet()
        assertTrue("OUTWARD без питання щоразу — лише системні композитори й чернетки: ${loose.sorted()}", loose == composers)
        assertEquals("питають щоразу", asking, tools.filter { it.askEveryCall }.map { it.id }.toSet())
        assertTrue("інструменти, яких аудит не зміг зібрати: $broken", broken.isEmpty())
    }

    @Test
    fun `an Always saved for an outward tool is never honoured`() = runTest {
        val outward = realTools().first.filter { it.risk == ToolRisk.OUTWARD && it.id !in composers }
        assertTrue(outward.isNotEmpty())
        for (tool in outward) {
            val preview = if (tool.id == "http") """{"url":"https://example.com/a","method":"GET"}""" else "{}"
            val store = InMemoryToolPermissionStore()
            store.setDecision(tool.id, Decision.ALWAYS)
            if (tool.id == "http") HttpTarget.parseFromArgsPreview(preview).scopedGrantKey?.let { store.setDecision(it, Decision.ALWAYS) }

            var asked = 0
            val gate = ToolPermissionGate(store, ask = { asked++; Decision.ALWAYS }, isStopped = { false })
            assertTrue(gate.check(tool, preview) is GateOutcome.Granted)
            assertEquals("«${tool.id}»: збережене «Завжди» пропустило без питання", 1, asked)
            gate.check(tool, preview)
            assertEquals("«${tool.id}»: відповідь «Завжди» запамʼяталась", 2, asked)

            val headless = ToolPermissionGate(store, ask = null, isStopped = { false }).check(tool, preview)
            assertTrue("«${tool.id}» виконався б без людини на збереженому «Завжди»", headless is GateOutcome.Denied)
        }
    }
}
