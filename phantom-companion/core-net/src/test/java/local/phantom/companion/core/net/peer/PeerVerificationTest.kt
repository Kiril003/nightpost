package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Звірка ключа — єдине, що відрізняє «хтось на імʼя Богдан» від Богдана.
 *
 * Тут ламається тихо і назавжди: якщо позначка звірки переживе ЗАМІНУ ключа,
 * підставлений посередині ключ носитиме чужий доказ, і жоден екран цього не
 * покаже. Тому правило пінується числом, а не оком.
 */
class PeerVerificationTest {

    private fun peer(
        id: String = "a1b2c3d4e5f60718",
        key: String = "KEY-1",
        dhKey: String = "DH-1",
        verifiedAt: Long = 0L,
    ) = PeerIdentity(
        id = id,
        name = "Богдан",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = key,
        dhPubKey = dhKey,
        verifiedAtMs = verifiedAt,
    )

    @Test
    fun `nobody is verified until a person says so`() {
        assertFalse(peer().isVerified)
        assertTrue(PeerRoster.markVerified(listOf(peer()), peer().id, 500L).single().isVerified)
    }

    @Test
    fun `withdrawing verification really withdraws it`() {
        val verified = listOf(peer(verifiedAt = 500L))
        assertFalse(PeerRoster.clearVerified(verified, peer().id).single().isVerified)
    }

    @Test
    fun `a changed key drops the verification`() {
        val existing = listOf(peer(key = "KEY-1", verifiedAt = 500L))
        val swapped = PeerRoster.upsert(existing, peer(key = "KEY-2"))
        assertFalse(
            "a substituted key inherited somebody else's proof",
            swapped.single().isVerified,
        )
        assertEquals("KEY-2", swapped.single().pubKey)
    }

    @Test
    fun `re-adding the same key keeps the verification`() {
        val existing = listOf(peer(key = "KEY-1", verifiedAt = 500L))
        val again = PeerRoster.upsert(existing, peer(key = "KEY-1"))
        assertTrue(again.single().isVerified)
        assertEquals(500L, again.single().verifiedAtMs)
    }

    @Test
    fun `verifying one peer leaves the others alone`() {
        val roster = listOf(peer(id = "aaaa"), peer(id = "bbbb"))
        val out = PeerRoster.markVerified(roster, "aaaa", 700L)
        assertTrue(out.first { it.id == "aaaa" }.isVerified)
        assertFalse(out.first { it.id == "bbbb" }.isVerified)
    }

    @Test
    fun `a changed encryption key drops the verification too`() {
        // Шість цифр покривають обидва ключі, тож підміна лише ключа шифрування
        // — це теж ключ, якого ніхто не звіряв уголос.
        val existing = listOf(peer(dhKey = "DH-1", verifiedAt = 500L))
        val swapped = PeerRoster.upsert(existing, peer(dhKey = "DH-2"))
        assertFalse(swapped.single().isVerified)
        assertEquals("DH-2", swapped.single().dhPubKey)
    }

    @Test
    fun `an upsert that claims no encryption key neither erases nor unverifies`() {
        // Спарований ПК і перейменування нічого не кажуть про ключ шифрування;
        // мовчання — не заміна.
        val existing = listOf(peer(dhKey = "DH-1", verifiedAt = 500L))
        val quiet = PeerRoster.upsert(existing, peer(dhKey = ""))
        assertTrue(quiet.single().isVerified)
        assertEquals("DH-1", quiet.single().dhPubKey)
    }

    @Test
    fun `an old row gaining an encryption key must be read aloud again`() {
        val legacy = listOf(peer(dhKey = "", verifiedAt = 500L))
        val upgraded = PeerRoster.upsert(legacy, peer(dhKey = "DH-1"))
        assertFalse(
            "the old six digits never covered the encryption key",
            upgraded.single().isVerified,
        )
    }

    @Test
    fun `a changed key raises an alarm and not just a missing tick`() {
        // Втрата позначки — це не сигнал: її ніхто не бачив. Мить заміни — сигнал,
        // і вона мусить пережити процес, який її помітив.
        val existing = listOf(peer(key = "KEY-1", verifiedAt = 500L))
        val swapped = PeerRoster.upsert(existing, peer(key = "KEY-2"), nowMs = 9_000L)
        assertEquals(9_000L, swapped.single().keyChangedAtMs)
        assertTrue("заміна ключа мусить кричати", swapped.single().keyAlarm)
    }

    @Test
    fun `a swapped encryption key raises it too`() {
        val existing = listOf(peer(dhKey = "DH-1", verifiedAt = 500L))
        val swapped = PeerRoster.upsert(existing, peer(dhKey = "DH-2"), nowMs = 9_000L)
        assertTrue(swapped.single().keyAlarm)
    }

    @Test
    fun `a brand new person is not an alarm`() {
        val fresh = PeerRoster.upsert(emptyList(), peer(), nowMs = 9_000L)
        assertEquals(0L, fresh.single().keyChangedAtMs)
        assertFalse("перше знайомство — не підміна", fresh.single().keyAlarm)
    }

