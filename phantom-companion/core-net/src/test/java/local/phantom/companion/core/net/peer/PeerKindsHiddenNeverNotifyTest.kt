package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Рід, що не доходить до розмови, не доходить і до жодного з трьох лічильників.
 *
 * Один сторож на всі приховані роди, а не по одному на бригаду. Дзвінок і
 * переказана історія додаються різними руками й у різні дні; без спільного
 * виміру вони розійшлися б у поведінці — один мовчав би в шторці, але ріс би в
 * непрочитаних, другий навпаки. Обидві половини виглядають як дрібниця, і обидві
 * показують людині число, за яким у розмові нічого немає.
 *
 * Перелік не свій: береться з [PeerFrames.Kind], тож новий прихований рід
 * потрапляє сюди сам, а не тоді, коли про нього згадають.
 */
class PeerKindsHiddenNeverNotifyTest {

    /** Номер дзвінка мінить рушій у вебі, а не ми. */
    private val callId = "call_9f31ac2b-17"

    /** Id рядка, на який указує позначка, — такий, яким його мінить PeerMesh. */
    private val targetId = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2"

    private val group = PeerGroup.Group(
        id = "a1b2c3d4e5f6",
        name = "ланка",
        memberIds = listOf("me", "them"),
    )

    private fun encoded(kind: PeerFrames.Kind): String = when (kind) {
        PeerFrames.Kind.ROUTE -> PeerLive.encodeRoute(
            PeerLive.Route(
                expiresAtMs = NOW + PeerLive.ROUTE_LIFE_MS,
                distanceM = 337.0,
                durationS = 258L,
                profile = "foot",
                waypoints = listOf(50.45010 to 30.52340, 50.45120 to 30.52330),
                shape = listOf(50.45010 to 30.52340, 50.45120 to 30.52330),
            ),
        )!!
        PeerFrames.Kind.FIX -> PeerLive.encodeFix(
            PeerLive.Fix(50.45010, 30.52340, 12, NOW, NOW + 600_000L, 3),
        )!!
        PeerFrames.Kind.STOP -> PeerLive.encodeStop(NOW)
        PeerFrames.Kind.PLACE -> PeerPayload.encodePlace(50.45010, 30.52340, 25)
        PeerFrames.Kind.CHUNK -> PeerFile.encodeChunk("0".repeat(32), 0, 1, byteArrayOf(7, 8))
        PeerFrames.Kind.GROUP -> PeerGroup.encode(group, "виходь")
        PeerFrames.Kind.SYSTEM -> PeerGroup.encodeAct(PeerGroup.Act.Leave)
        PeerFrames.Kind.CHANNEL -> PeerGroup.inChannel("general", "привіт")!!
        PeerFrames.Kind.CARD -> PeerCard.encode(
            PeerCard.Body(PeerCard.Card.KANBAN, "{\"columns\":[]}"),
        )!!
        PeerFrames.Kind.CALL_SIGNAL -> PeerCall.encodeSignal(
            PeerCall.Signal(
                callId,
                PeerCall.Signalling.OFFER,
                "v=0\r\na=fmtp:111 minptime=10;useinbandfec=1",
            ),
        )!!
        PeerFrames.Kind.REACTION -> PeerMark.encode(
            PeerMark.Mark(PeerMark.What.REACTION, targetId, on = true, value = "❤️"),
        )!!
        PeerFrames.Kind.VOTE -> PeerMark.encode(
            PeerMark.Mark(PeerMark.What.VOTE, targetId, on = true, value = "opt_1"),
        )!!
        PeerFrames.Kind.CARD_STATE -> PeerMark.encode(
            PeerMark.Mark(PeerMark.What.CARD_STATE, targetId, on = true, value = "rsvp:yes"),
        )!!
        PeerFrames.Kind.PIN -> PeerMark.encode(
            PeerMark.Mark(PeerMark.What.PIN, targetId, on = true, value = PeerMark.PIN_VALUE),
        )!!
        PeerFrames.Kind.HIST -> PeerHistory.packs(
            group.id,
            listOf(PeerHistory.Line("l1", "them", NOW, "виходили о шостій")),
        ).single()
        PeerFrames.Kind.BANNER -> PeerBanner.encode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10))!!
        PeerFrames.Kind.BANNER_PULL -> PeerBanner.pull("ab".repeat(16))!!
        PeerFrames.Kind.PROFILE -> PeerProfile.encode(
            PeerProfile.Body(
                rev = 1,
                bio = PeerProfile.Field.Set("Кирило"),
            ),
        )!!
        PeerFrames.Kind.WHERE -> PeerWhere.encode(
            PeerWhere.Area(
                PeerWhereGrid.cellOf(50.45010, 30.52340, PeerWhereGrid.Level.DISTRICT),
                NOW,
                NOW + 600_000L,
                1,
            ),
        )!!
        PeerFrames.Kind.EDIT -> PeerRevise.encode(PeerRevise.Edit(targetId, "виходимо о сьомій"))!!
        PeerFrames.Kind.UNSEND -> PeerRevise.encode(PeerRevise.Unsend(targetId))!!
        PeerFrames.Kind.READ -> PeerRead.encode(PeerRead.Read(NOW))!!
        PeerFrames.Kind.TYPING -> PeerTyping.encode(PeerTyping.Typing(on = true))!!
        PeerFrames.Kind.INTENT -> PeerIntent.encodeIntent(ByteArray(PeerIntent.SEAL_OVERHEAD + 16) { it.toByte() })
        PeerFrames.Kind.ANSWER -> PeerIntent.encodeAnswer(ByteArray(PeerIntent.SEAL_OVERHEAD + 1) { it.toByte() })
        PeerFrames.Kind.GATEWAY -> PeerIntent.GATEWAY_TAG + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
        PeerFrames.Kind.PREKEYS -> RelayFs.encodeBundle(RelayFs.Bundle(RelayFs.SignedPrekey(1, "AA", "AA")))
        PeerFrames.Kind.TTL -> PeerDisappear.encode(PeerDisappear.DAY_S, 1_790_000_000_000L)!!
        PeerFrames.Kind.PREVIEW -> PeerPreview.encode("m1", PeerPreview.Preview("https://example.org/a", "Заголовок"))!!
        PeerFrames.Kind.MOMENT -> MomentWire.encode(MomentWire.Op.Seen("m1abcdefgh", 1_790_000_000_000L))!!
        PeerFrames.Kind.CONTACT -> PeerContact.encode(PeerContact.Op.Request("r1", "Олена", "привіт"))
        PeerFrames.Kind.GROUP_JOIN -> PeerGroupJoin.encode(PeerGroupJoin.Frame.Ask("g0001", "Abcdefgh23456789"))
        PeerFrames.Kind.STAFF -> StaffWire.encode(StaffWire.Pass("Зерно", 1L, emptyList()))!!
        // Тег, якого не знає жодна збірка, — «кадр з завтрашньої».
        PeerFrames.Kind.FUTURE -> "PH-QQQ:1788517702898;щось із завтрашньої збірки"
    }

    private fun letter(body: String): PeerMessage =
        PeerMessage(id = "m", fromId = "them", toId = "me", body = body, sentAtMs = NOW)

    private val hidden = PeerFrames.Kind.entries.filterNot { it.reachesConversation }

    /**
     * Те саме, але без обгорток чужих слів.
     *
     * [PeerFrames.Kind.GROUP] теж не доходить до розмови як кадр — і це не той
     * самий «прихований». Він не вимір пристрою, а КОНВЕРТ навколо слова
     * людини: [PeerAnnounce] його розпечатує й оголошує те, що всередині. Тобто
     * `reachesConversation` і «ніколи не дзвонить» — дві різні осі, і сторож,
     * який їх сплутає, вимагатиме тиші саме там, де людині написали.
     *
     * Обгортка впізнається за самим кадром, а не за рукописним переліком, — тож
     * новий прихований рід усе одно потрапляє сюди сам.
     */
    private val wordless = hidden.filterNot { PeerGroup.isGroup(encoded(it)) }

    @Test
    fun `прихований рід не піднімає плашки`() {
        val loud = wordless.filter {
            PeerAnnounce.notice(letter(encoded(it)), visibleChat = null, visibleGroup = null) != null
        }
        assertEquals(
            "рід, якого розмова не показує, дзвонить у шторці — людина відкриє " +
                "розмову й не побачить там нічого",
            emptyList<PeerFrames.Kind>(),
            loud,
        )
    }

    /**
     * Негативний контроль виїмки вище: якби вона розповзлась, попередній випадок
     * зеленів би на дедалі меншому переліку, аж доки не перестав би щось міряти.
     * Лист у гілку МУСИТЬ дзвеніти — і саме словами, а не сирим дротом.
     */
    @Test
    fun `виїмка накриває лише конверт зі словом людини`() {
        assertEquals(listOf(PeerFrames.Kind.GROUP), hidden - wordless.toSet())
        val notice = PeerAnnounce.notice(
            letter(encoded(PeerFrames.Kind.GROUP)),
            visibleChat = null,
            visibleGroup = null,
        )
        assertEquals("лист у гілку перестав дзвеніти", "виходь", notice?.text)
    }

    @Test
    fun `прихований рід не вимовляється`() {
        val spoken = hidden.filter { PeerOutbox.isSpoken(letter(encoded(it))) }
        assertEquals("прихований рід дійшов до стрічки", emptyList<PeerFrames.Kind>(), spoken)
    }

    @Test
    fun `прихований рід не стає непрочитаним`() {
        val counted = hidden.filter {
            PeerOutbox.unread(listOf(letter(encoded(it))), peerId = "them", readUpToMs = 0L) > 0
        }
        assertEquals("прихований рід рахується непрочитаним", emptyList<PeerFrames.Kind>(), counted)
    }

    /**
     * Негативний контроль самого сторожа: якби [encoded] складав не ті кадри,
     * усе вище зеленіло б на порожньому місці. Слово людини мусить проходити.
     */
    @Test
    fun `слово людини лишається і плашкою, і непрочитаним`() {
        val word = letter("виходимо за пʼять хвилин")
        assertEquals(1, PeerOutbox.unread(listOf(word), peerId = "them", readUpToMs = 0L))
        assertNull(PeerFrames.kindOf(word.body))
        assertEquals(
            word.body,
            PeerAnnounce.notice(word, visibleChat = null, visibleGroup = null)?.text,
        )
    }
}
