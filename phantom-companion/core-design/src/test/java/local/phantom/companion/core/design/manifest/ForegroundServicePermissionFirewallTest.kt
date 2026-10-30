package local.phantom.companion.core.design.manifest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A flavour overlay that removes a foreground-service permission must also
 * remove every service that needs it.
 *
 * On 2026-07-30 the store overlay dropped FOREGROUND_SERVICE_SPECIAL_USE
 * because AutopilotService — one of its two consumers — is removed there. The
 * other consumer, AirAlertService, stayed. It then died with a
 * SecurityException at every start, so the only flavour meant to ship could
 * not raise an air-raid alert, and nothing said so anywhere but logcat.
 *
 * The rule is symmetric and mechanical: after the overlay's removals, no
 * surviving service may declare a `foregroundServiceType` whose permission the
 * overlay took away.
 *
 * Scope is app/src/main + the flavour overlays. Services declared in library
 * modules are not read here — the overlay can only remove what it names, and
 * everything it names lives in these two files.
 *
 * GATE LAW (shared with the other firewalls): this reads other modules'
 * sources as raw file I/O, invisible to Gradle's up-to-date check. Run with
 * `--rerun-tasks` at every gate.
 */
class ForegroundServicePermissionFirewallTest {

    private val permissionFor = mapOf(
        "specialUse" to "FOREGROUND_SERVICE_SPECIAL_USE",
        "microphone" to "FOREGROUND_SERVICE_MICROPHONE",
        "location" to "FOREGROUND_SERVICE_LOCATION",
        "dataSync" to "FOREGROUND_SERVICE_DATA_SYNC",
        "health" to "FOREGROUND_SERVICE_HEALTH",
        "camera" to "FOREGROUND_SERVICE_CAMERA",
        "connectedDevice" to "FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "mediaPlayback" to "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "mediaProjection" to "FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "phoneCall" to "FOREGROUND_SERVICE_PHONE_CALL",
        "remoteMessaging" to "FOREGROUND_SERVICE_REMOTE_MESSAGING",
        "systemExempted" to "FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    )

    private val serviceBlock = Regex("""<service\b[^>]*?/?>""", RegexOption.DOT_MATCHES_ALL)
    private val nameAttr = Regex("""android:name\s*=\s*"([^"]+)"""")
    private val typeAttr = Regex("""android:foregroundServiceType\s*=\s*"([^"]+)"""")
    private val removeAttr = Regex("""tools:node\s*=\s*"remove"""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root with app/ not found")
    }

    private fun qualify(name: String, pkg: String) =
        if (name.startsWith(".")) pkg + name else name

    @Test
    fun `no surviving service needs a permission its flavour removed`() {
        val root = repoRoot()
        val main = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("main manifest missing at $main", main.isFile)
        val mainText = main.readText()
        val pkg = "local.phantom.companion"

        val declared = serviceBlock.findAll(mainText).mapNotNull { m ->
            val block = m.value
            val name = nameAttr.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val types = typeAttr.find(block)?.groupValues?.get(1)?.split('|').orEmpty()
            qualify(name, pkg) to types
        }.toMap()
        assertTrue("no foregroundServiceType found — the parse stopped working",
            declared.values.any { it.isNotEmpty() })

        val flavours = File(root, "app/src").listFiles()
            ?.filter { it.isDirectory && it.name !in setOf("main", "test", "androidTest") }
            ?.mapNotNull { dir -> File(dir, "AndroidManifest.xml").takeIf { it.isFile } }
            .orEmpty()

        val offenders = flavours.flatMap { overlay ->
            val text = overlay.readText()
            val removedPermissions = Regex(
                """<uses-permission[^>]*?android:name\s*=\s*"android\.permission\.([A-Z_]+)"[^>]*?tools:node\s*=\s*"remove"""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(text).map { it.groupValues[1] }.toSet()

            val removedServices = serviceBlock.findAll(text)
                .filter { removeAttr.containsMatchIn(it.value) }
                .mapNotNull { nameAttr.find(it.value)?.groupValues?.get(1) }
                .map { qualify(it, pkg) }
                .toSet()

            declared.filterKeys { it !in removedServices }.flatMap { (service, types) ->
                types.mapNotNull { type ->
                    val needed = permissionFor[type] ?: return@mapNotNull null
                    if (needed in removedPermissions) {
                        "${overlay.parentFile.name}: ${service.substringAfterLast('.')} " +
                            "needs $needed (type=$type), which this flavour removes"
                    } else null
                }
            }
        }

        assertEquals(
            "a service survives in a flavour whose foreground-service permission that " +
                "flavour removed — it will die with a SecurityException at every start, " +
                "silently:\n" + offenders.joinToString("\n") { "    $it" } +
                "\n\nEither keep the permission or remove the service too.",
            emptyList<String>(),
            offenders,
        )
    }
}
