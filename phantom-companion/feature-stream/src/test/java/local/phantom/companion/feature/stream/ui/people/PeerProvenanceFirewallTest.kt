package local.phantom.companion.feature.stream.ui.people

import java.io.File
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The strong verification tier is worth exactly as much as the narrowness of
 * the path that can set it.
 *
 * «Ключ доведено» claims nobody could have substituted the key, and the only
 * reason that holds is physics: the photons went from their screen into this
 * phone's lens. A QR read out of a saved PNG, a forwarded screenshot or a file
 * picker travelled through whatever app carried it — exactly as substitutable
 * as a pasted code — and would wear the strong label while proving nothing.
 *
 * So the rule is structural, not a matter of care: the only writer of
 * KeySource.CAMERA is the composable that owns the live CameraX preview, and
 * that file may not learn to read an image. Both halves are checked here
 * because either one alone can be undone by an innocent-looking commit.
 *
 * GATE LAW (shared with the core-design firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks`, as scripts/verify.sh does.
 */
class PeerProvenanceFirewallTest {

    /** Wiring the camera tier is a deliberate act, allowed in these files only. */
    private val cameraWiring = setOf("PeerCardDestination.kt", "ScanDestination.kt")

    /** The handler that carries the camera tier may be handled here only. */
    // 04.10 (profile-hub): PeopleScreen і PeerHandshakeSheet пішли з дерева —
    // мертві, тримали одне одного. Обробник камери тримає лише картка людини.
    private val scanHandlerFiles = setOf("PeerCardScreen.kt")

    /** Anything that turns a stored or received image back into a code. */
    private val imageIntake = listOf(
        "PickVisualMedia",
        "GetContent",
        "ACTION_GET_CONTENT",
        "ACTION_OPEN_DOCUMENT",
        "OpenDocument",
        "BitmapFactory",
        "ImageDecoder",
        "MediaStore",
        "contentResolver",
        "RGBLuminanceSource",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun sources(): List<File> = listOf("app/src/main", "feature-stream/src/main")
        .map { File(repoRoot(), it) }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    @Test
    fun `only one site grants the camera tier`() {
        // Reading the value to choose a sentence is everywhere and harmless;
        // GRANTING it is the act worth pinning. A comparison has == or != in
        // front of it, an assignment does not — comments never count.
        val granters = sources()
            .filter { file ->
                withoutComments(file.readText())
                    .split("KeySource.CAMERA")
                    .dropLast(1)
                    .any { before -> !before.takeLast(40).contains("==") && !before.takeLast(40).contains("!=") }
            }
            .map { it.name }
            .toSet()
        assertEquals(
            "the camera tier may be granted only where the live camera is bound;\n" +
                "granted in: $granters",
            cameraWiring,
            granters,
        )
        val scanDest = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/navigation/ScanDestination.kt",
        ).readText()
        val scanScreen = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/ui/scan/ScanScreen.kt",
        ).readText()
        assertTrue("ScanDestination must host ScanScreen", scanDest.contains("ScanScreen("))
        assertTrue("ScanScreen must bind LiveQrCamera", scanScreen.contains("LiveQrCamera("))
    }

    private fun withoutComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the camera handler reaches nothing but the scan sheet`() {
        val holders = sources()
            .filter { withoutComments(it.readText()).contains("onScanInvite") }
            .map { it.name }
            .toSet() - cameraWiring
        assertEquals(
            "onScanInvite carries the camera tier and leaked into: $holders",
            scanHandlerFiles,
            holders,
        )
        // Holding the handler is only safe while the one thing done with it is
        // handing it to the live camera. Both surfaces must show that verbatim.
        for (name in listOf("PeerCardScreen.kt")) {
            val text = File(
                repoRoot(),
                "feature-stream/src/main/java/local/phantom/companion/feature/stream/" +
                    "ui/people/$name",
            ).readText()
            assertTrue(
                "$name must hand onScanInvite to PeerScanSheet and to nothing else",
                text.contains("PeerScanSheet(") && text.contains("onScanInvite(text)"),
            )
        }
    }

    /**
     * The safety-number handler is the second granter of the camera tier — a
     * match off the other screen sets both the tick and CAMERA — so it is held
     * by one surface and handed to nothing but the live viewfinder, like
     * onScanInvite. The grant itself still lives in PeerCardDestination only.
     */
    @Test
    fun `the safety-number handler reaches nothing but the scan sheet`() {
        val holders = sources()
            .filter { withoutComments(it.readText()).contains("onScanVerify") }
            .map { it.name }
            .toSet() - cameraWiring
        assertEquals(
            "onScanVerify grants the camera tier on a match and leaked into: $holders",
            setOf("PeerCardScreen.kt"),
            holders,
        )
        val card = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/" +
                "ui/people/PeerCardScreen.kt",
        ).readText()
        assertTrue(
            "PeerCardScreen must hand onScanVerify to PeerScanSheet's onVerifyCode and to nothing else",
            card.contains("onVerifyCode = onScanVerify"),
        )
    }

    @Test
    fun `the scan surface cannot read an image`() {
        val scanFiles = listOf(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerScanSheet.kt",
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/scan/LiveQrCamera.kt",
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/scan/QrFrame.kt",
            "app/src/main/java/local/phantom/companion/ui/scan/ScanScreen.kt",
            "app/src/main/java/local/phantom/companion/navigation/ScanDestination.kt",
        ).map { File(repoRoot(), it) }
        scanFiles.forEach { assertTrue("missing ${it.path}", it.isFile) }
        val found = scanFiles.flatMap { f ->
            val text = f.readText()
            imageIntake.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        assertTrue(
            "a QR decoded from a stored image is the paste path wearing the strong\n" +
                "label — scan surfaces must stay live-camera-only. Found: $found",
            found.isEmpty(),
        )
        val liveCamera = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/scan/LiveQrCamera.kt",
        ).readText()
        assertTrue(
            "the scan surface must still bind the live camera",
            liveCamera.contains("bindToLifecycle") && liveCamera.contains("ImageAnalysis"),
        )
        val scanSheet = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/PeerScanSheet.kt",
        ).readText()
        assertTrue("PeerScanSheet must use LiveQrCamera", scanSheet.contains("LiveQrCamera("))
    }

    /**
     * `PeerRoster.upsert` keeps `maxOf(existing.keySource, incoming.keySource)`,
     * so «strongest channel» IS the declaration order of the enum. Until now the
     * only thing protecting that was a comment, and a comment is documentation,
     * not a guard.
     *
     * The realistic break is tidiness, not malice: alphabetise the constants and
     * the next beacon silently erases camera provenance; append a deliberately
     * weak fifth tier at the end and a hand-typed key outranks a scanned one and
     * overwrites it forever. Both are silent, and `isProven` cannot catch either
     * — the damage is done at the upsert, before anything is read.
     */
    @Test
    fun `no channel may outrank the camera, whatever order the enum is written in`() {
        assertEquals(
            "KeySource is declared weakest-first because upsert takes the max; " +
                "reordering it changes what «strongest» means",
            listOf(
                PeerIdentity.KeySource.UNKNOWN,
                PeerIdentity.KeySource.SHARED,
                PeerIdentity.KeySource.REMOTE,
                PeerIdentity.KeySource.CAMERA,
            ),
            PeerIdentity.KeySource.values().toList(),
        )
        val scanned = listOf(
            PeerIdentity(
                id = "a1b2c3d4e5f60718",
                name = "Богдан",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = "KEY-1",
                dhPubKey = "DH-1",
                verifiedAtMs = 500L,
                keySource = PeerIdentity.KeySource.CAMERA,
            ),
        )
        for (other in PeerIdentity.KeySource.values()) {
            val again = PeerRoster.upsert(scanned, scanned.single().copy(keySource = other))
            assertEquals(
                "$other overwrote a key the camera had already taken off a screen",
                PeerIdentity.KeySource.CAMERA,
                again.single().keySource,
            )
        }
    }

    @Test
    fun `no people surface offers a gallery path into the roster`() {
        val dirs = listOf(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people",
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/scan",
            "app/src/main/java/local/phantom/companion/ui/scan",
        )
        val offenders = dirs
            .map { File(repoRoot(), it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { f -> imageIntake.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
                    .toList()
            }
        assertTrue("an image path into pairing: $offenders", offenders.isEmpty())
    }
}
