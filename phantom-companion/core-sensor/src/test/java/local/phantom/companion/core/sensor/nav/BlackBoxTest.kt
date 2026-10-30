package local.phantom.companion.core.sensor.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ZERO-FAIL — the flight recorder's own acceptance.
 *
 * The recorder is the thing that makes a failed drive *learnable*, so its
 * failure modes matter more than its features. Three of them are load-bearing
 * and all three are pinned here: it must never block the navigation loop, never
 * grow without bound, and never quietly lose data it claims to have recorded.
 *
 * The file-writing tests drive the real writer on the real IO dispatcher against
 * a real temp directory — a recorder verified only against a fake filesystem is
 * a recorder that has never been tested.
 */
class BlackBoxTest {

    /**
     * A record with no commas, emitted only once a queue is known-empty, whose
     * appearance on disk therefore proves everything queued before it is on disk
     * too. Matched whole: the message is the last field of its line, so seeing
     * all of it means the line — and every line before it — was written entire,
     * and no torn read of a file the writer is still appending to can fool it.
     */
    private val BARRIER = "END-OF-QUEUE"

    private lateinit var dir: File
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "blackbox-test-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        scope?.cancel()
        dir.deleteRecursively()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }

    private val gps = GpsSample(
        lat = 50.4501, lon = 30.5234, accuracyM = 8f,
        speedMps = 12.5f, bearingDeg = 91f, altitudeM = 140f, elapsedMs = 1234L,
    )

    private val imu = ImuSample(
        accelX = 0.1f, accelY = 0.2f, accelZ = 9.8f,
        gyroX = 0.01f, gyroY = 0.02f, gyroZ = 0.03f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    private fun snapshot(refused: Long = 0L, rejection: String? = null) = EngineSnapshot(
        trust = LocationTrust.TRUSTED,
        accepted = 10L,
        refused = refused,
        lastRejection = rejection,
        driftResidualM = 4.5,
        driftBudgetM = 200.0,
        driftSpoofed = false,
        kalE = 12.0, kalN = -3.0,
        kalVE = 1.5, kalVN = 0.5,
        kalSpeedMps = 1.58,
        kalUncertaintyM = 9.2,
        deadReckonedM = 0.0,
    )

    /** Wait for a predicate against the log, with a real deadline. */
    private fun awaitLog(box: BlackBox, timeoutMs: Long = 6_000, pred: (String) -> Boolean): String =
        runBlocking {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val text = box.currentFile.takeIf { it.exists() }?.readText().orEmpty()
                if (pred(text)) return@runBlocking text
                delay(50)
            }
            box.currentFile.takeIf { it.exists() }?.readText().orEmpty()
        }

    /**
     * Wait until the writer has taken at least [atLeast] records OUT of the queue.
     *
     * `writtenCount` rises the instant the writer dequeues a record into its
     * batch, so reaching the queue's capacity proves the queue is empty — a
     * signal, not a sleep, and the same one the unwritable-directory test below
     * waits on.
     */
    private fun awaitDequeued(box: BlackBox, atLeast: Long, timeoutMs: Long = 6_000) = runBlocking {
        val drained = withTimeoutOrNull(timeoutMs) {
            while (box.writtenCount < atLeast) delay(5)
            true
        }
        assertTrue(
            "the writer dequeued only ${box.writtenCount} of $atLeast records — it never drained the queue",
            drained == true,
        )
    }

    /** The same wait, against the body stream's own file. */
    private fun awaitImuLog(box: BlackBox, timeoutMs: Long = 6_000, pred: (String) -> Boolean): String =
        runBlocking {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val text = box.imuFile.takeIf { it.exists() }?.readText().orEmpty()
                if (pred(text)) return@runBlocking text
                delay(50)
            }
            box.imuFile.takeIf { it.exists() }?.readText().orEmpty()
        }

    // — 1. it must never block the drive ————————————————————————————————

    @Test
    fun `producers never block, even with no writer and a saturated queue`() {
        // No start() — nothing is draining. This is the worst case: the writer
        // coroutine is dead, wedged, or starved on a thermally-throttled phone.
        // The GPS and IMU callbacks must STILL return instantly. If the queue
        // could apply back-pressure here it would suspend the sensor callbacks,
        // and the recorder would have become the outage it exists to explain.
        val box = BlackBox(dir = dir)
        val calls = BlackBox.QUEUE_CAPACITY * 4

        val elapsedNs = measureNs {
            repeat(calls) { box.recordGps(gps) }
            repeat(calls) { box.recordImu(imu) }
            repeat(calls) { box.recordEngine(null, snapshot()) }
        }

        // The property is structural, not temporal: a bounded DROP_OLDEST
        // channel written with trySend cannot suspend, so every call returns
        // and every record is accounted for even though nothing drains. Swap
        // trySend for send and this deadlocks instead of going slowly.
        //
        // This used to assert wall-clock µs/call, which measured the build
        // machine rather than the code — it went red whenever the suite ran
        // beside a Gradle daemon, and a gate that fails under load is a gate
        // the team learns to ignore.
        //
        // Counted on GPS alone: it is the one path with a 1:1 call→record
        // mapping (IMU decimates, an engine tick fans out into several lines).
        val gpsOnly = BlackBox(dir = dir)
        repeat(calls) { gpsOnly.recordGps(gps) }
        assertEquals(
            "every record must be accepted with nothing draining — a producer blocked or was dropped",
            calls.toLong(),
            gpsOnly.producedCount,
        )

        // Left as a catastrophe guard only: milliseconds per call would mean
        // real I/O or a lock crept onto the sensor callback. Deliberately far
        // above anything scheduling noise can produce.
        val perCallUs = elapsedNs / (calls * 3.0) / 1_000.0
        assertTrue(
            "a recorder call cost ${"%.1f".format(perCallUs)}µs on a saturated queue — " +
                "it runs on the GPS/IMU callback and must not tax the loop it observes",
            perCallUs < 1_000.0,
        )
    }

    // — 2. it must never grow without bound ————————————————————————————

    @Test
    fun `disk is capped and rotates, keeping two generations`() {
        val cap = 256L * 1024
        val box = BlackBox(dir = dir, maxBytes = cap, imuDecimation = 1)
        box.start(newScope())

        // Far more than the cap can hold, fed in bursts the writer must keep up
        // with — this is the shape that broke the first implementation, where an
        // unbounded flush appended a quarter-megabyte in one write and sailed
        // straight past the cap before the next rotation check.
        repeat(40) {
            repeat(1_000) { box.recordGps(gps) }
            runBlocking { delay(25) }
        }
        awaitLog(box) { box.previousFile.exists() }

        assertTrue("expected a rotation to have happened", box.previousFile.exists())
        val total = box.currentFile.length() + box.previousFile.length()
        assertTrue(
            "recorder used $total bytes against a $cap cap — it may not eat the disk",
            total <= cap,
        )
        assertFalse("only two generations may survive", File(dir, "blackbox.2.log").exists())
    }

    @Test
    fun `queue drops the OLDEST under pressure — an autopsy needs the crash, not the history`() {
        // Nothing draining, then overflow the queue by 2x. DROP_OLDEST means the
        // survivors are the most RECENT records. That is the whole design intent:
        // when something must be lost it must be the ancient history, because the
        // seconds around the failure are the only ones anybody will read.
        val box = BlackBox(dir = dir)
        val overflow = BlackBox.QUEUE_CAPACITY * 2
        repeat(overflow) { i -> box.note("seq", "n=$i") }

        box.start(newScope())

        // Wait for the queue to be EMPTY and its last record flushed — not for
        // the first thing the writer happens to emit.
        //
        // This used to wait on `stat,`, which the writer emits on its very first
        // pass, when at most BATCH_MAX_RECORDS of the QUEUE_CAPACITY survivors
        // have reached the file. Measured on this machine: the predicate went
        // true with 512 of 4096 records on disk and `n=$lastSeq` landed 2–48 ms
        // afterwards — under a 50 ms poll, a test that usually passes. It raced
        // the flush the assertion below depends on, and lost on 2026-08-15.
        //
        // The barrier is two stages, and neither is the thing being asserted:
        //  1. writtenCount reaching QUEUE_CAPACITY means the writer has dequeued
        //     every record the queue held, so the queue is now empty and a
        //     record emitted here cannot be dropped by ANY overflow policy —
        //     which is what keeps a broken recorder failing on the assertion
        //     below rather than timing out somewhere less legible.
        //  2. That record is therefore behind every survivor in a FIFO queue
        //     drained by a single writer that appends in order, so the moment
        //     its last field is on disk, every survivor is on disk whole — the
        //     newest, and the ancient ones too if the recorder wrongly kept them.
        awaitDequeued(box, atLeast = BlackBox.QUEUE_CAPACITY.toLong())
        box.note("drained", BARRIER)
        val text = awaitLog(box) { it.contains(BARRIER) }
        assertTrue(
            "the drain barrier never reached the disk — nothing below is provable",
            text.contains(BARRIER),
        )

        val lastSeq = overflow - 1
        assertTrue(
            "the newest record must survive an overflow — it did not",
            text.contains("n=$lastSeq"),
        )
        assertFalse(
            "the oldest record should have been dropped, not retained",
            text.contains("n=0\n"),
        )

        // …and the survivors must be the TAIL of the run, not merely contain its
        // last record: one unbroken window ending at the newest. `contains` alone
        // would still pass on a recorder that kept a scatter, or that kept the
        // ancient half and the newest record by luck.
        val seqs = text.lineSequence()
            .mapNotNull { line ->
                line.substringAfter(",note,seq,n=", "").takeIf { it.isNotEmpty() }?.toIntOrNull()
            }
            .toList()
        assertTrue("no seq records survived at all", seqs.isNotEmpty())
        assertEquals(
            "the survivors must be one unbroken window, not a scatter: ${seqs.first()}..${seqs.last()}",
            seqs.last() - seqs.first() + 1,
            seqs.size,
        )
        assertEquals(
            "the recorder kept the wrong end: it held ${seqs.size} records starting at n=${seqs.first()}, " +
                "which should have been n=${overflow - seqs.size}",
            overflow - seqs.size,
            seqs.first(),
        )
    }

    // — 3. it must admit what it lost ————————————————————————————————

    @Test
    fun `a dropped record is confessed in the stat line`() {
        val box = BlackBox(dir = dir)
        val overflowed = BlackBox.QUEUE_CAPACITY * 2
        repeat(overflowed) { box.note("fill", "x") }

        box.start(newScope())
        val text = awaitLog(box) { it.contains(",stat,") }

        assertEquals(
            "produced must count every record handed to the recorder",
            overflowed.toLong(),
            box.producedCount,
        )
        assertTrue(
            "recorder wrote ${box.writtenCount} of ${box.producedCount} and must not pretend otherwise",
            box.writtenCount < box.producedCount,
        )
        val stat = text.lineSequence().last { it.contains(",stat,") }.split(",")
        // t, "stat", produced, written, cap
        val statProduced = stat[2].toLong()
        val statWritten = stat[3].toLong()
        assertTrue(
            "the stat line must expose the loss: produced=$statProduced written=$statWritten",
            statProduced > statWritten,
        )
    }

    // — shape of the record ————————————————————————————————————————————

    @Test
    fun `gps fix, fused fix, gate verdict and kalman state all reach the disk`() {
        val box = BlackBox(dir = dir)
        box.start(newScope())

        box.recordGps(gps)
        box.recordEngine(
            FusedFix(
                lat = 50.4501, lon = 30.5234, speedMps = 12.5f, bearingDeg = 91f,
                uncertaintyM = 9.2f, trust = LocationTrust.TRUSTED, dead = false,
                altitudeM = 140f, vertical = VerticalContext.GROUND,
            ),
            snapshot(refused = 3L, rejection = "TELEPORT"),
        )

        val text = awaitLog(box) { it.contains(",kal,") }

        assertTrue("raw sky missing", text.contains(",gps,50.450100,30.523400,8.00"))
        assertTrue("fused fix missing", text.contains(",fix,50.450100,30.523400"))
        assertTrue("fused fix must carry its trust", text.contains(",TRUSTED,false,"))
        assertTrue("gate verdict missing", text.contains(",ew,TRUSTED,10,3,"))
        assertTrue("gate's rejection reason missing", text.contains("TELEPORT"))
        assertTrue("kalman interior missing", text.contains(",kal,12.00,-3.00,1.50,0.50,"))
    }

    @Test
    fun `imu is decimated so a whole drive fits under the cap`() {
        val box = BlackBox(dir = dir, imuDecimation = 10)
        box.start(newScope())

        repeat(100) { box.recordImu(imu) }
        // Wait for the writer to settle, not merely for the first line to land.
        val text = awaitImuLog(box) { t -> t.lineSequence().count { ",imu," in it } >= 10 }

        val imuLines = text.lineSequence().count { ",imu," in it }
        assertEquals("100 samples at 1-in-10 must yield 10 lines", 10, imuLines)
    }

    @Test
    fun `the body cannot evict the sky — imu is on its own budget`() {
        // The failure this encodes: on 2026-08-04 a real REB was recorded and
        // then rotated out of existence inside a day, because imu (two thirds of
        // all bytes) shared one budget with the sky and the verdicts and spent it.
        val box = BlackBox(dir = dir, maxBytes = 128L * 1024, imuDecimation = 1)
        box.start(newScope())

        box.note("mark", "the-event")
        // Far more imu than the whole cap can hold.
        repeat(20) {
            repeat(1_000) { box.recordImu(imu) }
            runBlocking { delay(25) }
        }
        awaitImuLog(box) { box.imuPreviousFile.exists() }

        assertTrue("imu must have rotated", box.imuPreviousFile.exists())
        val sky = box.currentFile.takeIf { it.exists() }?.readText().orEmpty()
        assertTrue(
            "an imu flood evicted the one line that mattered — the whole 04.08 failure",
            sky.contains("the-event"),
        )
        assertFalse("imu must not land in the main stream", sky.contains(",imu,"))
    }

    @Test
    fun `a comma in a rejection reason cannot forge a column`() {
        val box = BlackBox(dir = dir)
        box.start(newScope())

        box.recordEngine(null, snapshot(rejection = "TELEPORT,15km,900kmh"))
        val text = awaitLog(box) { it.contains(",ew,") }

        val ew = text.lineSequence().first { it.contains(",ew,") }
        // t,ew,trust,accepted,refused,residual,budget,spoofed,rejection = 9 columns.
        assertEquals(
            "an injected comma must not forge a new column: $ew",
            9,
            ew.split(",").size,
        )
        assertTrue(ew.contains("TELEPORT;15km;900kmh"))
    }

    // — the recorder may never take the drive down with it ————————————

    @Test
    fun `an unwritable directory is survived silently — the recorder may not kill the drive`() {
        // A recorder that throws into the navigation loop because the disk is
        // full has inverted its own purpose.
        val file = File(dir, "not-a-dir").apply { writeText("x") }
        val box = BlackBox(dir = File(file, "nested"))
        box.start(newScope())

        box.recordGps(gps)
        box.recordEngine(null, snapshot())

        // Wait for the SIGNAL, not for a guess. `writtenCount` rises as the
        // writer dequeues a record into its batch — before it ever touches the
        // file — so reaching 2 proves the writer really did pick both records
        // up and try to flush them into a directory that cannot exist. This
        // used to be a flat `delay(1_500)`, which is both 1.5 s of dead CI time
        // and a weaker claim: a hang would have looked identical to a pass.
        runBlocking {
            val drained = withTimeoutOrNull(5_000) {
                while (box.writtenCount < 2) delay(5)
                true
            }
            assertTrue("the writer never dequeued the two records", drained == true)
            // The append attempt itself happens just after the counter rises;
            // give that one flush a moment to fail before checking for a file.
            delay(200)
        }

        assertFalse("no log should exist", box.currentFile.exists())
        // Reaching here at all is the assertion: nothing propagated.
        assertTrue(box.producedCount > 0)
    }

    private inline fun measureNs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }
}
