package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Другий вузол — не цей код.
 *
 * `scripts/phantom_peer.py` робить ноутбук справжнім учасником сітки: свій
 * ключ, своє запрошення, свій підпис. Обидві перевірки доставки спираються на
 * те, що його байти й наші — однакові байти. Але це дві РІЗНІ реалізації, і
 * розійтись вони можуть мовчки: варто комусь тут змінити роздільник у
 * канонічній формі чи порядок полів у запрошенні, і скрипт почне «доводити»
 * доставку, якої немає, — впаде він лише на живому пристрої, якого поруч може
 * не бути тижнями.
 *
 * Тому зліпки нижче зроблено пітоном і вбито в камінь. Ламається тут — значить
 * зламано сумісність із другим вузлом, а не тест.
 */
class PeerSecondNodeTest {

    /** Насіння з вектора 1 RFC 8032 — щоб зліпок можна було відтворити. */
    private val laptopPub = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo"

    /** Публічний ключ Аліси з RFC 7748 §6.1 — теж відтворюваний вектор. */
    private val laptopDhPub = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo"
    private val laptopId = "d1d27b5fa7533574"

    @Test
    fun `код запрошення з ноутбука розбирається застосунком`() {
        val code = "PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU" +
            "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz" +
            "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98MTAuMC4yLjJ8NDc5MjE:e39f92"

        val invite = inviteIn(code)

        assertNotNull("запрошення з ноутбука більше не розбирається", invite)
        invite!!
        assertEquals(laptopId, invite.peerId)
        assertEquals("Ноутбук", invite.name)
        assertEquals(laptopPub, invite.pubKey)
        assertEquals(laptopDhPub, invite.dhPubKey)
        assertEquals("10.0.2.2", invite.host)
        assertEquals(47_921, invite.port)
    }

    /**
     * Другий зліпок пітона: те саме запрошення без порту.
     *
     * Це не окремий діалект — саме таку форму друкує апарат, доки [PeerLink]
     * нічого не слухає, а на A52 04.09 це був звичайний стан. Розійдись тут
     * ноутбук із застосунком — і стенд «доводив» би формат, якого людина в
     * своєму QR не бачить.
     */
    @Test
    fun `код без порту з ноутбука не несе адреси й розбирається`() {
        val silent = "PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU" +
            "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz" +
            "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98fDA:51767f"

        assertEquals(silent, PeerInvite.encode(laptopCard(host = "10.0.2.2", port = 0)))

        val invite = inviteIn(silent)
        assertNotNull("безадресний код з ноутбука не розбирається", invite)
        assertEquals("", invite!!.host)
        assertEquals(0, invite.port)
        assertEquals(laptopDhPub, invite.dhPubKey)
        assertEquals("Ноутбук", invite.name)
    }

    /** Уже роздане: адреса всередині, порт нуль. Мусить читатись і далі. */
    @Test
    fun `код з адресою й нулем, знятий з екрана раніше, читається`() {
        val alreadyOut = "PH2:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU" +
            "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDcz" +
            "V2cyL09nMG1PQnIwNjZTcGpxcWJUbW98MTAuMC4yLjJ8MA:9354e4"

        val invite = inviteIn(alreadyOut)
        assertNotNull("уже роздані коди перестали читатись", invite)
        assertEquals("10.0.2.2", invite!!.host)
        assertEquals(0, invite.port)
    }

    private fun laptopCard(host: String, port: Int) = PeerInvite.Invite(
        peerId = laptopId,
        name = "Ноутбук",
        pubKey = laptopPub,
        dhPubKey = laptopDhPub,
        host = host,
        port = port,
    )

    /**
     * Старий формат мусить лишитись ВПІЗНАВАНИМ, але нерозбірним: скрипт із
     * попередньої версії, наведений на новий застосунок, має почути «онови», а
     * не мовчазну відмову, яку ніхто не повʼяже з причиною.
     */
    @Test
    fun `код запрошення старого формату впізнається і не розбирається`() {
        val old = "PH1:ZDFkMjdiNWZhNzUzMzU3NHzQndC-0YPRgtCx0YPQunwxMXFZQVlLeENyZlZTLzdU" +
            "eVdRSE9nN2hjdlBhcGlNbHJ3SWFhUGNIVVJvfDEwLjAuMi4yfDQ3OTIx:616ec0"

        assertEquals(PeerInvite.Decoded.LegacyPh1, PeerInvite.decode(old))
    }

