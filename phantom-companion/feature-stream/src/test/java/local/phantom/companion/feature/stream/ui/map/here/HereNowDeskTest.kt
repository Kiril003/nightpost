package local.phantom.companion.feature.stream.ui.map.here

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.truth.CellId
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.H3
import local.phantom.companion.core.truth.Reading
import local.phantom.companion.core.truth.TruthState
import local.phantom.companion.feature.stream.ui.map.AlertSky
import local.phantom.companion.feature.stream.ui.map.AlertState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HereNowDeskTest {

    private val lat = 50.4501
    private val lon = 30.5234
    private val oblast = "Київська область"
    private var clock = 1_800_000_000_000L

    private class FakePort(var area: AreaReply = AreaReply.Verdicts(emptyList())) : HereTruthPort {
        val observed = mutableListOf<Pair<FactKind, Reading>>()
        override suspend fun area(area: String) = this.area
        override suspend fun observe(cell: CellId, kind: FactKind, reading: Reading): ObserveReply {
            observed += kind to reading
            return ObserveReply.Accepted
        }
    }

    private fun sky(active: Boolean, fetched: Long? = clock) =
        AlertSky(oblasts = mapOf(oblast to AlertState(active = active, changedEpochMs = clock)), fetchedAtMs = fetched)

    private fun desk(port: HereTruthPort, sky: MutableStateFlow<AlertSky>, scope: TestScope) =
        HereNowDesk(port, sky, { _, _ -> oblast }, scope.backgroundScope, { clock }, scope.coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!)

    @Test fun `без сервера правди блок каже чесне речення і не дає дій`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort(AreaReply.NotEnabled)
        val d = desk(port, MutableStateFlow(sky(false)), this)
        d.open(lat, lon)
        assertEquals(HereReach.OFF, d.state.value.reach)
        d.report(FactKind.POWER.wire, Reading.ON.wire)
        assertTrue(port.observed.isEmpty())
    }

    @Test fun `сервер відповів вироком — рядок його показує, решта видів порожня`() = runTest(UnconfinedTestDispatcher()) {
        val cell = H3.cell(lat, lon).value
        val body = """{"cell":"${H3.area(CellId(cell))}","verdicts":[
            {"cell":"$cell","object":null,"kind":"utility.power","state":"LIKELY","reading":"on","probability":0.8,
             "supporters":2,"last_support_ms":1,"newest_ms":1,"dissent":false,"contested":false,"viewers_24h":0},
            {"cell":"88aaaaaaaaaaaaa","object":null,"kind":"utility.water","state":"LIKELY","reading":"off","probability":0.8,
             "supporters":2,"last_support_ms":1,"newest_ms":1,"dissent":false,"contested":false,"viewers_24h":0}]}"""
        val d = desk(FakePort(AreaReply.Verdicts(HereWire.parseArea(body)!!)), MutableStateFlow(sky(false)), this)
        d.open(lat, lon)
        val rows = d.state.value.rows.associateBy { it.kind }
        assertEquals(HereReach.ON, d.state.value.reach)
        assertEquals(TruthState.LIKELY, rows[FactKind.POWER]!!.verdict!!.state)
        assertEquals(null, rows[FactKind.WATER]!!.verdict)
        assertEquals(FactKind.CELL, d.state.value.rows.map { it.kind })
    }

    @Test fun `під тривогою свідчення чекає, після відбою йде`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val sky = MutableStateFlow(sky(true))
        val d = desk(port, sky, this)
        d.open(lat, lon)
        assertTrue(d.state.value.skyHolds)
        d.report(FactKind.POWER.wire, Reading.OFF.wire)
        assertTrue("під тривогою не відправляється", port.observed.isEmpty())
        assertTrue(d.state.value.rows.first { it.kind == FactKind.POWER }.held)

        clock += 10 * 60_000L
        sky.value = sky(false)
        assertEquals(listOf(FactKind.POWER to Reading.OFF), port.observed)
        assertTrue(d.state.value.rows.first { it.kind == FactKind.POWER }.sent)
    }

    @Test fun `застаріле за тривогу свідчення не йде`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val sky = MutableStateFlow(sky(true))
        val d = desk(port, sky, this)
        d.open(lat, lon)
        d.report(FactKind.POWER.wire, Reading.OFF.wire)
        clock += FactKind.POWER.halfLifeMs + 1
        sky.value = sky(false)
        assertTrue(port.observed.isEmpty())
    }

    @Test fun `стрічка мовчить — це не тиша`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val d = desk(port, MutableStateFlow(sky(false, fetched = null)), this)
        d.open(lat, lon)
        d.report(FactKind.ROAD.wire, Reading.BLOCKED.wire)
        assertTrue(port.observed.isEmpty())
    }

    @Test fun `тиша — свідчення йде одразу`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val d = desk(port, MutableStateFlow(sky(false)), this)
        d.open(lat, lon)
        d.report(FactKind.SIGNAL.wire, Reading.ON.wire)
        assertEquals(listOf(FactKind.SIGNAL to Reading.ON), port.observed)
    }

    @Test fun `заборонені й обʼєктні види не відправляються`() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val d = desk(port, MutableStateFlow(sky(false)), this)
        d.open(lat, lon)
        d.report("air_defense.position", "on")
        assertTrue(d.state.value.notice is HereNotice.Refused)
        d.report("military", "on")
        d.report("ппо", "on")
        d.report(FactKind.SHELTER_OPEN.wire, Reading.OPEN.wire)
        d.report(FactKind.POWER.wire, Reading.BLOCKED.wire)
        assertTrue(port.observed.isEmpty())
    }

    @Test fun `клієнт — 404 FastAPI означає «не ввімкнено», підписане тіло доходить`() = runTest {
        var posted = ""
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = head.firstOrNull { it.lowercase().startsWith("content-length:") }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                    val (code, reply) = if (head.first().startsWith("POST")) {
                        posted = String(body)
                        "202 Accepted" to """{"verdict":{}}"""
                    } else "404 Not Found" to """{"detail":"Not Found"}"""
                    val bytes = reply.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val http = HereTruthHttp(base) { _, _, _ -> TruthProof("PUB", "a".repeat(32), 7L, "SIG") }
            assertEquals(AreaReply.NotEnabled, http.area("861e8052fffffff"))
            assertEquals(ObserveReply.Accepted, http.observe(CellId("881e6d8a5bfffff"), FactKind.POWER, Reading.ON))
            for (part in listOf("\"pub_key\":\"PUB\"", "\"cell\":\"881e6d8a5bfffff\"", "\"kind\":\"utility.power\"",
                "\"claim\":\"assert\"", "\"reading\":\"on\"", "\"issued_ms\":7")) assertTrue(posted, part in posted)
            assertEquals(ObserveReply.NoKey, HereTruthHttp(base) { _, _, _ -> null }.observe(CellId("881e6d8a5bfffff"), FactKind.POWER, Reading.ON))
        } finally {
            server.close()
        }
        assertEquals(AreaReply.NotEnabled, HereTruthHttp("") { _, _, _ -> null }.area("861e8052fffffff"))
        assertEquals(AreaReply.Unreachable, HereTruthHttp("http://127.0.0.1:1") { _, _, _ -> null }.area("861e8052fffffff"))
    }
}