    @Test
    fun `re-adding the same key raises nothing`() {
        val existing = listOf(peer(key = "KEY-1", verifiedAt = 500L))
        val again = PeerRoster.upsert(existing, peer(key = "KEY-1"), nowMs = 9_000L)
        assertFalse(again.single().keyAlarm)
    }

    @Test
    fun `the alarm ends by reading the digits again, not by forgetting`() {
        val existing = listOf(peer(key = "KEY-1", verifiedAt = 500L))
        val swapped = PeerRoster.upsert(existing, peer(key = "KEY-2"), nowMs = 9_000L)
        val settled = PeerRoster.markVerified(swapped, peer().id, 10_000L)
        assertFalse(settled.single().keyAlarm)
        assertEquals(
            "мить заміни лишається в реєстрі, її просто вже звірено",
            9_000L,
            settled.single().keyChangedAtMs,
        )
    }

    @Test
    fun `a second swap after verifying raises it again`() {
        var roster = PeerRoster.upsert(
            listOf(peer(key = "KEY-1", verifiedAt = 500L)),
            peer(key = "KEY-2"),
            nowMs = 9_000L,
        )
        roster = PeerRoster.markVerified(roster, peer().id, 10_000L)
        roster = PeerRoster.upsert(roster, peer(key = "KEY-3"), nowMs = 11_000L)
        assertTrue("друга підміна така сама небезпечна, як перша", roster.single().keyAlarm)
    }

    @Test
    fun `an alarm survives being written down and read back`() {
        // Реєстр на диску — єдине, що переживає перезапуск. Тривога, яка гине
        // з процесом, — це тривога, яку показали, поки ніхто не дивився.
        val swapped = PeerRoster.upsert(
            listOf(peer(key = "KEY-1", verifiedAt = 500L)),
            peer(key = "KEY-2"),
            nowMs = 9_000L,
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val serializer = kotlinx.serialization.builtins.ListSerializer(PeerIdentity.serializer())
        val back = json.decodeFromString(serializer, json.encodeToString(serializer, swapped))
        assertTrue(back.single().keyAlarm)
        assertEquals(9_000L, back.single().keyChangedAtMs)
    }

    @Test
    fun `the spoken code follows the keys and nothing else`() {
        // Два різні ключі мають дати різні шість цифр, інакше звірка нічого не
        // звіряє; ті самі ключі — ті самі цифри на обох телефонах.
        assertEquals(
            PeerInvite.spokenCode("KEY-1", "DH-1"),
            PeerInvite.spokenCode("KEY-1", "DH-1"),
        )
        assertNotEquals(
            PeerInvite.spokenCode("KEY-1", "DH-1"),
            PeerInvite.spokenCode("KEY-2", "DH-1"),
        )
        assertNotEquals(
            PeerInvite.spokenCode("KEY-1", "DH-1"),
            PeerInvite.spokenCode("KEY-1", "DH-2"),
        )
        // Імʼя не входить у код — його обирають, а не доводять.
        val a = PeerInvite.Invite("id", "Богдан", "KEY-1", "DH-1", "10.0.0.2", 1)
        val b = PeerInvite.Invite("id", "хтось інший", "KEY-1", "DH-1", "10.0.0.9", 2)
        assertEquals(
            PeerInvite.spokenCode(a.pubKey, a.dhPubKey),
            PeerInvite.spokenCode(b.pubKey, b.dhPubKey),
        )
    }

    @Test
    fun `a roster written by the previous build still reads back`() {
        // Реєстр на диску — це JSON без поля dhPubKey. Якби він перестав
        // розбиратись, оновлення застосунку стерло б усіх контактів разом із
        // їхніми ключами, і жоден екран не сказав би, чому.
        val old = """
            [{"id":"a1b2c3d4e5f60718","name":"Богдан","kind":"PERSON",
              "pubKey":"KEY-1","verifiedAtMs":500}]
        """.trimIndent()

        val read = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(PeerIdentity.serializer()),
                old,
            )

        assertEquals(1, read.size)
        assertEquals("Богдан", read.single().name)
        assertEquals("", read.single().dhPubKey)
        assertTrue("стара позначка звірки мусить пережити оновлення", read.single().isVerified)
    }

    @Test
    fun `the code is six digits, grouped for reading aloud`() {
        val spoken = PeerInvite.spokenCode("KEY-1", "DH-1")
        assertEquals(7, spoken.length)
        assertEquals(' ', spoken[3])
        assertTrue(spoken.filter { it != ' ' }.all { it.isDigit() })
    }

    /**
     * The residual, pinned by execution so it can never be quietly re-promised.
     *
     * The six digits are a public function of two published keys with no
     * commitment: ~20 bits. A middleman passes the SIGNING key through
     * untouched — every signature still verifies — and grinds only the
     * encryption key it substitutes until the digits land on the victim's.
     * Both phones then show the same six digits and both people tap «збіглись».
     *
     * If this test ever starts failing, the derivation changed and the honest
     * copy that leans on this fact must be re-read; it must never be "fixed" by
     * lengthening the code, which only raises the price of the same attack.
     */
    @Test
    fun `a substituted encryption key can be ground to any six digits`() {
        val signing = "ED25519-REAL"
        val target = PeerInvite.verificationCode(signing, "X25519-REAL")

        var forged: String? = null
        var tries = 0L
        while (tries < 12_000_000L) {
            val candidate = "X25519-MITM-$tries"
            if (PeerInvite.verificationCode(signing, candidate) == target) {
                forged = candidate
                break
            }
            tries++
        }

        assertTrue("шість цифр мали б перебиратись приблизно за мільйон спроб", forged != null)
        assertNotEquals("X25519-REAL", forged)
        assertEquals(target, PeerInvite.verificationCode(signing, forged!!))
    }

