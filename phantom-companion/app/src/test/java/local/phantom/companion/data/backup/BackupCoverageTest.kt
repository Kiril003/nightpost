package local.phantom.companion.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.memory.Ledger
import local.phantom.companion.core.memory.LedgerStore
import local.phantom.companion.core.memory.Seal
import local.phantom.companion.data.PeerHandleBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupCoverageTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun profile(id: String) = BackupManifest.ProfileEntry(
        id = id, displayName = id, role = "OPERATOR", createdAtEpochMs = 1L, pinHashArgon2 = "",
    )

    @Test
    fun `nicknames, memory, spaces, folders, profiles and profile faces come back on the new phone`() {
        val oldLedger = Ledger(LedgerStore(Seal.fromSecret("old")), device = "phone-old")
        oldLedger.rememberText("Оля пʼє чай без цукру", Ledger.Origin.OWNER_STATEMENT, explicit = true)
        val oldHandles = PeerHandleBook({ null }, {}).apply { remember("aa11", "ola", 5L) }
        val oldWeb = WebBackupStash(tmp.newFolder("old")).apply {
            put("np.profiles.v1", """{"v":1,"profiles":[{"id":"work"}]}""")
            put("np.spaces.v1.work", "{}")
            put("np.chatprofile.v1", """{"v":1,"map":{"aa11":"work"}}""")
            put("phantom.listFolders.v1", "[]")
            put("phantom_ai_key", "sk-x")
        }

        val oldSelf = tmp.newFolder("old_self").apply {
            resolve("p_work").mkdirs()
            resolve("p_work/state.json").writeText("{\"bio\":\"Кава о 9\"}")
            resolve("p_work/photo.img").writeBytes(byteArrayOf(1, 2, 3))
            resolve("cover-work.img").writeBytes(byteArrayOf(4, 5))
            resolve("p_work/state.json.tmp").writeText("half")
            resolve("notes.txt").writeText("x")
        }

        val sent = BackupManifest.collect(
            identity = null,
            claimKey = null,
            declined = setOf("bb22"),
            handles = oldHandles.all(),
            memoryItems = (oldLedger.export()["items"] as JsonArray).map { it.jsonObject },
            web = oldWeb.all(),
            selfFiles = BackupSelfFiles.collect(oldSelf),
        )
        val plan = RestorePlan.of(BackupManifest.decode(sent.encode())!!)!!
        assertEquals(setOf("bb22"), plan.declined)

        val ledger = Ledger(LedgerStore(Seal.fromSecret("new")), device = "phone-new")
        var book: String? = null
        val handles = PeerHandleBook({ book }, { book = it })
        val web = WebBackupStash(tmp.newFolder("new"))
        val self = tmp.newFolder("new_self")
        restoreExtras(plan, handles, ledger, web, self)

        assertEquals(listOf("Оля пʼє чай без цукру"), ledger.known().map { it.text })
        assertEquals(mapOf("aa11" to "ola"), handles.all())
        val taken = Json.parseToJsonElement(web.take()).jsonObject
        assertEquals(setOf("np.profiles.v1", "np.chatprofile.v1", "np.spaces.v1.work", "phantom.listFolders.v1"), taken.keys)
        assertEquals("", web.take())
        assertEquals(taken.keys, web.all().keys)
        assertEquals("{\"bio\":\"Кава о 9\"}", self.resolve("p_work/state.json").readText())
        assertEquals(listOf<Byte>(1, 2, 3), self.resolve("p_work/photo.img").readBytes().toList())
        assertEquals(listOf<Byte>(4, 5), self.resolve("cover-work.img").readBytes().toList())
        assertEquals(setOf("cover-work.img", "p_work", "p_work/photo.img", "p_work/state.json"),
            self.walkTopDown().filter { it != self }.map { it.relativeTo(self).invariantSeparatorsPath }.toSet())
    }

    @Test
    fun `two profiles under one id make the copy unreadable instead of half written`() {
        val m = BackupManifest.collect(identity = null, claimKey = null, profiles = listOf(profile("p1"), profile("p1")))
        assertNull(RestorePlan.of(BackupManifest.decode(m.encode())!!))
    }

    @Test
    fun `the old phone hint shows for a month after a restore`() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(showOldPhoneHint(0L, 100 * day))
        assertTrue(showOldPhoneHint(10 * day, 11 * day))
        assertFalse(showOldPhoneHint(10 * day, 41 * day))
    }
}
