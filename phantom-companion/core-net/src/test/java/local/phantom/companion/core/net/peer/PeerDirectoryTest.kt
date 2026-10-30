package local.phantom.companion.core.net.peer

import java.security.MessageDigest
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.bytesToHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що довідник має право сказати телефонові, і скільки способів у нього збрехати.
 *
 * Кожна відмова тут — це вигаданий сервер, який намагається підсунути чужий
 * ключ під потрібним іменем: підписаний не тим ключем, з адресою всередині,
 * протухлий, з ключем не тим, який очікували. Жодна з цих перевірок не
 * покладається на сервер — усі рахуються з самого запису.
 */
class PeerDirectoryTest {

    private class Person(
        name: String,
        val signing: Ed25519.Keypair = Ed25519.generateKeypair(),
        dh: X25519.Keypair = dhKeypair(),
    ) {
        val pub = b64(signing.publicKey)
        val card = PeerInvite.Invite(
            peerId = PeerIntro.fingerprint(pub),
            name = name,
            pubKey = pub,
            dhPubKey = b64(dh.publicKey),
            host = "",
            port = 0,
        )
    }

    private val hour = 3_600_000L

    private fun record(
        who: Person,
        handle: String,
        expSeconds: Long,
        signWith: ByteArray = who.signing.privateKey,
        card: PeerInvite.Invite = who.card,
        pubKey: String = "",
        // Не через addressless(): саме адреса всередині коду і є те, що
        // перевіряє один із тестів нижче, а addressless() її зрізав би.
        code: String = PeerInvite.encode(PeerDirectory.addressless(card)),
    ): PeerDirectory.Record {
        return PeerDirectory.Record(
            handle = handle,
            invite = code,
            sig = PeerDirectory.sign(
                PeerDirectory.publishPayload(PeerDirectory.handleHash(handle), code, expSeconds),
                signWith,
            ),
            expSeconds = expSeconds,
            pubKey = pubKey,
        )
    }

    @Test
    fun `a record signed by the key inside it is trusted and carries that key back`() {
        val marta = Person("Марта")
        val verdict = PeerDirectory.verify(record(marta, "marta_k", 2_000L), nowMs = 1_000_000L)

        val trusted = verdict as PeerDirectory.Verdict.Trusted
        assertEquals(marta.card.pubKey, trusted.invite.pubKey)
        assertEquals(marta.card.dhPubKey, trusted.invite.dhPubKey)
        assertEquals(marta.card.peerId, trusted.invite.peerId)
        assertEquals("", trusted.invite.host)
        assertEquals(0, trusted.invite.port)
    }

    @Test
    fun `a record signed by somebody else's key is refused`() {
        val marta = Person("Марта")
        val stranger = Person("Хтось")
        val forged = record(marta, "marta_k", 2_000L, signWith = stranger.signing.privateKey)

        assertEquals(
            PeerDirectory.Verdict.BadSignature,
            PeerDirectory.verify(forged, nowMs = 1_000_000L),
        )
    }

    @Test
    fun `an invite carrying an address is refused even when the signature holds`() {
        val marta = Person("Марта")
        val addressed = marta.card.copy(host = "192.168.1.9", port = 47_331)
        // Підписано правильно і саме тим ключем — відмова тут не про підпис.
        val withAddress =
            record(marta, "marta_k", 2_000L, code = PeerInvite.encode(addressed))

        assertEquals(
            PeerDirectory.Verdict.HasAddress,
            PeerDirectory.verify(withAddress, nowMs = 1_000_000L),
        )
    }

    @Test
    fun `an expired record is refused before its signature is even looked at`() {
        val marta = Person("Марта")
        val stale = record(marta, "marta_k", 2_000L)

        assertEquals(
            PeerDirectory.Verdict.Expired,
            PeerDirectory.verify(stale, nowMs = 2_000L * 1_000L + hour),
        )
        assertTrue(
            PeerDirectory.verify(stale, nowMs = 1_000L * 1_000L) is PeerDirectory.Verdict.Trusted,
        )
    }

    @Test
    fun `a record whose key is not the key we expected is refused`() {
        val marta = Person("Марта")
        val stranger = Person("Хтось")
        val swapped = record(marta, "marta_k", 2_000L, pubKey = stranger.card.pubKey)

        assertEquals(
            PeerDirectory.Verdict.WrongKey,
            PeerDirectory.verify(swapped, nowMs = 1_000_000L),
        )
        assertTrue(
            PeerDirectory.verify(
                record(marta, "marta_k", 2_000L, pubKey = marta.card.pubKey),
                nowMs = 1_000_000L,
            ) is PeerDirectory.Verdict.Trusted,
        )
    }

    @Test
    fun `a signature in the url alphabet and a key in the plain one both read`() {
        val marta = Person("Марта")
        val ok = record(marta, "marta_k", 2_000L)
        // Телефон шле ключ звичайною абеткою; підпис їде base64url. Обидва
        // алфавіти в одному записі — це нормальний робочий випадок, не збій.
        assertTrue(ok.sig.none { it == '+' || it == '/' })
        assertTrue(PeerDirectory.verify(ok, nowMs = 1_000_000L) is PeerDirectory.Verdict.Trusted)
    }