    @Test
    fun `matching digits alone never make a key proven`() {
        // Саме це рятує від перебору вище: сильна позначка тримається на каналі,
        // яким прийшов ключ, а не на збігу цифр.
        val pasted = peer(verifiedAt = 500L).copy(keySource = PeerIdentity.KeySource.REMOTE)
        assertTrue(pasted.isVerified)
        assertFalse("звірені цифри на пересланому ключі — не доказ", pasted.isProven)

        val shared = pasted.copy(keySource = PeerIdentity.KeySource.SHARED)
        assertFalse(shared.isProven)

        val scanned = pasted.copy(keySource = PeerIdentity.KeySource.CAMERA)
        assertTrue("ключ з камери плюс збіг цифр — це доказ", scanned.isProven)
    }

    @Test
    fun `a peer verified before provenance existed is not promoted`() {
        // Ряди зі старого реєстру не мають поля взагалі. Мовчання — не камера.
        val old = """
            [{"id":"a1b2c3d4e5f60718","name":"Богдан","kind":"PERSON",
              "pubKey":"KEY-1","dhPubKey":"DH-1","verifiedAtMs":500}]
        """.trimIndent()
        val read = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(PeerIdentity.serializer()),
                old,
            )
        assertEquals(PeerIdentity.KeySource.UNKNOWN, read.single().keySource)
        assertTrue(read.single().isVerified)
        assertFalse("невідомий шлях не стає камерою просто від оновлення", read.single().isProven)
    }

    @Test
    fun `the camera cannot be claimed by the key that arrived`() {
        // Шлях ключа пише той бік екрана, що його прийняв. У PH2 такого поля
        // нема, тож ніщо надіслане не може назвати себе сканованим.
        val invite = PeerInvite.encode(
            PeerInvite.Invite("id", "Богдан", "KEY-1", "DH-1", "10.0.0.2", 5555),
        )
        assertFalse(invite.contains("CAMERA"))
        assertFalse(invite.contains("keySource"))
        val decoded = PeerInvite.decode(invite) as PeerInvite.Decoded.Ph2
        assertEquals(
            PeerIdentity.KeySource.UNKNOWN,
            PeerIdentity(
                id = decoded.invite.peerId,
                name = decoded.invite.name,
                kind = PeerIdentity.Kind.PERSON,
                pubKey = decoded.invite.pubKey,
                dhPubKey = decoded.invite.dhPubKey,
            ).keySource,
        )
    }

    @Test
    fun `scanning a key that was pasted before upgrades it`() {
        val pasted = listOf(
            peer(verifiedAt = 500L).copy(keySource = PeerIdentity.KeySource.REMOTE),
        )
        val met = PeerRoster.upsert(
            pasted,
            peer().copy(keySource = PeerIdentity.KeySource.CAMERA),
        )
        assertEquals(PeerIdentity.KeySource.CAMERA, met.single().keySource)
        assertTrue("той самий ключ, кращий канал — звірка лишається", met.single().isVerified)
    }

    @Test
    fun `a silent upsert never downgrades the channel`() {
        // Маяк, перейменування, оновлення адреси нічого не кажуть про шлях
        // ключа; якби казали, сильна позначка гасла б від першого ж пакета.
        val scanned = listOf(peer().copy(keySource = PeerIdentity.KeySource.CAMERA))
        val beacon = PeerRoster.upsert(scanned, peer())
        assertEquals(PeerIdentity.KeySource.CAMERA, beacon.single().keySource)
    }

    @Test
    fun `a changed key keeps only its own channel`() {
        val scanned = listOf(
            peer(key = "KEY-1", verifiedAt = 500L)
                .copy(keySource = PeerIdentity.KeySource.CAMERA),
        )
        val swapped = PeerRoster.upsert(
            scanned,
            peer(key = "KEY-2").copy(keySource = PeerIdentity.KeySource.REMOTE),
        )
        assertEquals(PeerIdentity.KeySource.REMOTE, swapped.single().keySource)
        assertFalse(swapped.single().isProven)
    }

    @Test
    fun `the channel survives being written down and read back`() {
        val scanned = PeerRoster.upsert(
            emptyList(),
            peer(verifiedAt = 500L).copy(keySource = PeerIdentity.KeySource.CAMERA),
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val serializer = kotlinx.serialization.builtins.ListSerializer(PeerIdentity.serializer())
        val back = json.decodeFromString(serializer, json.encodeToString(serializer, scanned))
        assertTrue("сильна позначка мусить пережити перезапуск", back.single().isProven)
    }
}
