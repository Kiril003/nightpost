package local.phantom.companion.data.backup

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCensusTest {

    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "feature-stream").isDirectory }
            ?: error("phantom-companion root not found")

    private class Row(val kind: String, val name: String, val keep: Boolean)

    private val census: List<Row> by lazy {
        javaClass.classLoader!!.getResource("backup-census.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .map { Row(it[0], it[1], it[2] == "keep") }
    }

    private val webList: String by lazy { File(root, "../phantom-os/src/frontend/src/services/backupStash.ts").readText() }

    private fun source(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `every user-made store from the census travels with the copy, caches stay home`() {
        val missing = census.filterNot { row ->
            when (row.kind) {
                "web" -> WebBackupStash.allowed(row.name) == row.keep && webList.contains("'${row.name}'") == row.keep
                "webprefix" -> row.name in WebBackupStash.PREFIXES && webList.contains("'${row.name}'")
                "slot" -> row.name in BackupLocal.SLOTS
                "room" -> row.name in BackupLocal.TABLES
                else -> false
            }
        }
        assertEquals(emptyList<String>(), missing.map { "${it.kind} ${it.name}" })
    }

    @Test
    fun `nothing travels that the census does not name`() {
        val named = census.filter { it.keep }.map { it.name }.toSet()
        assertEquals(emptySet<String>(), WebBackupStash.KEYS - named)
        assertEquals(emptySet<String>(), BackupLocal.SLOTS.keys - named)
        val ts = Regex("BACKED_UP_KEYS[^\\[]*\\[([^\\]]*)]").find(webList)!!.groupValues[1]
        assertEquals(WebBackupStash.KEYS, Regex("'([^']+)'").findAll(ts).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun `slot keys match the stores that write them`() {
        val moments = source("data/MomentsWiring.kt")
        assertTrue(moments.contains("\"phantom_moments\""))
        BackupLocal.SLOTS.getValue("phantom_moments").forEach { assertTrue(it, moments.contains("= \"$it\"")) }
        val requests = source("data/ContactRequestsWiring.kt")
        assertTrue(requests.contains("\"phantom_contact_requests\"") && requests.contains("KEY = \"rows\""))
        val container = source("data/AppContainer.kt")
        assertTrue(container.contains("EncryptedSlot(applicationContext, \"phantom_scheduled\"") && container.contains("slot.read(\"letters\")"))
    }

    private fun manifest(
        slots: Map<String, Map<String, String>> = emptyMap(),
        rows: Map<String, List<String>> = emptyMap(),
        media: List<BackupManifest.MediaLeft> = emptyList(),
    ) = BackupManifest.collect(
        identity = null,
        claimKey = null,
        peers = listOf(PeerIdentity(id = "1122334455667788", name = "Оля", kind = PeerIdentity.Kind.PERSON, pubKey = "cHVi")),
        slots = slots,
        rows = rows,
        media = media,
    )

    @Test
    fun `moments, scheduled letters, requests, reminders and places come back from the copy`() {
        val reminder = BackupLocal.Reminder("r1", "забрати посилку", 10L, 15, null, false, 1L)
        val place = BackupLocal.Place("p1", "Дім", "home", 49.8, 36.2, null, "код 12", 1L, 2L)
        val line = BackupLocal.Line("l1", "пробіжка", "[[1,2],[3,4]]", 1L, 2L)
        val m = manifest(
            slots = mapOf(
                "phantom_moments" to mapOf("book" to "{\"m\":[1]}", "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "x"),
                "phantom_scheduled" to mapOf("letters" to "[{\"id\":\"s1\"}]"),
                "phantom_contact_requests" to mapOf("rows" to "[]"),
                "phantom_wallet" to mapOf("seed" to "never"),
            ),
            rows = mapOf(
                BackupLocal.REMINDERS to BackupLocal.encode(BackupLocal.Reminder.serializer(), listOf(reminder)),
                BackupLocal.PLACES to BackupLocal.encode(BackupLocal.Place.serializer(), listOf(place)),
                BackupLocal.LINES to BackupLocal.encode(BackupLocal.Line.serializer(), listOf(line)),
            ),
            media = listOf(BackupManifest.MediaLeft("chat", "blob1", 2_000_000)),
        )
        val plan = RestorePlan.of(BackupManifest.decode(m.encode())!!)!!.local
        assertEquals(
            mapOf(
                "phantom_moments" to mapOf("book" to "{\"m\":[1]}"),
                "phantom_scheduled" to mapOf("letters" to "[{\"id\":\"s1\"}]"),
                "phantom_contact_requests" to mapOf("rows" to "[]"),
            ),
            plan.slots,
        )
        assertEquals(listOf(reminder), plan.reminders)
        assertEquals(listOf(place), plan.places)
        assertEquals(listOf(line), plan.lines)
        assertEquals("blob1", plan.media.single().name)
        assertEquals(listOf(1, 1, 1), m.local.slotsB64.values.map { it.size })
    }

    @Test
    fun `one broken row leaves the whole copy untouched`() {
        val m = manifest(rows = mapOf(BackupLocal.REMINDERS to listOf("{\"id\":\"r1\"}")))
        assertNull(RestorePlan.of(BackupManifest.decode(m.encode())!!))
    }

    @Test
    fun `a copy from before this section still restores`() {
        val old = JsonObject(Json.parseToJsonElement(manifest().encode()).jsonObject - "local_kv").toString()
        assertNotNull(RestorePlan.of(BackupManifest.decode(old)!!))
    }

    @Test
    fun `photos that do not fit are listed as not restored`() {
        val big = ByteArray(200 * 1024) { 7 }
        val m = BackupManifest.collect(identity = null, claimKey = null, selfFiles = mapOf("photo.img" to big))
        val fitted = AutoSnapshot.fit(m, limit = 100 * 1024)
        assertTrue(fitted.selfFiles.filesB64.isEmpty())
        val left = fitted.local.media.single()
        assertEquals("self" to "photo.img", left.kind to left.name)
        assertTrue(left.bytes in big.size - 4L..big.size.toLong())
    }
}