    @Test
    fun `only a lowercase latin handle of the right length is a handle`() {
        assertEquals("marta_k", PeerDirectory.normalise("@Marta_K"))
        assertEquals("marta_k", PeerDirectory.normalise("  marta_k  "))
        assertNull(PeerDirectory.normalise("марта"))
        assertNull(PeerDirectory.normalise("abcd"))
        assertNull(PeerDirectory.normalise("a".repeat(33)))
        assertNull(PeerDirectory.normalise("marta k"))
        assertNull(PeerDirectory.normalise(null))
    }

    @Test
    fun `the server is asked by digest, never by name`() {
        val expected = bytesToHex(
            MessageDigest.getInstance("SHA-256").digest("marta_k".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(expected, PeerDirectory.handleHash("marta_k"))
        assertEquals(expected, PeerDirectory.handleHash("MARTA_K"))
        assertEquals(64, expected.length)
    }

    /**
     * Речення, під яким стоїть підпис, не має містити імені — і це видно з
     * САМОГО речення, а не з опису ручки.
     *
     * До v2 підпис накривав імʼя, тож імʼя мусило приїхати на сервер, щоб
     * підпис було з чим звірити: обіцянка «сервер знає лише SHA-256» була
     * правдою для пошуку й неправдою для публікації.
     */
    @Test
    fun `the signed sentence carries the digest and never the name`() {
        val digest = PeerDirectory.handleHash("marta_k")
        val publish = String(
            PeerDirectory.publishPayload(digest, "PH2:x:aaaaaa", 2_000L),
            Charsets.UTF_8,
        )
        val withdraw = String(PeerDirectory.withdrawPayload(digest, 2_000L), Charsets.UTF_8)

        assertEquals("PH-DIR-v2|$digest|PH2:x:aaaaaa|2000", publish)
        assertEquals("PH-DIR-del-v2|$digest|2000", withdraw)
        assertFalse("імені в реченні публікації немає", publish.contains("marta_k"))
        assertFalse("імені в реченні відклику немає", withdraw.contains("marta_k"))

        // Імʼя, підсунуте замість адреси, дало б цілком дійсний підпис під
        // неправильним реченням — і побачити це можна було б лише на живому
        // сервері. Тому це падіння, а не тихий рядок.
        for (notAnAddress in listOf("marta_k", "", digest.dropLast(1), digest.uppercase())) {
            assertThrows(IllegalArgumentException::class.java) {
                PeerDirectory.publishPayload(notAnAddress, "PH2:x:aaaaaa", 2_000L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                PeerDirectory.withdrawPayload(notAnAddress, 2_000L)
            }
        }
    }

    /**
     * Мутація, а не віра в рядок «v2»: запис, підписаний старим реченням,
     * мусить діставати ту саму відмову, що й підробка. Двох чинних форм немає.
     */
    @Test
    fun `a record signed with the old v1 sentence is refused`() {
        val marta = Person("Марта")
        val code = PeerInvite.encode(PeerDirectory.addressless(marta.card))
        val old = "PH-DIR-v1|marta_k|$code|2000".toByteArray(Charsets.UTF_8)
        val v1 = PeerDirectory.Record(
            handle = "marta_k",
            invite = code,
            sig = PeerDirectory.sign(old, marta.signing.privateKey),
            expSeconds = 2_000L,
        )

        assertEquals(
            PeerDirectory.Verdict.BadSignature,
            PeerDirectory.verify(v1, nowMs = 1_000_000L),
        )
        // Той самий запис у v2 — приймається. Без цього відмова вище доводила
        // б лише те, що ми зіпсували підпис узагалі.
        assertTrue(
            PeerDirectory.verify(record(marta, "marta_k", 2_000L), nowMs = 1_000_000L)
                is PeerDirectory.Verdict.Trusted,
        )
    }

    /**
     * Сторож: безадресний конверт довідника не несе ЖОДНОГО людського рядка.
     *
     * Хеш прибрав імʼя з запиту, але база складалася в перелік людських імен
     * із ключами далі — бо показне імʼя їхало всередині самого конверта. Тут
     * перевіряється не «немає імені Марта», а сильніше: кожне поле, що
     * лишилось, має машинну форму, і людський рядок у жодну з них не влізе.
     * Перевірка йде над РОЗПАКОВАНИМ тілом коду, а не над полями `Invite`:
     * зрізати імʼя в структурі й лишити його в base64 — це рівно та помилка,
     * яку тест мусить ловити.
     */
    @Test
    fun `the directory envelope carries no human strings at all`() {
        val marta = Person("Марта Коваленко")
        val code = PeerInvite.encode(PeerDirectory.addressless(marta.card))
        val payload = String(
            java.util.Base64.getUrlDecoder().decode(code.split(":")[1]),
            Charsets.UTF_8,
        )
        val f = payload.split("|")

        assertEquals(6, f.size)
        assertEquals("показне імʼя зрізане", "", f[1])
        assertEquals("адреси немає", "", f[4])
        assertEquals("порту немає", "0", f[5])
        assertFalse("імені в конверті немає", payload.contains("Марта"))
        assertFalse(payload.contains("Коваленко"))

        assertTrue("відбиток ключа — 16 hex: ${f[0]}", Regex("^[0-9a-f]{16}$").matches(f[0]))
        for (key in listOf(f[2], f[3])) {
            assertEquals(32, java.util.Base64.getDecoder().decode(key).size)
        }
    }

    /**
     * Старий контракт (rows підставляв короткий ідентифікатор) знято 22.10:
     * код ключа в рядку пошуку власник бачив як імʼя людини. Розбір конверта
     * свою підстановку лишає — на неї спираються інші шляхи.
     */
    @Test
    fun `rows pass the name through and never invent a short id`() {
        val marta = Person("Марта")
        val rec = record(marta, "marta_k", 2_000L)
        val trusted =
            PeerDirectory.verify(rec, nowMs = 1_000_000L) as PeerDirectory.Verdict.Trusted
        assertEquals(PeerRoster.shortId(marta.card.peerId), trusted.invite.name)

        val blank = PeerDirectory.rows("marta_k", PeerDirectory.addressless(marta.card), rec.invite)
        assertEquals(1, blank.size)
        assertEquals("", blank[0].name)
        assertEquals("Марта", PeerDirectory.rows("marta_k", marta.card, rec.invite)[0].name)
    }

    @Test
    fun `a lookup answers with one row or with none`() {
        val marta = Person("Марта")
        val code = PeerInvite.encode(PeerDirectory.addressless(marta.card))

        assertEquals(0, PeerDirectory.rows("marta_k", null, "").size)
        val one = PeerDirectory.rows("marta_k", marta.card, code)
        assertEquals(1, one.size)
        assertEquals(marta.card.peerId, one[0].id)
        assertEquals("marta_k", one[0].handle)
        assertEquals(code, one[0].compact)
    }

    /**
     * Раніше тут стояла відмова: звичайний декодер не читав коду без адреси,
     * щоб запис довідника, вставлений у поле «встав код людини», не склав
     * контакту, якому нема куди слати перший лист. З 04.09 безадресна форма —
     * це те, що друкує САМ телефон, доки він нічого не слухає, тож та відмова
     * відмовляла б власним кодам. Її роботу тепер робить не декодер, а
     * [PeerDirectory.Verdict.HasAddress] — і рівно в той бік, який має значення:
     * адреса, ПРИСЛАНА сервером, не приймається («an invite carrying an address
     * is refused even when the signature holds» вище).
     */
    @Test
    fun `обидві форми читає один декодер`() {
        val marta = Person("Марта")

        val code = PeerInvite.encode(PeerDirectory.addressless(marta.card))
        assertTrue(PeerInvite.decode(code) is PeerInvite.Decoded.Ph2)

        val addressed = PeerInvite.encode(marta.card.copy(host = "10.0.0.4", port = 47_331))
        assertTrue(PeerInvite.decode(addressed) is PeerInvite.Decoded.Ph2)
    }

    // ── довжина імені ────────────────────────────────────────────────────

    @Test
    fun `завести коротке імʼя вже не можна`() {
        // Вісім знаків — захід проти словника по голому sha256: кожен знак
        // множить простір на 37, тож 5 → 8 це ×50 653 і нуль коштів.
        assertEquals(8, PeerDirectory.MIN_PUBLISH_LENGTH)
        assertNull(PeerDirectory.normaliseForPublish("marta_k"))
        assertNull(PeerDirectory.normaliseForPublish("@Marta_K"))
        assertEquals("marta_kom", PeerDirectory.normaliseForPublish("@Marta_Kom"))
        assertEquals("marta_ko", PeerDirectory.normaliseForPublish("marta_ko"))
        // Решта форми не послабилась разом із довжиною.
        assertNull(PeerDirectory.normaliseForPublish("мартакома"))
        assertNull(PeerDirectory.normaliseForPublish("marta kom"))
        assertNull(PeerDirectory.normaliseForPublish("a".repeat(33)))
        assertNull(PeerDirectory.normaliseForPublish(null))
    }

    @Test
    fun `коротке імʼя, видане раніше, лишається знайденим і знімним`() {
        // Правило введено СЬОГОДНІ. Якби воно судило й пошук зі зняттям, імʼя,
        // опубліковане вчора, замкнулось би на сервері до кінця строку: власник
        // не зміг би його зняти, бо власна ж збірка відмовила б йому вдома.
        // Тому [normalise] лишається від пʼяти — це форма, яку сервер ЩЕ тримає.
        assertEquals("marta_k", PeerDirectory.normalise("marta_k"))
        assertEquals("marta_k", PeerDirectory.normalise("@Marta_K"))
        assertTrue(PeerDirectory.isHandle("marta_k"))

        // І коротше за пʼять не було іменем ніколи — межа не поповзла вниз.
        assertNull(PeerDirectory.normalise("abcd"))
    }
}
