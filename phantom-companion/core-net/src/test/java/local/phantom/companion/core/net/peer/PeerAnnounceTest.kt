package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Шторка мусить мовчати про те, що людина зараз читає — і про групу теж.
 *
 * Один-на-один це вже вміло, група — ні: кожен рядок групи, відкритої на
 * екрані, дзижчав у руці. Так люди й вчаться глушити канал, після чого не
 * доходить уже ніщо.
 */
class PeerAnnounceTest {

    private val group = PeerGroup.Group("abc123abc123", "Північ", listOf("me", "marta", "oleh"))

    private fun line(from: String, body: String) = PeerMessage(
        id = "m1",
        fromId = from,
        toId = "me",
        body = body,
        sentAtMs = 1_000L,
    )

    @Test
    fun `the conversation on screen does not also buzz`() {
        assertNull(line("marta", "тут").let { PeerAnnounce.notice(it, "marta", null) })
    }

    @Test
    fun `somebody else writing while another chat is open still gets through`() {
        assertNotNull(PeerAnnounce.notice(line("oleh", "тут"), "marta", null))
    }

    @Test
    fun `the group on screen does not buzz either`() {
        val body = PeerGroup.encode(group, "виходимо")
        assertNull(PeerAnnounce.notice(line("marta", body), null, group.id))
    }

    @Test
    fun `a group line is not silenced by the one-to-one chat being open`() {
        // Той самий відправник, але це група — інша розмова, інший запис у шторці.
        val body = PeerGroup.encode(group, "виходимо")
        assertNotNull(PeerAnnounce.notice(line("marta", body), "marta", null))
    }

    @Test
    fun `a group line is keyed on the group, a private one on the sender`() {
        val body = PeerGroup.encode(group, "виходимо")
        assertEquals(group.id, PeerAnnounce.notice(line("marta", body), null, null)?.key)
        assertEquals("marta", PeerAnnounce.notice(line("marta", "тут"), null, null)?.key)
    }

    @Test
    fun `the shade gets the words, never the wire form they arrived in`() {
        val body = PeerGroup.encode(group, "виходимо")
        val notice = PeerAnnounce.notice(line("marta", body), null, null)
        assertEquals("виходимо", notice?.text)
        assertEquals("Північ", notice?.groupName)
    }

    @Test
    fun `another group being open does not silence this one`() {
        val body = PeerGroup.encode(group, "виходимо")
        assertNotNull(PeerAnnounce.notice(line("marta", body), null, "ffffffffffff"))
    }

    // ── 12.09: екран блокування тестувальника, дослівно ────────────────────
    // «Без імені Чтчтчт», «peer·phan ⚡ Аналіз запиту: «D»…», «fa57·1a94 D +4».
    // Три різні вади в одному кадрі: власний лист, відповідь пристрою і
    // службовий кадр. Сповіщення бувають лише про вхідне від людини.

    @Test
    fun `власний лист не сповіщає`() {
        assertNull(PeerAnnounce.notice(line("me", "Чтчтчт"), null, null, selfId = "me"))
    }

    @Test
    fun `чужий лист сповіщає, навіть коли власний ключ відомий`() {
        assertNotNull(PeerAnnounce.notice(line("marta", "тут"), null, null, selfId = "me"))
    }

    @Test
    fun `відповідь самого PHANTOM не сповіщає`() {
        val phantom = line("peer_phantom_agent", "⚡ Аналіз запиту: «D»")
        assertNull(
            PeerAnnounce.notice(phantom, null, null, isHuman = { !it.startsWith("peer_phantom") }),
        )
    }

    @Test
    fun `жива позначка і її зупинка мовчать`() {
        assertNull(PeerAnnounce.notice(line("marta", "${PeerLive.FIX}50.1,30.1;9"), null, null))
        assertNull(PeerAnnounce.notice(line("marta", PeerLive.STOP), null, null))
    }

    @Test
    fun `системний рядок групи мовчить`() {
        assertNull(PeerAnnounce.notice(line("marta", "${PeerGroup.SYS}приєднався"), null, null))
    }

    @Test
    fun `службовий кадр усередині групи теж мовчить`() {
        val body = PeerGroup.encode(group, "${PeerGroup.SYS}приєднався")
        assertNull(PeerAnnounce.notice(line("marta", body), null, null))
    }

    @Test
    fun `надіслане місце лишається сповіщенням — це вчинок людини`() {
        assertNotNull(PeerAnnounce.notice(line("marta", "${PeerPayload.LOC}50.1,30.1;25"), null, null))
    }

    @Test
    fun `шторка групує за розмовою, а не за листом`() {
        val first = PeerAnnounce.notice(line("marta", "раз"), null, null)
        val second = PeerAnnounce.notice(line("marta", "два"), null, null)
        assertEquals(first?.key, second?.key)
    }

    @Test
    fun `a letter sent without sound reaches the shade quietly, in a chat and in a group`() {
        val dm = PeerAnnounce.notice(line("marta", "сплю").copy(quiet = true), null, null)
        assertEquals(true, dm?.quiet)
        val inGroup = PeerAnnounce.notice(line("marta", PeerGroup.encode(group, "тихо")).copy(quiet = true), null, null)
        assertEquals(true, inGroup?.quiet)
        assertEquals(false, PeerAnnounce.notice(line("marta", "дзвінко"), null, null)?.quiet)
    }

    @Test
    fun `P10 - a group line that names me carries the mention, a line that names someone else does not`() {
        val body = PeerGroup.encode(group, "@Кирило глянь")
        val mine = PeerAnnounce.notice(line("marta", body).copy(mentions = listOf("me")), null, null, selfId = "me")
        assertEquals(true, mine?.mentionsMe)
        val theirs = PeerAnnounce.notice(line("marta", body).copy(mentions = listOf("oleh")), null, null, selfId = "me")
        assertEquals(false, theirs?.mentionsMe)
        assertEquals(false, PeerAnnounce.notice(line("marta", body), null, null, selfId = "me")?.mentionsMe)
    }

    @Test
    fun `P10 - mentions survive the envelope as mn, stay out of the signature, and old letters read empty`() {
        val key = local.phantom.companion.core.net.crypto.randomBytes(32)
        val named = line("marta", PeerGroup.encode(group, "@Олег")).copy(mentions = listOf("oleh"))
        val frame = PeerEnvelope.seal(named, key)!!.frame
        assertEquals(listOf("oleh"), PeerEnvelope.open(frame) { key }?.mentions)
        assertEquals(String(PeerSealing.canonical(named.copy(mentions = emptyList()))), String(PeerSealing.canonical(named)))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val wire = json.encodeToString(PeerMessage.serializer(), named)
        assertEquals(true, wire.contains("\"mn\":[\"oleh\"]"))
        assertEquals(false, json.encodeToString(PeerMessage.serializer(), line("marta", "x")).contains("\"mn\""))
        assertEquals(emptyList<String>(), json.decodeFromString(PeerMessage.serializer(), json.encodeToString(PeerMessage.serializer(), line("marta", "x"))).mentions)
    }

    @Test
    fun `the quiet flag survives the envelope and does not change what is signed`() {
        val key = local.phantom.companion.core.net.crypto.randomBytes(32)
        val quiet = line("marta", "сплю").copy(quiet = true)
        val frame = PeerEnvelope.seal(quiet, key)!!.frame
        assertEquals(true, PeerEnvelope.open(frame) { key }?.quiet)
        assertEquals(
            String(PeerSealing.canonical(quiet.copy(quiet = false))),
            String(PeerSealing.canonical(quiet)),
        )
    }
}
