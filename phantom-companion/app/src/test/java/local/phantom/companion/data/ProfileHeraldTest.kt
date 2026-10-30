package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHeraldTest {

    private val dir: File = Files.createTempDirectory("herald").toFile()
    private var roster = listOf(person("ana"), person("bob"))
    private var blocked = emptySet<String>()
    private var myName = "Кирило"
    private val wire = mutableListOf<Pair<String, String>>()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(300) { it.toByte() }
    private val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun person(id: String, introduced: Boolean = true, key: String = "dh-$id") = PeerIdentity(
        id = id,
        name = id,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pk-$id",
        dhPubKey = key,
        introducedAtMs = if (introduced) 1L else 0L,
    )

    private var faceProfile: String? = null

    private fun herald(legacy: Long = 0L) = ProfileHerald(
        faceProfileId = { faceProfile },
        dir = dir,
        legacyRev = { legacy },
        people = { roster },
        blocked = { blocked },
        name = { myName },
        handle = { PeerProfile.Field.Cleared },
        send = { id, frame -> wire += id to frame; true },
    )

    private fun withPhoto() = PeerProfile.Body(rev = 0, avatar = PeerProfile.Field.Set(thumb), photo = PeerProfile.Field.Set(jpeg))

    private fun lastTo(id: String) = PeerProfile.decode(wire.last { it.first == id && PeerProfile.isProfile(it.second) }.second)!!

    @Test
    fun `фото їде кожному контакту повним кадром з іменем`() {
        val out = herald().publish(0, withPhoto())!!
        assertEquals(listOf("ana", "bob"), out.sent)
        val got = lastTo("ana")
        assertTrue((got.photo as PeerProfile.Field.Set).value.contentEquals(jpeg))
        assertTrue((got.avatar as PeerProfile.Field.Set).value.contentEquals(thumb))
        assertEquals(PeerProfile.Field.Set("Кирило"), got.name)
    }

    @Test
    fun `заблокованому фото не йде`() {
        blocked = setOf("bob")
        val out = herald().publish(0, withPhoto())!!
        assertEquals(listOf("ana"), out.sent)
        assertEquals(listOf("bob" to "blocked"), out.skipped)
        assertTrue(wire.none { it.first == "bob" })
    }

    @Test
    fun `новий контакт отримує фото без нової зміни, старий — не вдруге`() {
        val h = herald()
        h.publish(0, withPhoto())
        wire.clear()
        roster = roster + person("cat")
        val out = h.sweep()!!
        assertEquals(listOf("cat"), out.sent)
        assertTrue((lastTo("cat").photo as PeerProfile.Field.Set).value.contentEquals(jpeg))
    }

    @Test
    fun `той, чий реєстр нас ще не тримає, отримує знову — відмітки немає`() {
        roster = listOf(person("dan", introduced = false))
        val h = herald()
        h.publish(0, withPhoto())
        wire.clear()
        h.sweep()
        assertEquals(listOf("dan"), wire.map { it.first })
    }

    @Test
    fun `новий ключ — людина перевстановилась і має отримати знову`() {
        val h = herald()
        h.publish(0, withPhoto())
        wire.clear()
        roster = listOf(person("ana", key = "dh-new"), person("bob"))
        h.sweep()
        assertEquals(listOf("ana"), wire.map { it.first })
    }

    @Test
    fun `перейменування не губить фото`() {
        val h = herald()
        h.publish(0, withPhoto())
        myName = "Кирило М"
        h.bump()
        val got = lastTo("ana")
        assertEquals(PeerProfile.Field.Set("Кирило М"), got.name)
        assertTrue(got.photo is PeerProfile.Field.Set)
    }

    @Test
    fun `нова мініатюра без фото прибирає старе фото`() {
        val h = herald()
        h.publish(0, withPhoto())
        h.publish(0, PeerProfile.Body(rev = 0, avatar = PeerProfile.Field.Set(thumb)))
        assertEquals(PeerProfile.Field.Cleared, lastTo("ana").photo)
        assertTrue(h.ownPhotoFile()!!.readBytes().contentEquals(thumb))
    }

    @Test
    fun `rev не опускається нижче старого лічильника і переживає перезапуск`() {
        val first = herald(legacy = 41).publish(0, withPhoto())!!
        assertEquals(42L, first.rev)
        val again = herald(legacy = 0)
        assertEquals(42L, again.rev)
        assertTrue(again.ownPhotoFile()!!.readBytes().contentEquals(jpeg))
        assertEquals(43L, again.bump()!!.rev)
    }

    @Test
    fun `фото понад стелю не зберігається і не їде`() {
        val big = PeerProfile.Body(rev = 0, photo = PeerProfile.Field.Set(ByteArray(PeerProfile.MAX_PHOTO_BYTES + 1)))
        assertNull(herald().publish(0, big))
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `до першої зміни не шле нічого, крім оголошення можливостей`() {
        // Людина профілю не міняла: ні фото, ні статусу, ні біо не їде — лише
        // імʼя з нікою (контакти їх і так знають) та оголошення, що збірка вміє
        // позначки сцени. Без нього контакти не знали б, що їй можна їх слати.
        val h = herald(legacy = 7)
        val out = h.sweep()!!
        assertEquals(listOf("ana", "bob"), out.sent)
        val got = lastTo("ana")
        assertEquals(PeerProfile.SCENE_V1, got.scene)
        assertEquals(PeerProfile.Field.Absent, got.photo)
        assertEquals(PeerProfile.Field.Absent, got.avatar)
        assertEquals(PeerProfile.Field.Absent, got.statusText)
        assertEquals(PeerProfile.Field.Absent, got.bio)
        assertEquals(8L, got.rev)
        // Оголошення їде один раз: другий прохід нікого не тривожить.
        wire.clear()
        assertTrue(h.sweep()!!.sent.isEmpty())
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `кожен кадр профілю несе рівень сцени, а новий контакт його теж отримує`() {
        val h = herald()
        h.publish(0, withPhoto())
        assertEquals(PeerProfile.SCENE_V1, lastTo("ana").scene)
        wire.clear()
        roster = roster + person("cat")
        h.sweep()
        assertEquals(PeerProfile.SCENE_V1, lastTo("cat").scene)
    }

    @Test
    fun `телефон, що ще не оголошував сцену, оголошує її старим контактам один раз після оновлення`() {
        // Стан зі старої збірки: профіль публікували, rev 5, всім доставлено, рівня сцени немає.
        File(dir, "state.json").writeText(
            """{"rev":5,"sent":{"ana:dh-ana":5,"bob:dh-bob":5}}""",
        )
        val h = herald()
        assertEquals(5L, h.rev)
        val out = h.sweep()!!
        assertEquals(6L, out.rev)
        assertEquals(listOf("ana", "bob"), out.sent)
        assertEquals(PeerProfile.SCENE_V1, lastTo("bob").scene)
        // І запам'ятовує: перезапуск не повторює оголошення.
        wire.clear()
        assertTrue(herald().sweep()!!.sent.isEmpty())
    }

    @Test
    fun `після кожного нового rev кожна людина в реєстрі отримує рівно один доповнений кадр`() {
        roster = listOf(person("ana"), person("bob"), person("cat"))
        val h = herald()
        wire.clear()
        val out1 = h.publish(0, withPhoto())!!
        assertEquals(listOf("ana", "bob", "cat"), wire.map { it.first })
        assertTrue(
            wire.all { (_, frame) ->
                val raw = java.util.Base64.getDecoder().decode(frame.removePrefix("PH-PRF:"))
                raw.size == 4 * 1024 && frame.toByteArray(Charsets.UTF_8).size == out1.frameBytes
            },
        )

        wire.clear()
        myName = "Кирило М"
        val out2 = h.bump()!!
        assertEquals(listOf("ana", "bob", "cat"), wire.map { it.first })
        assertEquals(out1.frameBytes, out2.frameBytes)
    }

    @Test
    fun `обличчя за кільцем замінює поля близькому, лишає базове робочому, вирівнює розмір і не світить id чи кільце`() {
        val mirrorDir = Files.createTempDirectory("herald-mirror").toFile()
        var hRef: ProfileHerald? = null
        val mirror = ProfilesMirror(mirrorDir, onChanged = { hRef?.bump() })
        val rings = mapOf(
            "ana" to setOf("close"),
            "bob" to setOf("work"),
        )
        val h = ProfileHerald(
            dir = dir,
            legacyRev = { 0L },
            people = { roster },
            blocked = { blocked },
            name = { myName },
            handle = { PeerProfile.Field.Cleared },
            send = { id, frame -> wire += id to frame; true },
            faceFor = { id -> mirror.faceFor(rings[id].orEmpty()) },
        )
        hRef = h
        mirror.apply(
            """
            {
              "v": 1,
              "activeId": "prof-secret-id-77",
              "profiles": [
                {
                  "id": "prof-secret-id-77",
                  "name": "Мій",
                  "kind": "personal",
                  "faces": {
                    "close": { "name": "Соня", "status": "Вдома", "bio": "Для своїх" }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        wire.clear()
        h.publish(
            0,
            withPhoto().copy(
                statusText = PeerProfile.Field.Set("У справах"),
                bio = PeerProfile.Field.Set("Базове біо"),
            ),
        )
        val anaFrame = wire.last { it.first == "ana" }.second
        val bobFrame = wire.last { it.first == "bob" }.second
        val anaBody = PeerProfile.decode(anaFrame)!!
        val bobBody = PeerProfile.decode(bobFrame)!!

        assertEquals(PeerProfile.Field.Set("Соня"), anaBody.name)
        assertEquals(PeerProfile.Field.Set("Вдома"), anaBody.statusText)
        assertEquals(PeerProfile.Field.Set("Для своїх"), anaBody.bio)
        assertEquals(PeerProfile.Field.Set("Кирило"), bobBody.name)
        assertEquals(PeerProfile.Field.Set("У справах"), bobBody.statusText)
        assertEquals(PeerProfile.Field.Set("Базове біо"), bobBody.bio)

        assertEquals(
            anaFrame.toByteArray(Charsets.UTF_8).size,
            bobFrame.toByteArray(Charsets.UTF_8).size,
        )

        for (frame in listOf(anaFrame, bobFrame)) {
            val rawUtf8 = String(
                java.util.Base64.getDecoder().decode(frame.removePrefix("PH-PRF:")),
                Charsets.UTF_8,
            )
            for (forbidden in listOf("prof-secret-id-77", "personal", "close", "work")) {
                assertTrue("frame leaked $forbidden", !frame.contains(forbidden) && !rawUtf8.contains(forbidden))
            }
        }
    }

    @Test
    fun `різні профілі на телефоні не ділять між собою одне фото і біо`() {
        var activeId: String? = "prof-1"
        val h = ProfileHerald(
            dir = Files.createTempDirectory("herald-multi").toFile(),
            legacyRev = { 0L },
            people = { roster },
            blocked = { blocked },
            name = { myName },
            handle = { PeerProfile.Field.Cleared },
            send = { id, frame -> wire += id to frame; true },
            activeProfileId = { activeId },
        )
        h.publish(0, withPhoto().copy(bio = PeerProfile.Field.Set("Перший")))
        assertTrue(h.ownPhotoFile() != null)
        assertEquals("Перший", h.bio)

        activeId = "prof-2"
        assertNull(h.ownPhotoFile())
        assertEquals("", h.bio)

        activeId = "prof-1"
        assertTrue(h.ownPhotoFile() != null)
        assertEquals("Перший", h.bio)
    }

    @Test
    fun `кожен профіль віддає вебу своє фото, а не фото активного`() {
        var activeId: String? = "prof-1"
        val h = ProfileHerald(
            dir = Files.createTempDirectory("herald-urls").toFile(),
            legacyRev = { 0L },
            people = { roster },
            blocked = { blocked },
            name = { myName },
            handle = { PeerProfile.Field.Cleared },
            send = { id, frame -> wire += id to frame; true },
            activeProfileId = { activeId },
        )
        h.publish(0, withPhoto())
        activeId = "prof-2"

        val urls = h.photoUrls(listOf("prof-1", "prof-2", "../prof-1"), "/webmsgr/_faces/")
        assertEquals(setOf("prof-1"), urls.keys)
        assertTrue(urls.getValue("prof-1"), urls.getValue("prof-1").matches(Regex("/webmsgr/_faces/me-prof-1\\?v=\\d+")))
        assertTrue(h.photoFileOf("me-prof-1")!!.readBytes().contentEquals(jpeg))
        assertNull(h.photoFileOf("me-prof-2"))
        assertNull(h.photoFileOf("me-../p_prof-1"))
        assertNull(h.photoFileOf("prof-1"))
    }

    private val b1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)
    private val b2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 8)

    @Test
    fun `банер — опис у профілі, потім по кадру на фото, і лише раз`() {
        val h = herald()
        h.publishBanner(listOf(ProfileHerald.BannerPhoto(b1, 10, 20), ProfileHerald.BannerPhoto(b2)))!!
        val toAna = wire.filter { it.first == "ana" }.map { it.second }
        assertTrue(PeerProfile.isProfile(toAna[0]))
        val banner = (PeerProfile.decode(toAna[0])!!.banner as PeerProfile.Field.Set).value
        assertEquals(listOf(PeerBanner.hashOf(b1), PeerBanner.hashOf(b2)), banner.items.map { it.hash })
        assertEquals(10 to 20, banner.items[0].fx to banner.items[0].fy)
        assertEquals(listOf(PeerBanner.hashOf(b1), PeerBanner.hashOf(b2)), toAna.drop(1).map { PeerBanner.decode(it)!!.hash })
        // Зміна статусу не жене фото вдруге.
        wire.clear()
        h.publish(0, PeerProfile.Body(rev = 0, statusText = PeerProfile.Field.Set("тут")))
        assertTrue(wire.none { PeerBanner.isBanner(it.second) })
        assertTrue(wire.any { it.first == "ana" })
    }

    @Test
    fun `прибраний банер — порожній тег і жодного фото`() {
        val h = herald()
        h.publishBanner(listOf(ProfileHerald.BannerPhoto(b1)))
        wire.clear()
        h.publishBanner(null)!!
        assertEquals(PeerProfile.Field.Cleared, lastTo("ana").banner)
        assertTrue(wire.none { PeerBanner.isBanner(it.second) })
        assertNull(h.ownBanner())
        assertNull(h.ownBannerFile(PeerBanner.hashOf(b1)))
    }

    @Test
    fun `сім фото або не картинка — банер не змінено`() {
        val h = herald()
        assertNull(h.publishBanner(List(7) { ProfileHerald.BannerPhoto(b1) }))
        assertNull(h.publishBanner(listOf(ProfileHerald.BannerPhoto(byteArrayOf(1, 2, 3)))))
        assertTrue(wire.isEmpty())
    }

    private class Wired(val mirror: ProfilesMirror, val herald: ProfileHerald)

    private fun mirrorJson(active: String, peers: String, workName: String = "Р-тест", workKind: String = "work") =
        """{"v":2,"activeId":"$active","profiles":[
          {"id":"prof-secret-a","kind":"personal","ownName":""},
          {"id":"prof-secret-b","kind":"$workKind","ownName":"$workName"}],
          "peerProfile":{$peers}}"""

    private val bobAtWork = "\"bob\":\"prof-secret-b\""

    private fun wired(rings: Map<String, Set<String>> = emptyMap()): Wired {
        val d = Files.createTempDirectory("herald-v2").toFile()
        var hRef: ProfileHerald? = null
        val mirror = ProfilesMirror(Files.createTempDirectory("herald-v2-mirror").toFile(), onChanged = { hRef?.onMirror(it) })
        val h = ProfileHerald(
            dir = d,
            legacyRev = { 0L },
            people = { roster },
            blocked = { blocked },
            name = { pid -> mirror.profile(pid)?.ownName?.takeIf { it.isNotBlank() } ?: myName },
            handle = { pid -> if (mirror.profile(pid)?.hidesHandle == true) PeerProfile.Field.Cleared else PeerProfile.Field.Set("kyrylo") },
            send = { id, frame -> wire += id to frame; true },
            faceFor = { id -> mirror.ownerOf(id)?.faceFor(rings[id].orEmpty()) },
            activeProfileId = { mirror.liveActive()?.id },
            faceProfileId = { mirror.liveActive()?.id },
            legacyProfileId = { key -> if (key == ProfileHerald.dirKey(mirror.state.value.main?.id)) "native-1" else null },
            ownerOf = { id -> mirror.ownerOf(id)?.id },
        )
        hRef = h
        mirror.apply(mirrorJson("prof-secret-a", bobAtWork))
        return Wired(mirror, h)
    }

    private fun profileFrames() = wire.filter { PeerProfile.isProfile(it.second) }

    @Test
    fun `перемикання активного профілю не шле людям жодного кадру`() {
        val w = wired()
        w.herald.publish(0, withPhoto())
        w.herald.sweep()
        wire.clear()
        w.mirror.apply(mirrorJson("prof-secret-b", bobAtWork))
        assertEquals(emptyList<Pair<String, String>>(), wire)
        assertTrue(w.herald.sweep()!!.sent.isEmpty())
        w.mirror.apply(mirrorJson("prof-secret-a", bobAtWork))
        assertTrue(w.herald.sweep()!!.sent.isEmpty())
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `людина «Роботи» отримує обличчя «Роботи» з прибраним фото, «Особисте» — своє, кадри однакові завдовжки`() {
        val w = wired()
        w.herald.publish(0, withPhoto().copy(bio = PeerProfile.Field.Set("Особисте біо")))
        val ana = lastTo("ana")
        val bob = lastTo("bob")
        assertEquals(PeerProfile.Field.Set("Кирило"), ana.name)
        assertTrue((ana.photo as PeerProfile.Field.Set).value.contentEquals(jpeg))
        assertEquals(PeerProfile.Field.Set("Особисте біо"), ana.bio)
        assertEquals(PeerProfile.Field.Set("Р-тест"), bob.name)
        assertEquals(PeerProfile.Field.Cleared, bob.photo)
        assertEquals(PeerProfile.Field.Cleared, bob.avatar)
        assertEquals(PeerProfile.Field.Cleared, bob.bio)
        assertEquals(PeerProfile.Field.Cleared, bob.statusText)
        assertEquals(PeerProfile.Field.Cleared, bob.banner)
        assertEquals(PeerProfile.Field.Set("kyrylo"), bob.handle)
        val sizes = profileFrames().map { it.second.toByteArray(Charsets.UTF_8).size }.toSet()
        assertEquals(1, sizes.size)
        for ((_, frame) in profileFrames()) {
            val raw = String(java.util.Base64.getDecoder().decode(frame.removePrefix("PH-PRF:")), Charsets.UTF_8)
            for (forbidden in listOf("prof-secret", "personal", "work")) {
                assertTrue("frame leaked $forbidden", !frame.contains(forbidden) && !raw.contains(forbidden))
            }
        }
    }

    @Test
    fun `фото, змінене в «Роботі», їде лише людям «Роботи»`() {
        val w = wired()
        w.herald.sweep()
        w.mirror.apply(mirrorJson("prof-secret-b", bobAtWork))
        wire.clear()
        w.herald.publish(0, withPhoto())
        assertEquals(listOf("bob"), profileFrames().map { it.first })
        assertTrue((lastTo("bob").photo as PeerProfile.Field.Set).value.contentEquals(jpeg))
    }

    @Test
    fun `нове імʼя профілю їде лише його людям`() {
        val w = wired()
        w.herald.sweep()
        wire.clear()
        w.mirror.apply(mirrorJson("prof-secret-a", bobAtWork, workName = "Р-нове"))
        assertEquals(listOf("bob"), profileFrames().map { it.first })
        assertEquals(PeerProfile.Field.Set("Р-нове"), lastTo("bob").name)
    }

    @Test
    fun `перенесена людина отримує обличчя нового профілю один раз і новішим rev, решта — нічого`() {
        val w = wired()
        w.herald.publish(0, withPhoto())
        val bobRev = lastTo("bob").rev
        wire.clear()
        w.mirror.apply(mirrorJson("prof-secret-a", ""))
        assertEquals(listOf("bob"), profileFrames().map { it.first })
        val got = lastTo("bob")
        assertTrue(got.rev > bobRev)
        assertEquals(PeerProfile.Field.Set("Кирило"), got.name)
        assertTrue(got.photo is PeerProfile.Field.Set)
        wire.clear()
        w.herald.sweep()
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `«Без імені» не шле нік`() {
        val w = wired()
        w.herald.sweep()
        w.mirror.apply(mirrorJson("prof-secret-a", bobAtWork, workKind = "unnamed"))
        assertEquals(PeerProfile.Field.Cleared, lastTo("bob").handle)
        assertEquals(PeerProfile.Field.Set("kyrylo"), lastTo("ana").handle)
    }

    @Test
    fun `обличчя за кільцем береться з профілю-власника, а не з активного`() {
        val w = wired(rings = mapOf("bob" to setOf("close")))
        w.mirror.apply(
            """{"v":2,"activeId":"prof-secret-a","profiles":[
              {"id":"prof-secret-a","kind":"personal","faces":{"close":{"name":"Соня"}}},
              {"id":"prof-secret-b","kind":"work","ownName":"Р-тест","faces":{"close":{"name":"Софія Іванівна"}}}],
              "peerProfile":{$bobAtWork}}""",
        )
        w.herald.sweep()
        assertEquals(PeerProfile.Field.Set("Софія Іванівна"), lastTo("bob").name)
    }

    @Test
    fun `банер у кожного профілю свій, і перемикання нікому його не шле`() {
        val w = wired()
        w.mirror.apply(mirrorJson("prof-secret-b", bobAtWork))
        w.herald.publishBanner(listOf(ProfileHerald.BannerPhoto(b1)))
        assertEquals(PeerBanner.hashOf(b1), (lastTo("bob").banner as PeerProfile.Field.Set).value.items.single().hash)
        assertTrue("банер «Роботи» не їде людям «Особистого»", wire.none { it.first == "ana" && PeerBanner.isBanner(it.second) })
        wire.clear()
        w.mirror.apply(mirrorJson("prof-secret-a", bobAtWork))
        assertTrue(wire.isEmpty())
        assertNull("у «Особистого» банера «Роботи» нема", w.herald.ownBanner())
        w.herald.publishBanner(listOf(ProfileHerald.BannerPhoto(b2)))
        assertEquals(setOf("ana"), wire.map { it.first }.toSet())
        assertEquals(PeerBanner.hashOf(b2), (lastTo("ana").banner as PeerProfile.Field.Set).value.items.single().hash)
        w.mirror.apply(mirrorJson("prof-secret-b", bobAtWork))
        assertEquals(listOf(PeerBanner.hashOf(b1)), w.herald.ownBanner()!!.items.map { it.hash })
        assertTrue("фото «Роботи» лишилось", w.herald.ownBannerFile(PeerBanner.hashOf(b1)) != null)
    }

    private fun clipFile(seed: Int): File =
        File(Files.createTempDirectory("herald-clip").toFile(), "clip.mp4").apply { writeBytes(ByteArray(2048) { (it * seed % 251).toByte() }) }

    @Test
    fun `петлю банера людина отримує лише від профілю, якому вона належить`() {
        val w = wired()
        val home = clipFile(3)
        w.herald.publishBanner(listOf(ProfileHerald.BannerPhoto(b1)), video = home)
        assertEquals(home.length(), w.herald.sharedBannerVideo("ana")!!.second.length())
        assertNull("людина «Роботи» не отримує петлю активного «Особистого»", w.herald.sharedBannerVideo("bob"))
        w.mirror.apply(mirrorJson("prof-secret-b", bobAtWork))
        val work = clipFile(7)
        w.herald.publishBanner(listOf(ProfileHerald.BannerPhoto(b2)), video = work)
        w.mirror.apply(mirrorJson("prof-secret-a", bobAtWork))
        assertTrue(w.herald.sharedBannerVideo("bob")!!.second.readBytes().contentEquals(work.readBytes()))
        assertTrue(w.herald.sharedBannerVideo("ana")!!.second.readBytes().contentEquals(home.readBytes()))
    }

    @Test
    fun `головний профіль без жодної зміни лишає поля Absent, як і раніше`() {
        val w = wired()
        w.herald.sweep()
        val ana = lastTo("ana")
        assertEquals(PeerProfile.Field.Absent, ana.photo)
        assertEquals(PeerProfile.Field.Absent, ana.bio)
    }

    @Test
    fun `незнайомому фото банера не женемо, а як познайомились — їдуть`() {
        roster = listOf(person("ana", introduced = false))
        val h = herald()
        h.publishBanner(listOf(ProfileHerald.BannerPhoto(b1)))
        h.sweep()
        assertTrue("опис їде", wire.any { PeerProfile.isProfile(it.second) })
        assertTrue("фото незнайомому не їде", wire.none { PeerBanner.isBanner(it.second) })
        roster = listOf(person("ana"))
        h.sweep()
        assertEquals(1, wire.count { PeerBanner.isBanner(it.second) })
        h.sweep()
        assertEquals("вдруге не женемо", 1, wire.count { PeerBanner.isBanner(it.second) })
    }

    @Test
    fun `фото зі старого ключа переходить головному профілю, новий профіль порожній`() {
        var activeId: String? = "native-1"
        var legacy: String? = null
        val dir = Files.createTempDirectory("herald-legacy").toFile()
        val h = ProfileHerald(
            dir = dir,
            legacyRev = { 0L },
            people = { roster },
            blocked = { blocked },
            name = { myName },
            handle = { PeerProfile.Field.Cleared },
            send = { id, frame -> wire += id to frame; true },
            activeProfileId = { activeId },
            legacyProfileId = { legacy },
        )
        h.publish(0, withPhoto().copy(bio = PeerProfile.Field.Set("Старе")))

        activeId = "web-2"
        assertNull(h.ownPhotoFile())

        activeId = "web-1"
        legacy = "native-1"
        assertTrue(h.ownPhotoFile() != null)
        assertEquals("Старе", h.bio)
    }
}
