package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Розмова ДВОХ після того, як лінія стала кімнатою.
 *
 * Кімната на багато ніг ([PeerCallRoomTest]) не має права зіпсувати випадок,
 * яким користуються щодня. Тут ті самі перевірки, що були до 16.09, слово в
 * слово за змістом: апарат дзвонить, «зайнято» відповідає, чужий кадр не
 * проходить, розмова не має стелі часу. Перекладено лише на кімнату з ОДНІЄЮ
 * ногою — бо саме нею розмова двох тепер і є.
 *
 * Рішень тут навмисно мало: рушій живе у вебі, і друга його копія в котліні
 * була б двома правдами про один дзвінок. Лишилось рівно те, чого веб зробити
 * не може, бо його немає на екрані.
 */
class PeerCallLineTest {

    private val them = "marta-fp"
    private val stranger = "hto-to-fp"
    private val callId = "call_9f31ac2b"
    private val other = "call_77bbee01"

    private fun frame(
        what: PeerCall.Signalling,
        id: String = callId,
        payload: String = """{"call_id":"$id","kind":"${what.webKind}"}""",
    ) = PeerCall.encodeSignal(PeerCall.Signal(id, what, payload))!!

    /** Кімната з однією ногою — те, чим була стара `Line`. */
    private fun one(foot: PeerCallLine.Foot, who: String = them, at: Long = NOW) =
        PeerCallLine.Room(callId, null, listOf(PeerCallLine.Leg(who, foot, at)))

    @Test
    fun `пропозиція підіймає апарат, і тіло їде рушієві як є`() {
        val payload = """{"call_id":"$callId","kind":"offer","sdp":"v=0\r\n","media":"audio"}"""
        val v = PeerCallLine.apply(null, them, frame(PeerCall.Signalling.OFFER, payload = payload), NOW)
        val ringing = PeerCallLine.face(v.room) as PeerCallLine.Line.Ringing

        assertTrue(v.changed)
        assertEquals(them, ringing.peerId)
        assertEquals(callId, ringing.callId)
        assertEquals("рушій отримав не те тіло, яке склав рушій на тому боці", payload, v.toWeb)
        assertTrue(v.out.isEmpty())
    }

    /** «Зайнято» — відповідь, а не тиша: інакше той бік дзвонить у порожнечу. */
    @Test
    fun `друге запрошення з ІНШИМ номером отримує зайнято і не чіпає живого`() {
        val live = one(PeerCallLine.Foot.TALKING)
        val v = PeerCallLine.apply(live, stranger, frame(PeerCall.Signalling.OFFER, other), NOW)

        assertEquals("живу розмову зачепило чуже запрошення", live, v.room)
        assertNull("чуже тіло дійшло до рушія посеред розмови", v.toWeb)
        assertEquals(stranger, v.out.single().toPeerId)
        assertEquals(
            PeerCallLine.Reply(other, PeerCall.Signalling.HANGUP, PeerCallLine.BUSY),
            v.out.single().reply,
        )
    }

    /**
     * Повторна пропозиція того самого дзвінка — це перезапуск ICE, а не новий
     * дзвінок: рушій мусить її отримати, а апарат — не задзвонити вдруге.
     */
    @Test
    fun `повторна пропозиція доходить до рушія і не дзвонить удруге`() {
        val talking = one(PeerCallLine.Foot.TALKING)
        val v = PeerCallLine.apply(talking, them, frame(PeerCall.Signalling.OFFER), NOW + 9_000L)

        assertEquals(talking, v.room)
        assertFalse("перезапуск ICE полічився новим дзвінком", v.changed)
        assertNotNull("перезапуск ICE не дійшов до рушія", v.toWeb)
        assertTrue("собі ж відповіли «зайнято»", v.out.isEmpty())
    }

    /**
     * Найдешевша атака з реєстру: покласти чужу розмову вигаданим номером. Без
     * звірки номера й наявності ноги вона коштувала б один кадр.
     */
    @Test
    fun `чужий кадр не кладе розмови і не доходить до рушія`() {
        val talking = one(PeerCallLine.Foot.TALKING)
        for (what in listOf(
            PeerCall.Signalling.HANGUP,
            PeerCall.Signalling.ICE,
            PeerCall.Signalling.ANSWER,
            PeerCall.Signalling.RADIO,
        )) {
            val byStranger = PeerCallLine.apply(talking, stranger, frame(what), NOW + 5_000L)
            assertEquals("чужий поклав нашу розмову кадром $what", talking, byStranger.room)
            assertNull("чуже тіло дійшло до рушія: $what", byStranger.toWeb)

            val wrongId = PeerCallLine.apply(talking, them, frame(what, other), NOW + 5_000L)
            assertEquals("кадр іншого дзвінка зачепив цей: $what", talking, wrongId.room)
            assertNull("тіло іншого дзвінка дійшло до рушія: $what", wrongId.toWeb)
        }
    }

    @Test
    fun `сигнал у спокої не доходить до рушія взагалі`() {
        for (what in PeerCall.Signalling.entries.filter { it != PeerCall.Signalling.OFFER }) {
            val v = PeerCallLine.apply(null, them, frame(what), NOW)
            assertNull("кімната завелась не з пропозиції: $what", v.room)
            assertNull("рушій отримав опис розмови, якої немає: $what", v.toWeb)
        }
    }

