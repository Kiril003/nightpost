package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesMirrorTest {

    private fun tempDir(): File = Files.createTempDirectory("profiles-mirror").toFile()

    @Test
    fun `parses v1 and exposes active profile and faces`() {
        val dir = tempDir()
        val mirror = ProfilesMirror(dir, now = { 1_000L })
        val ok = mirror.apply(
            """
            {
              "v": 1,
              "activeId": "p-work",
              "profiles": [
                {
                  "id": "p-personal",
                  "name": "Особистий",
                  "kind": "personal",
                  "passGroups": [],
                  "quiet": null,
                  "expiresAt": null,
                  "faces": {
                    "close": { "name": "Соня", "status": "Вдома", "bio": "Свої" }
                  }
                },
                {
                  "id": "p-work",
                  "name": "Робота",
                  "kind": "work",
                  "passGroups": ["work", "close"],
                  "quiet": { "from": "22:00", "to": "07:00" },
                  "expiresAt": 9000,
                  "faces": {
                    "work": { "name": "Софія Іванівна", "status": "На нараді" }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(ok)
        val active = mirror.state.value.active
        assertNotNull(active)
        assertEquals("p-work", active!!.id)
        assertEquals("Робота", active.name)
        assertEquals("work", active.kind)
        assertEquals(listOf("work", "close"), active.passGroups)
        assertEquals(ProfilesMirror.Quiet("22:00", "07:00"), active.quiet)
        assertEquals(9_000L, active.expiresAt)
        assertEquals(FaceOverride(name = "Софія Іванівна", status = "На нараді", bio = null), active.faces["work"])
        assertNull(mirror.pendingActive.value)
        assertTrue(File(dir, "mirror.json").isFile)
    }

    @Test
    fun `rejects unknown versions and keeps previous state`() {
        val dir = tempDir()
        val mirror = ProfilesMirror(dir)
        assertTrue(
            mirror.apply(
                """{"v":1,"activeId":"p1","profiles":[{"id":"p1","name":"Основний","kind":"personal"}]}""",
            ),
        )
        assertFalse(
            mirror.apply(
                """{"v":3,"activeId":"p2","profiles":[{"id":"p2","name":"Новий","kind":"personal"}]}""",
            ),
        )
        assertFalse(mirror.apply("""{"activeId":"p2","profiles":[]}"""))
        assertFalse(mirror.apply("""not-json"""))
        assertEquals("p1", mirror.state.value.active?.id)
    }

    @Test
    fun `expired temp profile is dropped on start along with its faces`() {
        val dir = tempDir()
        File(dir, "mirror.json").writeText(
            """
            {
              "v": 1,
              "activeId": "p-main",
              "profiles": [
                {
                  "id": "p-main",
                  "name": "Основний",
                  "kind": "personal",
                  "faces": { "close": { "name": "Соня" } }
                },
                {
                  "id": "p-temp",
                  "name": "Поїздка",
                  "kind": "temp",
                  "expiresAt": 5000,
                  "faces": { "friends": { "name": "Тимчасове", "bio": "В дорозі" } }
                }
              ]
            }
            """.trimIndent(),
        )
        val mirror = ProfilesMirror(dir, now = { 5_000L })
        assertEquals(listOf("p-main"), mirror.state.value.profiles.map { it.id })
        assertEquals("p-main", mirror.state.value.active?.id)
        assertNull(mirror.pendingActive.value)
        assertFalse(File(dir, "mirror.json").readText().contains("p-temp"))
        assertFalse(File(dir, "mirror.json").readText().contains("Тимчасове"))
    }

    @Test
    fun `active expired falls back to personal and sets pendingActive`() {
        val dir = tempDir()
        File(dir, "mirror.json").writeText(
            """
            {
              "v": 1,
              "activeId": "p-temp",
              "profiles": [
                {
                  "id": "p-work",
                  "name": "Робота",
                  "kind": "work"
                },
                {
                  "id": "p-personal",
                  "name": "Особистий",
                  "kind": "personal"
                },
                {
                  "id": "p-temp",
                  "name": "Вечір",
                  "kind": "temp",
                  "expiresAt": 2000,
                  "faces": { "close": { "name": "Тінь" } }
                }
              ]
            }
            """.trimIndent(),
        )
        val mirror = ProfilesMirror(dir, now = { 3_000L })
        assertEquals(listOf("p-work", "p-personal"), mirror.state.value.profiles.map { it.id })
        assertEquals("p-personal", mirror.state.value.active?.id)
        assertEquals("p-personal", mirror.pendingActive.value)
    }

    @Test
    fun `unknown keys including photoId are ignored`() {
        val dir = tempDir()
        val mirror = ProfilesMirror(dir)
        val ok = mirror.apply(
            """
            {
              "v": 1,
              "extraRoot": true,
              "activeId": "p1",
              "profiles": [
                {
                  "id": "p1",
                  "name": "Мій",
                  "kind": "personal",
                  "unknownField": [1, 2, 3],
                  "quiet": { "from": "23:00", "to": "06:00", "tz": "local" },
                  "faces": {
                    "close": {
                      "name": "Соня",
                      "status": "Тут",
                      "bio": "Про себе",
                      "photoId": "photo-99",
                      "futureKey": "ignored"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(ok)
        val face = mirror.state.value.active?.faces?.get("close")
        assertEquals(FaceOverride(name = "Соня", status = "Тут", bio = "Про себе"), face)
    }

    @Test
    fun `a temp profile that expires while running falls back to personal and tells web`() {
        var t = 1_000L
        var changes = 0
        val mirror = ProfilesMirror(tempDir(), now = { t }, onChanged = { changes++ })
        assertTrue(
            mirror.apply(
                """{"v":1,"activeId":"tmp","profiles":[{"id":"me","kind":"personal","name":"Я"},""" +
                    """{"id":"tmp","kind":"temp","name":"Вечірка","expiresAt":5000,"faces":{"close":{"name":"Соня"}}}]}""",
            ),
        )
        assertEquals("Соня", mirror.faceFor(setOf("close"))?.name)
        val before = changes
        t = 6_000L
        assertEquals("me", mirror.liveActive()?.id)
        assertNull(mirror.faceFor(setOf("close")))
        assertEquals("me", mirror.pendingActive.value)
        assertTrue("herald must re-send faces", changes > before)
    }

    @Test
    fun `pending active survives a page that never answers`() = kotlinx.coroutines.runBlocking {
        val dir = tempDir()
        ProfilesMirror(dir, now = { 1_000L }).apply(
            """{"v":1,"activeId":"tmp","profiles":[{"id":"me","kind":"personal"},{"id":"tmp","kind":"temp","expiresAt":2000}]}""",
        )
        val mirror = ProfilesMirror(dir, now = { 3_000L })
        assertEquals("me", mirror.pendingActive.value)
        mirror.deliverPendingActive("me", run = { "missing" }, pause = {})
        assertEquals("me", mirror.pendingActive.value)
        mirror.deliverPendingActive("me", run = { "opened" }, pause = {})
        assertNull(mirror.pendingActive.value)
    }

    @Test
    fun `«Я» перемикає профіль і тоді, коли сторінка піднялась пізніше за всі спроби`() = kotlinx.coroutines.runBlocking {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        val page = """{"v":1,"activeId":"me","profiles":[{"id":"me","kind":"personal"},{"id":"w","kind":"work"}]}"""
        mirror.apply(page)
        mirror.requestActive("w")
        mirror.deliverPendingActive("w", run = { "missing" }, pause = {})
        val stalledAt = mirror.retry.value
        mirror.apply(page)
        assertEquals("w", mirror.pendingActive.value)
        assertEquals(stalledAt + 1, mirror.retry.value)
        mirror.apply(page)
        assertEquals("ще одна відповідь сторінки не подвоює доставку", stalledAt + 1, mirror.retry.value)
        mirror.deliverPendingActive("w", run = { "opened" }, pause = {})
        assertNull(mirror.pendingActive.value)
    }

    @Test
    fun `сторінка вже в потрібному профілі — прохання знімається без другого setActive`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        mirror.requestActive("w")
        mirror.apply("""{"v":1,"activeId":"w","profiles":[{"id":"me","kind":"personal"},{"id":"w","kind":"work"}]}""")
        assertNull(mirror.pendingActive.value)
    }

    @Test
    fun `QR і запрошення несуть імʼя активного профілю, без власного — головне`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        val biz = """{"v":1,"activeId":"b","profiles":[{"id":"me","kind":"personal"},{"id":"b","kind":"business","ownName":"Кавʼярня Зерно"}]}"""
        mirror.apply(biz)
        assertEquals("Кавʼярня Зерно", mirror.inviteName("Олег"))
        mirror.apply(biz.replace("\"activeId\":\"b\"", "\"activeId\":\"me\""))
        assertEquals("Олег", mirror.inviteName("Олег"))
        mirror.apply(biz.replace("Кавʼярня Зерно", "  "))
        assertEquals("Олег", mirror.inviteName("Олег"))
    }

    @Test
    fun `несе акцент і знак профілю, старий міст без них — null`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        assertTrue(
            mirror.apply(
                """{"v":1,"activeId":"w","profiles":[{"id":"w","name":"Робота","kind":"work","accent":"sea","glyph":"work"},{"id":"o","name":"Старий"}]}""",
            ),
        )
        val w = mirror.state.value.profiles.first { it.id == "w" }
        assertEquals("sea" to "work", w.accent to w.glyph)
        assertNull(mirror.state.value.profiles.first { it.id == "o" }.accent)
    }

    @Test
    fun `прохання перемкнути профіль чекає на веб`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        mirror.requestActive("w")
        assertEquals("w", mirror.pendingActive.value)
        mirror.requestActive("  ")
        assertEquals("w", mirror.pendingActive.value)
    }

    @Test
    fun `смуга профілю доходить із моста, старий міст без неї - null`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        assertTrue(
            mirror.apply(
                """{"v":1,"activeId":"s","profiles":[{"id":"s","name":"Школа","kind":"study","bar":[]},{"id":"p","name":"Особисте","bar":["map","wallet"]},{"id":"o","name":"Старий"}]}""",
            ),
        )
        val by = mirror.state.value.profiles.associateBy { it.id }
        assertEquals(emptyList<String>(), by.getValue("s").bar)
        assertEquals(listOf("map", "wallet"), by.getValue("p").bar)
        assertNull(by.getValue("o").bar)
    }

    @Test
    fun `слоти напряму доходять із моста, порядок збережено`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        assertTrue(
            mirror.apply(
                """{"v":1,"activeId":"b","profiles":[{"id":"b","name":"Бізнес","kind":"business","slots":[{"id":"inbox","label":"Вхідні"},{"id":"orders","label":"Замовлення"}]},{"id":"o","name":"Старий"}]}""",
            ),
        )
        val by = mirror.state.value.profiles.associateBy { it.id }
        assertEquals(listOf("inbox" to "Вхідні", "orders" to "Замовлення"), by.getValue("b").slots!!.map { it.id to it.label })
        assertNull(by.getValue("o").slots)
    }

    @Test
    fun `own name follows the active profile and a change of it is reported`() {
        var changed = 0
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L }, onChanged = { changed++ })
        fun push(active: String, workName: String) = mirror.apply(
            """{"v":1,"activeId":"$active","profiles":[
              {"id":"a","kind":"personal","ownName":""},
              {"id":"b","kind":"work","ownName":"$workName"}]}""",
        )
        push("a", "Олег Робочий")
        assertEquals("", mirror.liveActive()?.ownName)
        push("b", "Олег Робочий")
        assertEquals("Олег Робочий", mirror.liveActive()?.ownName)
        val before = changed
        push("b", "Олег Новий")
        assertEquals(before + 1, changed)
    }

    private fun v2(active: String, peers: String = "", chats: String = "", workName: String = "Р-тест", workKind: String = "work") =
        """{"v":2,"activeId":"$active","profiles":[
          {"id":"a","kind":"personal","ownName":""},
          {"id":"b","kind":"$workKind","ownName":"$workName"},
          {"id":"c","kind":"business"}],
          "peerProfile":{$peers},"chatProfile":{$chats}}"""

    @Test
    fun `v2 несе власника розмови з людиною, без запису — головний профіль, v1 — активний`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        assertTrue(mirror.apply(v2("c", peers = "\"bob\":\"b\",\"eve\":\"gone\"")))
        assertEquals("b", mirror.ownerOf("bob")?.id)
        assertEquals("a", mirror.ownerOf("ana")?.id)
        assertEquals("невідомий профіль — головний", "a", mirror.ownerOf("eve")?.id)
        assertTrue(mirror.apply("""{"v":1,"activeId":"c","profiles":[{"id":"a","kind":"personal"},{"id":"c","kind":"business"}]}"""))
        assertEquals("c", mirror.ownerOf("bob")?.id)
    }

    @Test
    fun `v2 переживає перезапуск разом із власниками`() {
        val dir = tempDir()
        ProfilesMirror(dir, now = { 1_000L }).apply(v2("a", peers = "\"bob\":\"b\""))
        assertEquals("b", ProfilesMirror(dir, now = { 1_000L }).ownerOf("bob")?.id)
    }

    @Test
    fun `v2 перемикання активного нікому нічого не каже, зміна обличчя — лише цей профіль, зміна власників — власники`() {
        val got = mutableListOf<ProfilesMirror.Change>()
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L }, onChanged = { got += it })
        mirror.apply(v2("a", peers = "\"bob\":\"b\""))
        got.clear()
        mirror.apply(v2("b", peers = "\"bob\":\"b\""))
        mirror.apply(v2("a", peers = "\"bob\":\"b\""))
        assertEquals(emptyList<ProfilesMirror.Change>(), got)
        mirror.apply(v2("a", peers = "\"bob\":\"b\"", workName = "Р-нове"))
        assertEquals(listOf(ProfilesMirror.Change(legacy = false, faces = setOf("b"))), got)
        got.clear()
        mirror.apply(v2("a", peers = "\"bob\":\"b\"", workName = "Р-нове", workKind = "unnamed"))
        assertEquals("вид змінює нік у кадрі", setOf("b"), got.single().faces)
        got.clear()
        mirror.apply(v2("a", peers = "\"bob\":\"c\"", workName = "Р-нове", workKind = "unnamed"))
        assertEquals(listOf(ProfilesMirror.Change(legacy = false, owners = true)), got)
    }

    @Test
    fun `v1 як і раніше кличе на кожне перемикання`() {
        val got = mutableListOf<ProfilesMirror.Change>()
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L }, onChanged = { got += it })
        mirror.apply("""{"v":1,"activeId":"a","profiles":[{"id":"a","kind":"personal"},{"id":"b","kind":"work"}]}""")
        got.clear()
        mirror.apply("""{"v":1,"activeId":"b","profiles":[{"id":"a","kind":"personal"},{"id":"b","kind":"work"}]}""")
        assertEquals(listOf(ProfilesMirror.Change(legacy = true)), got)
    }

    @Test
    fun `нік ховають лише «Без імені» і «На час»`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        mirror.apply(
            """{"v":2,"activeId":"a","profiles":[{"id":"a","kind":"personal"},{"id":"u","kind":"unnamed"},
              {"id":"t","kind":"temp"},{"id":"w","kind":"work"}]}""",
        )
        assertEquals(listOf(false, true, true, false), listOf("a", "u", "t", "w").map { mirror.profile(it)!!.hidesHandle })
    }

    @Test
    fun `моменти йдуть лише людям активного профілю, без запису — людям головного, v1 — усім`() {
        val mirror = ProfilesMirror(tempDir(), now = { 1_000L })
        assertTrue(mirror.apply(v2("b", peers = "\"bob\":\"b\",\"cat\":\"c\"")))
        assertEquals(listOf(true, false, false), listOf("bob", "cat", "ana").map(mirror::speaksAs))
        assertTrue(mirror.apply(v2("a", peers = "\"bob\":\"b\",\"cat\":\"c\"")))
        assertEquals(listOf(false, false, true), listOf("bob", "cat", "ana").map(mirror::speaksAs))
        assertTrue(mirror.apply("""{"v":1,"activeId":"c","profiles":[{"id":"a","kind":"personal"},{"id":"c","kind":"business"}]}"""))
        assertTrue(mirror.speaksAs("bob"))
    }
}
