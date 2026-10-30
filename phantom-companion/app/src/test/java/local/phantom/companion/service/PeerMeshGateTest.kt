package local.phantom.companion.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh service takes a MulticastLock with no timeout and pumps every 5 s.
 * On a fresh install — empty roster, «Люди» never opened — that is a phone
 * waking for every broadcast frame on the crew's hotspot, for nobody.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerMeshGateTest {

    private fun person(id: String) = PeerIdentity(
        id = id,
        name = id,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pub-$id",
        dhPubKey = "dh-$id",
    )

    private fun pairedPc(id: String) = PeerIdentity(
        id = id,
        name = id,
        kind = PeerIdentity.Kind.DEVICE,
        pubKey = "pub-$id",
    )

    // ── The decision ─────────────────────────────────────────────────────

    @Test
    fun `an empty roster with People closed does not run the mesh`() {
        assertTrue(!PeerMeshGate.shouldRun(emptyList(), peopleOnScreen = false))
    }

    @Test
    fun `one person in the roster runs the mesh`() {
        assertTrue(PeerMeshGate.shouldRun(listOf(person("a")), peopleOnScreen = false))
    }

    @Test
    fun `a paired PC alone is not somebody to talk to`() {
        // It has no exchange key, so the mesh could never send it anything —
        // paying a multicast lock for it buys nothing.
        assertTrue(!PeerMeshGate.shouldRun(listOf(pairedPc("pc")), peopleOnScreen = false))
    }

    @Test
    fun `an open People screen runs the mesh with nobody in the roster`() {
        // This is where the invite code is shown, and the code carries the
        // port the mesh binds — closed, it would lead nowhere.
        assertTrue(PeerMeshGate.shouldRun(emptyList(), peopleOnScreen = true))
    }

    // ── The wiring ───────────────────────────────────────────────────────

    @Test
    fun `a fresh install never starts the service and stops what boot restarted`() = runTest {
        val roster = MutableStateFlow(emptyList<PeerIdentity>())
        val onScreen = MutableStateFlow(false)
        var starts = 0
        var stops = 0

        val job = launch {
            PeerMeshGate.keepInStep(roster, onScreen, start = { starts++ }, stop = { stops++ })
        }
        advanceUntilIdle()

        assertEquals("the mesh must not run for an empty roster", 0, starts)
        assertEquals("START_STICKY can revive it — boot has to knock it down", 1, stops)
        job.cancel()
    }

    @Test
    fun `the first peer starts it and losing the last one stops it`() = runTest {
        val roster = MutableStateFlow(emptyList<PeerIdentity>())
        val onScreen = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        val job = launch {
            PeerMeshGate.keepInStep(
                roster, onScreen,
                start = { calls += "start" },
                stop = { calls += "stop" },
            )
        }
        advanceUntilIdle()

        roster.value = listOf(person("a"))
        advanceUntilIdle()
        roster.value = emptyList()
        advanceUntilIdle()

        assertEquals(listOf("stop", "start", "stop"), calls)
        job.cancel()
    }

    @Test
    fun `opening People starts it and leaving stops it again`() = runTest {
        val roster = MutableStateFlow(emptyList<PeerIdentity>())
        val onScreen = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        val job = launch {
            PeerMeshGate.keepInStep(
                roster, onScreen,
                start = { calls += "start" },
                stop = { calls += "stop" },
            )
        }
        advanceUntilIdle()

        onScreen.value = true
        advanceUntilIdle()
        onScreen.value = false
        advanceUntilIdle()

        assertEquals(listOf("stop", "start", "stop"), calls)
        job.cancel()
    }

    @Test
    fun `leaving People does not stop a mesh that has peers`() = runTest {
        val roster = MutableStateFlow(listOf(person("a")))
        val onScreen = MutableStateFlow(true)
        val calls = mutableListOf<String>()

        val job = launch {
            PeerMeshGate.keepInStep(
                roster, onScreen,
                start = { calls += "start" },
                stop = { calls += "stop" },
            )
        }
        advanceUntilIdle()

        onScreen.value = false
        advanceUntilIdle()

        assertEquals(listOf("start"), calls)
        job.cancel()
    }

    @Test
    fun `the screen latch survives an overlapping enter and leave`() = runTest {
        val roster = MutableStateFlow(emptyList<PeerIdentity>())
        val calls = mutableListOf<String>()

        val job = launch {
            PeerMeshGate.keepInStep(
                roster,
                start = { calls += "start" },
                stop = { calls += "stop" },
            )
        }
        advanceUntilIdle()

        PeerMeshGate.peopleEntered()
        advanceUntilIdle()
        // A second surface arrives before the first is torn down.
        PeerMeshGate.peopleEntered()
        PeerMeshGate.peopleLeft()
        advanceUntilIdle()
        assertEquals("one leave must not unlatch two enters", listOf("stop", "start"), calls)

        PeerMeshGate.peopleLeft()
        advanceUntilIdle()
        assertEquals(listOf("stop", "start", "stop"), calls)
        job.cancel()
    }

    // ── Self-heal ────────────────────────────────────────────────────────

    @Test
    fun `a start the system refused in background is tried again when the app comes to the front`() = runTest {
        // Android 12+ refuses startForegroundService to a process woken in background
        // (relay worker, alarm). ensureRunning swallows it; the roster never changes
        // again, so without a retry the outbox sat QUEUED until the process died.
        val roster = MutableStateFlow(listOf(person("a")))
        val front = MutableSharedFlow<Unit>()
        var running = false
        var starts = 0

        val job = launch {
            PeerMeshGate.keepInStep(
                roster, MutableStateFlow(false), front,
                running = { running },
                start = { starts++ },
                stop = {},
            )
        }
        advanceUntilIdle()
        assertEquals(1, starts)

        front.emit(Unit)
        advanceUntilIdle()
        assertEquals("refused in background, started again in front", 2, starts)

        running = true
        front.emit(Unit)
        advanceUntilIdle()
        assertEquals("a running mesh is not started twice", 2, starts)
        job.cancel()
    }

    @Test
    fun `coming to the front does not start a mesh nobody needs`() = runTest {
        val front = MutableSharedFlow<Unit>()
        val calls = mutableListOf<String>()

        val job = launch {
            PeerMeshGate.keepInStep(
                MutableStateFlow(emptyList()), MutableStateFlow(false), front,
                running = { false },
                start = { calls += "start" },
                stop = { calls += "stop" },
            )
        }
        advanceUntilIdle()
        front.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("stop"), calls)
        job.cancel()
    }

    private val root: java.io.File = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
        .firstOrNull { java.io.File(it, "feature-stream").isDirectory }
        ?: error("repo root not found")

    private fun src(rel: String) = java.io.File(root, "app/src/main/java/local/phantom/companion/$rel").readText()

    @Test
    fun `the gate hears the front screen the boot and a tap on the clock`() {
        val onStart = src("MainActivity.kt").substringAfter("override fun onStart()").substringBefore("override fun ")
        assertTrue("coming to the front never knocks again", "PeerMeshGate.foregrounded()" in onStart)
        val boot = src("data/ContainerBoot.kt").substringAfter("PeerMeshGate.keepInStep(").substringBefore("start = {")
        assertTrue("the gate cannot see a refused service", "running = { mesh.running }" in boot)
        val flush = src("ui/messenger/WebMessengerBridge.kt").substringAfter("seg.getOrNull(2) == \"flush\"").substringBefore("ok(")
        assertTrue("a tap on the clock with no service does nothing", "if (!mesh.pollRelayNow()) local.phantom.companion.service.PeerMeshGate.foregrounded()" in flush)
    }
}