    @Test
    fun `взяли слухавку — нога йде за рушієм`() {
        val dialing = one(PeerCallLine.Foot.DIALING)
        val v = PeerCallLine.apply(dialing, them, frame(PeerCall.Signalling.ANSWER), NOW + 900L)
        val talking = PeerCallLine.face(v.room) as PeerCallLine.Line.Talking

        assertEquals(NOW + 900L, talking.sinceMs)
        assertNotNull("відповідь не дійшла до рушія", v.toWeb)
    }

    /** Наш веб веде, кімната йде слідом — інакше дві правди розійшлись би. */
    @Test
    fun `надісланий нами кадр веде ногу`() {
        val dialed = PeerCallLine.noteSent(
            null, them, callId, PeerCall.Signalling.OFFER, NOW,
        ).room
        assertEquals(PeerCallLine.Line.Dialing(callId, them, NOW), PeerCallLine.face(dialed))

        val took = PeerCallLine.noteSent(
            one(PeerCallLine.Foot.RINGING), them, callId, PeerCall.Signalling.ANSWER, NOW + 4_000L,
        ).room
        assertTrue(
            "«взяв» не зняв дзвінка з плашки",
            PeerCallLine.face(took) is PeerCallLine.Line.Talking,
        )

        val over = PeerCallLine.noteSent(
            one(PeerCallLine.Foot.TALKING), them, callId, PeerCall.Signalling.HANGUP, NOW + 8_000L,
        ).room
        assertTrue(PeerCallLine.face(over) is PeerCallLine.Line.Over)

        // Кандидат нічого не вирішує — і не має міняти кімнати.
        val same = one(PeerCallLine.Foot.TALKING)
        assertEquals(
            same,
            PeerCallLine.noteSent(same, them, callId, PeerCall.Signalling.ICE, NOW).room,
        )
    }

    /**
     * Єдина дія дзвінка, яку телефон робить сам: «взяти» вимагає мікрофона й
     * рушія, тобто екрана, а «відхилити» — рівно одного кадру.
     */
    @Test
    fun `відхилити можна не відкриваючи застосунку`() {
        val v = PeerCallLine.decline(one(PeerCallLine.Foot.RINGING))!!
        assertEquals(
            PeerCallLine.Reply(callId, PeerCall.Signalling.HANGUP, PeerCallLine.DECLINED),
            v.out.single().reply,
        )
        assertEquals(
            PeerCallLine.DECLINED,
            (PeerCallLine.face(v.room) as PeerCallLine.Line.Over).why,
        )
        assertNull(
            "«відхилити» запропоновано там, де нічого не дзвонить",
            PeerCallLine.decline(one(PeerCallLine.Foot.TALKING)),
        )
    }

    @Test
    fun `ніхто не підійшов — нога гасне сама і каже про це`() {
        val ringing = one(PeerCallLine.Foot.RINGING)
        assertNull(
            "нога згасла раніше межі",
            PeerCallLine.expire(ringing, NOW + PeerCall.RING_LIFE_MS - 1),
        )
        val gone = PeerCallLine.expire(ringing, NOW + PeerCall.RING_LIFE_MS)!!
        assertEquals(
            PeerCallLine.MISSED,
            (PeerCallLine.face(gone.room) as PeerCallLine.Line.Over).why,
        )
        assertEquals(them, gone.out.single().toPeerId)
        assertNull(
            "розмову, яку вже ведуть, нема чому гасити за часом",
            PeerCallLine.expire(one(PeerCallLine.Foot.TALKING), NOW + 600_000L),
        )
    }

    /**
     * Власник просив стелю часу назвати вголос, якщо вона є, — а тут її НЕМАЄ,
     * і це доведено, а не переказано з коментаря. [PeerCall.RING_LIFE_MS] —
     * хвилина ДЗВОНІННЯ; щойно нога бере слухавку, [PeerCallLine.expire]
     * повертає `null` для БУДЬ-ЯКОЇ відстані в часі — перевірено роком уперед,
     * не лише хвилинами. Хтось, хто згодом впише таймаут «про всяк випадок»,
     * почервонить саме тут.
     */
    @Test
    fun `розмова не має стелі часу — рік не кладе лінії`() {
        val talking = one(PeerCallLine.Foot.TALKING)
        val aYearLater = NOW + 365L * 24 * 60 * 60 * 1000
        assertNull("рік розмови поклав лінію сам собою", PeerCallLine.expire(talking, aYearLater))
        assertNull(
            "найдальша можлива мить усе одно поклала розмову",
            PeerCallLine.expire(talking, Long.MAX_VALUE),
        )
    }

    /**
     * Дзвінок у гілку веде облік ногами ЛЮДЕЙ, а `group.id` лишається на
     * кімнаті — саме його й показує шторка, бо обрізаний хеш однієї людини
     * замість назви розмови був би неправдою про те, куди дзвонять.
     */
    @Test
    fun `гілка живе на кімнаті, а ноги лишаються людьми`() {
        val groupId = "abc123abc123"
        val room = PeerCallLine.noteSent(
            null, them, callId, PeerCall.Signalling.OFFER, NOW, groupId = groupId,
        ).room
        assertEquals(groupId, room!!.groupId)
        assertEquals(them, room.legs.single().peerId)
        assertEquals(groupId, PeerCallLine.peerOf(PeerCallLine.face(room)))
    }
}