    @Test
    fun `підпис ноутбука проходить перевірку тут`() {
        val message = laptopMessage()

        assertTrue(
            "канонічна форма розійшлась із другим вузлом",
            PeerSealing.verify(message, decode(laptopPub), expectedFromId = laptopId),
        )
    }

    /**
     * Зліпок мусить бути крихким у правильному місці: якщо він проходить
     * перевірку навіть із переставленими полями, він не доводить нічого.
     */
    @Test
    fun `той самий підпис під переадресованим повідомленням не проходить`() {
        val readdressed = laptopMessage().copy(toId = "0000000000000000")

        assertFalse(PeerSealing.verify(readdressed, decode(laptopPub), expectedFromId = laptopId))
    }

    @Test
    fun `відбиток ключа рахується однаково на обох вузлах`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(laptopPub.toByteArray(Charsets.UTF_8))

        assertEquals(laptopId, digest.take(8).joinToString("") { "%02x".format(it) })
    }

    /**
     * Ключ пари, виведений пітоном із ключів RFC 7748 §6.1. Розійдеться сіль,
     * info чи порядок відбитків — і жоден лист із ноутбука не відкриється,
     * причому обидва боки вважатимуть себе правими.
     */
    @Test
    fun `ключ пари виводиться однаково тут і на другому вузлі`() {
        val laptopDhPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val phoneDhPub = "3p7bfXt9wbTTW2HC7OQ1Nz+DQ8hbeGdNrfx+FG+IK08"

        val key = PeerChannel.derive(laptopDhPriv, phoneDhPub, laptopId, "aabbccddeeff0011")

        assertEquals(
            "20038b5adbe88c3bdd8d784f117d23bff3d6e4da63bc294753204769e3e41723",
            key!!.joinToString("") { "%02x".format(it) },
        )
    }

    /**
     * Найдорожчий зліпок у файлі: КОНВЕРТ, запечатаний пітоном, мусить
     * відкритись тут. Порядок полів в AAD, варіант base64, місце тега, форма
     * відкритого тексту — усе це може розійтись мовчки, і побачили б ми це
     * лише як «лист не дійшов» на чужому телефоні.
     */
    @Test
    fun `конверт із ноутбука відкривається застосунком`() {
        val opened = PeerEnvelope.open(laptopFrame) { laptopChannelKey }

        assertNotNull("конверт із другого вузла більше не відкривається", opened)
        assertEquals("перевірка з ноутбука PH3-PROOF-c0ffee11", opened!!.body)
        assertEquals(laptopId, opened.fromId)
        // І підпис усередині теж мусить пройти — конверт не замінює авторства.
        assertTrue(
            PeerSealing.verify(opened, decode(laptopPub), expectedFromId = laptopId),
        )
        // Те, заради чого все: слова немає в кадрі. Від v3 немає й id — тепер
        // спостерігач не може навіть назвати лист, за який хоче відзвітувати.
        assertFalse(laptopFrame.contains("PH3-PROOF-c0ffee11"))
        assertFalse(laptopFrame.contains("ноутбука"))
        assertFalse(laptopFrame.contains("6f1e4b2a"))
    }

    /**
     * Найтихіший спосіб усе зламати: обидва вузли рахують квитанцію, але
     * трохи по-різному. Тоді телефон чесно доставить лист і чесно не повірить
     * жодній відповіді — а виглядатиме це як «мережа не працює».
     */
    @Test
    fun `квитанція рахується однаково тут і на другому вузлі`() {
        val tag = PeerReceipt.of(
            channelKey = laptopChannelKey,
            messageId = "6f1e4b2a-0000-4000-8000-000000000002",
            fromId = laptopId,
            toId = "aabbccddeeff0011",
            nonceB64 = PeerEnvelope.parse(laptopFrame)!!.n,
        )

        assertEquals("B0CZnkeVnDitIRKe07zyICMggVggUwJ0XcdRIVFXvLo", tag)
    }

    // — PH4: зустріч, погоджена з ноутбуком ————————————————————————————————
    //
    // Ефемерні половини й добавки тут СТАЛІ — інакше зліпок не відтворити.
    // На дроті вони випадкові щоразу; тут вони прибиті, щоб розходження двох
    // реалізацій падало саме тут, а не на чужому телефоні через тиждень.

    private val phonePub = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw"
    private val phoneDhPub = "3p7bfXt9wbTTW2HC7OQ1Nz+DQ8hbeGdNrfx+FG+IK08"
    private val phoneId = "aabbccddeeff0011"

    private val laptopHello = PeerSession.Hello(
        v = 1,
        from = laptopId,
        to = phoneId,
        eph = "HJ/Yj0VgbZMqgMcYJK4VHRXXPnfeOOjgAIUuYU+ucBk",
        n = "AQIDBAUGBwgJCgsMDQ4PEA",
        sig = "2SA8Uq84e6u9Dycu3w0mkjZK8nMMkTI01XuTiR92X8N3yhmDK2gfDTaUPkhpeB+Czf" +
            "DGU1vqz/NQlLG+W5T6Aw",
    )

    private val phoneHello = PeerSession.Hello(
        v = 1,
        from = phoneId,
        to = laptopId,
        eph = "/2P+V7+/Q/o/VjYosUmvcE09tiU2nEmYNlA0empx4A4",
        n = "ERITFBUWFxgZGhscHR4fIA",
        sig = "hLKXq4gw8WFg9IzTUMCYNsXL3mufWwRGZpXzzJ00oCVB9xVUHQtIRVa4dSWVrK33Pd" +
            "wsnLdT8pDyp4OWyJ66BQ",
    )

    private val meetingSid = "3247e4c1288b3e5e"
    private val meetingKey =
        hex("efc1cdc5736801c7bcad03043c867d8c11c29fa60ae6e0f130cb4f617dad9997")

    /** Ключ зустрічі. Розійдеться порядок ikm, солі чи info — і жоден лист не відкриється. */
    @Test
    fun `ключ зустрічі виводиться однаково тут і на другому вузлі`() {
        val laptopDhPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")

        val key = PeerSession.derive(
            myEphPrivate = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            theirEph = phoneHello.eph,
            myStaticDh = laptopDhPriv,
            theirStaticDhB64 = phoneDhPub,
            myId = laptopId,
            theirId = phoneId,
            initiator = laptopHello,
            responder = phoneHello,
        )

        assertEquals(
            "efc1cdc5736801c7bcad03043c867d8c11c29fa60ae6e0f130cb4f617dad9997",
            key!!.joinToString("") { "%02x".format(it) },
        )
    }

    /** Імʼя зустрічі: обидва боки мусять назвати її однаково, або кадр нікуди не приткнути. */
    @Test
    fun `імʼя зустрічі рахується однаково на обох вузлах`() {
        assertEquals(meetingSid, PeerSession.sid(laptopHello, phoneHello))
    }

    /**
     * Підписи обох привітань. Ініціаторів підпис перевіряє відповідач, і
     * навпаки — розійдеться порядок полів у канонічній формі, і зустріч не
     * складеться ЖОДНОГО разу, причому обидва боки вважатимуть себе правими.
     */
    @Test
    fun `обидва привітання з ноутбука проходять перевірку тут`() {
        assertTrue(
            "канонічна форма привіту ініціатора розійшлась",
            local.phantom.companion.core.net.crypto.Ed25519.verify(
                decode(laptopPub),
                PeerSession.initiatorTranscript(laptopHello),
                decode(laptopHello.sig),
            ),
        )
        assertTrue(
            "канонічна форма привіту відповідача розійшлась",
            local.phantom.companion.core.net.crypto.Ed25519.verify(
                decode(phonePub),
                PeerSession.responderTranscript(phoneHello, laptopHello),
                decode(phoneHello.sig),
            ),
        )
    }

    /** І весь шлях відповідача разом: розібрати, перевірити, погодити. */
    @Test
    fun `привіт із ноутбука доводить обмін до ключа`() {
        val answered = PeerSession.answer(
            theirHello = PeerSession.parse(PeerSession.encode(laptopHello))!!,
            myId = phoneId,
            theirSigningPubB64 = laptopPub,
            mySigningKey = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"),
            myStaticDh = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"),
            theirStaticDhB64 = laptopDhPub,
            nowMs = 1L,
        )
        assertNotNull("привіт із другого вузла більше не розбирається", answered)
    }

    /**
     * Найдорожчий зліпок хвилі: конверт ПІД КЛЮЧЕМ ЗУСТРІЧІ, запечатаний
     * пітоном. Імʼя зустрічі лежить в AAD, тож розійтись може ще й воно — і
     * тоді доставка мовчки перестане працювати саме там, де свіжий ключ і
     * мав би захищати.
     */
    @Test
    fun `конверт зустрічі з ноутбука відкривається застосунком`() {
        val opened = PeerEnvelope.open(meetingFrame, sessionKey = { sid ->
            meetingKey.takeIf { sid == meetingSid }
        }) { null }

        assertNotNull("конверт v4 із другого вузла більше не відкривається", opened)
        assertEquals("зустріч із ноутбука PH4-PROOF-a17c3e90", opened!!.body)
        assertTrue(PeerSealing.verify(opened, decode(laptopPub), expectedFromId = laptopId))
        assertFalse(meetingFrame.contains("PH4-PROOF-a17c3e90"))
        assertFalse(meetingFrame.contains("ноутбука"))

        // І та сама відмова, що робить свіжий ключ свіжим: довгий ключ пари
        // цього кадру не відкриває на жодному з двох вузлів.
        assertNull(PeerEnvelope.open(meetingFrame) { laptopChannelKey })
    }

    @Test
    fun `квитанція під ключем зустрічі рахується однаково на обох вузлах`() {
        assertEquals(
            "NaYtjg4akBPIPwLozA6hw3lhaw09ByYMOsKfoug88HI",
            PeerReceipt.of(
                channelKey = meetingKey,
                messageId = "6f1e4b2a-0000-4000-8000-000000000004",
                fromId = laptopId,
                toId = phoneId,
                nonceB64 = PeerEnvelope.parse(meetingFrame)!!.n,
            ),
        )
    }

    private val meetingFrame =
        "{\"v\":4,\"from\":\"d1d27b5fa7533574\",\"to\":\"aabbccddeeff0011\",\"n\":\"AQ" +
            "IDBAUGBwgJCgsM\",\"ct\":\"gQxi9kXcpIm4vs8zIrnmtqmuEtcZDM/Mqd4jsabJs/86GW" +
            "h2/l/vRFfpOaQTSsofE/UNtIfjoOx78lERCCj/gSElCky06GeZ8D2u0No71P2kCuPIP9hEJT" +
            "w0jmRSWBE9zc91Q89EIIzvDotCuo4qXGEKfvtDG7MwrC8PzFS1wknRSbkhY7S/JiLWBPbHtb" +
            "siJ8JUWW1wWLkzc9yuuD0PLxmMsp3MsB2sauvP4nS9C8ugAppBtRXtfayCX2CEWphkPW1mxX" +
            "bpq7KtVhYdLZ5+S11CvIlUG1ol6j4zOP8tgVwP1k5BpWk3w1ftyJW0INgPzMeDETkpDEelen" +
            "huqZcih1nC0/BULlTmrPAZgaJ/DNTcz8BmB33WTzzj1PEEQDivw+q25AcsBFX8HMllO8jrTn" +
            "+FJV2ecQnUZOShZ+gZVC6P\",\"sid\":\"3247e4c1288b3e5e\"}"

    private val laptopChannelKey =
        hex("20038b5adbe88c3bdd8d784f117d23bff3d6e4da63bc294753204769e3e41723")

    private val laptopFrame =
        "{\"v\":3,\"from\":\"d1d27b5fa7533574\",\"to\":\"aabbccddeeff0011\",\"n\":\"AQ" +
            "IDBAUGBwgJCgsM\",\"ct\":\"OoWG1wNcdPV2V257rb28D9lLonE2PgTYXwPhu47EqX" +
            "CJ+JThJpapPMoi+jIEF8uL7hDIR3JJjLdgw/DCN1K4zFQ49a7wrM4ydtAvMkGZj9" +
            "Lk6o7qMbqg57mfa+s7jht3GabZmociB4bNQG7iRe2KFV5wvnogI8TbQ9Xqgmc2UI" +
            "E9+ldSNiAUUMOV3bybLSi6ZVQQjOBViqoyjv9hKiW5M7qSPbzPRtAaTX8PlJt/Cx" +
            "rQC0T6b2QT3JyK1tWeGbo7+I1PFsXPTLgzqjrBI/ZuXT/TxYEWJeEImmc+R8BUf2" +
            "pzsDf66QEYxj14DJPPguz8lcOJoKxWwcmHAd3e/g6w5//3ur7AikzeGWIL0AeA+X" +
            "aueo1TfUbiMCrDAuKMYnVCkM+6BrHITat3R9hYsw8hDyR94EJhZ6BeNVAxQt4vP9" +
            "WivQ4\"}"

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun laptopMessage() = PeerMessage(
        id = "6f1e4b2a-0000-4000-8000-000000000001",
        fromId = laptopId,
        toId = "aabbccddeeff0011",
        body = "перевірка з ноутбука",
        sentAtMs = 1_754_870_400_000L,
        signature = "GGq87FnKUEEmhCptuUQEOF/atflwlJDxYqKIPi3hUd94IBwBVC/f0dbTfFeEkphJ" +
            "DlzVE8eNF2Q0SIY3x/tpCw",
    )

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
