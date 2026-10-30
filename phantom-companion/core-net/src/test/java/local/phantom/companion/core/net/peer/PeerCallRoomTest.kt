package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Конференція: кімната тримає КІЛЬКА ніг одночасно.
 *
 * Те, чого не було до 16.09, і те, через що групового дзвінка не існувало як
 * явища. Старий `Line` тримав один `peerId`, і `apply` звіряла пару
 * `(callId, peerId)`; ДРУГИЙ учасник тієї самої розмови отримував «зайнято»
 * тим самим рядком, яким його отримував чужий номер. Кожен тест нижче
 * червонів би на тому коді.
 */
class PeerCallRoomTest {

    private val a = "marta-fp"
    private val b = "taras-fp"
    private val c = "olena-fp"
    private val stranger = "hto-to-fp"
    private val callId = "call_9f31ac2b"
    private val other = "call_77bbee01"

    private fun frame(
        what: PeerCall.Signalling,
        id: String = callId,
        payload: String = """{"call_id":"$id","kind":"${what.webKind}"}""",
    ) = PeerCall.encodeSignal(PeerCall.Signal(id, what, payload))!!

    private fun room(vararg legs: PeerCallLine.Leg, group: String? = null) =
        PeerCallLine.Room(callId, group, legs.toList())

    private fun leg(
        who: String,
        foot: PeerCallLine.Foot,
        at: Long = NOW,
    ) = PeerCallLine.Leg(who, foot, at)

    // ── ГОЛОВНЕ: третій більше не «зайнято» ──────────────────────────────

    /**
     * Той самий номер дзвінка від ІНШОГО апарата — це друга нога, а не другий
     * дзвінок. Рівно цей тест і був неможливий на одній нозі.
     */
    @Test
    fun `третій учасник дістає СВОЮ ногу, а не «зайнято»`() {
        val live = room(leg(a, PeerCallLine.Foot.TALKING))
        val v = PeerCallLine.apply(live, b, frame(PeerCall.Signalling.OFFER), NOW + 3_000L)

        assertTrue("нова нога полічилась як «нічого не сталось»", v.changed)
        assertTrue("другій нозі відповіли «зайнято»", v.out.isEmpty())
        assertNotNull("опис другої ноги не дійшов до рушія", v.toWeb)
        assertEquals(2, PeerCallLine.liveLegs(v.room).size)
        assertEquals(
            PeerCallLine.Foot.RINGING,
            PeerCallLine.legOf(v.room, b)!!.foot,
        )
        assertEquals(
            "перша нога постраждала від появи другої",
            PeerCallLine.Foot.TALKING,
            PeerCallLine.legOf(v.room, a)!!.foot,
        )
    }

    /** Повна сітка: кожен приходить своєю ногою, і всі вони живуть поруч. */
    @Test
    fun `кімната тримає всі ноги одночасно`() {
        var cur: PeerCallLine.Room? = null
        for ((i, who) in listOf(a, b, c).withIndex()) {
            val v = PeerCallLine.apply(cur, who, frame(PeerCall.Signalling.OFFER), NOW + i * 1_000L)
            cur = v.room
        }
        assertEquals(3, PeerCallLine.liveLegs(cur).size)
        assertEquals(listOf(a, b, c), PeerCallLine.liveLegs(cur).map { it.peerId })
        assertEquals(callId, cur!!.callId)
    }

    /**
     * Вихід одного не кладе розмови решті. До 16.09 «поклав» від будь-кого
     * гасив єдину лінію, тобто вимикав конференцію всім, хто лишався.
     */
    @Test
    fun `той, хто вийшов, забирає лише свою ногу`() {
        val live = room(
            leg(a, PeerCallLine.Foot.TALKING),
            leg(b, PeerCallLine.Foot.TALKING),
            leg(c, PeerCallLine.Foot.TALKING),
        )
        val v = PeerCallLine.apply(live, b, frame(PeerCall.Signalling.HANGUP), NOW + 30_000L)

        assertEquals(PeerCallLine.Foot.GONE, PeerCallLine.legOf(v.room, b)!!.foot)
        assertEquals(2, PeerCallLine.liveLegs(v.room).size)
        assertTrue("кімната померла разом з однією ногою", PeerCallLine.isLive(v.room))
        assertTrue(
            "розмова перестала бути розмовою",
            PeerCallLine.face(v.room) is PeerCallLine.Line.Talking,
        )
        assertNotNull("рушій не дізнався, що цю ногу треба зняти", v.toWeb)
    }

    /** Коли пішли ВСІ — кімната закінчилась, і це видно проєкцією. */
    @Test
    fun `кімната кінчається, коли живих ніг не лишилось`() {
        var cur: PeerCallLine.Room? = room(
            leg(a, PeerCallLine.Foot.TALKING),
            leg(b, PeerCallLine.Foot.TALKING),
        )
        cur = PeerCallLine.apply(cur, a, frame(PeerCall.Signalling.HANGUP), NOW + 1).room
        assertTrue(PeerCallLine.isLive(cur))
        cur = PeerCallLine.apply(cur, b, frame(PeerCall.Signalling.HANGUP), NOW + 2).room
        assertFalse(PeerCallLine.isLive(cur))
        assertTrue(PeerCallLine.face(cur) is PeerCallLine.Line.Over)
    }

    // ── межі, які лишились правильними ───────────────────────────────────

    /** ІНШИЙ номер дзвінка — це справді друга розмова, і «зайнято» тут правда. */
    @Test
    fun `інший номер дзвінка отримує зайнято`() {
        val live = room(leg(a, PeerCallLine.Foot.TALKING))
        val v = PeerCallLine.apply(live, stranger, frame(PeerCall.Signalling.OFFER, other), NOW)

        assertEquals("живу кімнату зачепила чужа розмова", live, v.room)
        assertNull("чуже тіло дійшло до рушія посеред розмови", v.toWeb)
        assertEquals(
            listOf(
                PeerCallLine.Out(
                    stranger,
                    PeerCallLine.Reply(other, PeerCall.Signalling.HANGUP, PeerCallLine.BUSY),
                ),
            ),
            v.out,
        )
    }

    /**
     * Стеля ніг — заміряне число, і вона відмовляє СВОЇМ словом.
     *
     * «Зайнято» тут було б брехнею: чужої розмови немає, є межа апарата, і
     * людина, яка прочитає «зайнято», шукатиме неіснуючий другий дзвінок.
     */
    @Test
    fun `девʼята нога не влазить і чує саме «кімната повна»`() {
        val full = PeerCallLine.Room(
            callId,
            null,
            (1..PeerCallLine.MAX_LEGS).map { leg("peer-$it", PeerCallLine.Foot.TALKING) },
        )
        assertEquals(0, PeerCallLine.roomLeft(full))
        val v = PeerCallLine.apply(full, stranger, frame(PeerCall.Signalling.OFFER), NOW + 5_000L)

        assertEquals("переповнена кімната взяла ще одну ногу", full, v.room)
        assertNull(v.toWeb)
        assertEquals(PeerCallLine.FULL, v.out.single().reply.reason)
        assertEquals(stranger, v.out.single().toPeerId)
    }

    /** Попередження — за крок до межі, а не в мить відмови. */
    @Test
    fun `кімната каже, що вона близько до стелі, ДО відмови`() {
        assertTrue(PeerCallLine.CROWDED_LEGS < PeerCallLine.MAX_LEGS)
        val roomy = PeerCallLine.Room(
            callId,
            null,
            (1 until PeerCallLine.CROWDED_LEGS).map { leg("peer-$it", PeerCallLine.Foot.TALKING) },
        )
        assertFalse(PeerCallLine.isCrowded(roomy))
        val crowded = roomy.copy(legs = roomy.legs + leg("peer-crowd", PeerCallLine.Foot.TALKING))
        assertTrue(PeerCallLine.isCrowded(crowded))
        assertTrue("місця вже немає там, де ще має бути", PeerCallLine.roomLeft(crowded) > 0)
    }

    /** Найдешевша атака з реєстру: покласти чужу кімнату вигаданим номером. */
    @Test
    fun `чужий кадр не кладе кімнати і не доходить до рушія`() {
        val live = room(leg(a, PeerCallLine.Foot.TALKING))
        for (what in listOf(
            PeerCall.Signalling.HANGUP,
            PeerCall.Signalling.ICE,
            PeerCall.Signalling.ANSWER,
            PeerCall.Signalling.RADIO,
        )) {
            val byStranger = PeerCallLine.apply(live, stranger, frame(what), NOW + 5_000L)
            assertEquals("чужий поклав нашу кімнату кадром $what", live, byStranger.room)
            assertNull("чуже тіло дійшло до рушія: $what", byStranger.toWeb)

            val wrongId = PeerCallLine.apply(live, a, frame(what, other), NOW + 5_000L)
            assertEquals("кадр іншої кімнати зачепив цю: $what", live, wrongId.room)
            assertNull("тіло іншої кімнати дійшло до рушія: $what", wrongId.toWeb)
        }
    }

    /** Нога, яку забрали, більше не приймає кадрів — інакше вона не забрана. */
    @Test
    fun `мертва нога не пропускає кадрів до рушія`() {
        val live = room(
            leg(a, PeerCallLine.Foot.TALKING),
            PeerCallLine.Leg(b, PeerCallLine.Foot.GONE, NOW, why = PeerCallLine.HUNG_UP),
        )
        for (what in listOf(PeerCall.Signalling.ICE, PeerCall.Signalling.RADIO, PeerCall.Signalling.HANGUP)) {
            val v = PeerCallLine.apply(live, b, frame(what), NOW + 1_000L)
            assertNull("мертва нога пустила $what до рушія", v.toWeb)
            assertEquals(live, v.room)
        }
    }

    // ── час ──────────────────────────────────────────────────────────────

    /**
     * Гасне НОГА, не кімната. Учасник, який не взяв слухавку, не має права
     * покласти розмову тим, хто взяв.
     */
    @Test
    fun `нога, яку не взяли, гасне сама і не чіпає розмови`() {
        val mixed = room(
            leg(a, PeerCallLine.Foot.TALKING),
            leg(b, PeerCallLine.Foot.DIALING),
            leg(c, PeerCallLine.Foot.DIALING),
        )
        assertNull(
            "нога згасла раніше межі",
            PeerCallLine.expire(mixed, NOW + PeerCall.RING_LIFE_MS - 1),
        )
        val v = PeerCallLine.expire(mixed, NOW + PeerCall.RING_LIFE_MS)!!

        assertEquals(PeerCallLine.Foot.TALKING, PeerCallLine.legOf(v.room, a)!!.foot)
        assertEquals(PeerCallLine.MISSED, PeerCallLine.legOf(v.room, b)!!.why)
        assertEquals(PeerCallLine.MISSED, PeerCallLine.legOf(v.room, c)!!.why)
        // Кожній нозі — свій кадр: одна відповідь на всіх загубила б решту.
        assertEquals(setOf(b, c), v.out.map { it.toPeerId }.toSet())
        assertTrue("розмова померла разом із тими, хто не взяв", PeerCallLine.isLive(v.room))
    }

    /**
     * Власник просив стелю часу назвати вголос, якщо вона є, — а її НЕМАЄ, і
     * тепер це доведено для КІМНАТИ, а не лише для однієї лінії. Хтось, хто
     * згодом впише таймаут «про всяк випадок», почервонить саме тут.
     */
    @Test
    fun `розмова не має стелі часу — рік не кладе жодної ноги`() {
        val talking = room(
            leg(a, PeerCallLine.Foot.TALKING),
            leg(b, PeerCallLine.Foot.TALKING),
            leg(c, PeerCallLine.Foot.TALKING),
        )
        val aYearLater = NOW + 365L * 24 * 60 * 60 * 1000
        assertNull("рік розмови поклав кімнату сам собою", PeerCallLine.expire(talking, aYearLater))
        assertNull(
            "найдальша можлива мить усе одно поклала розмову",
            PeerCallLine.expire(talking, Long.MAX_VALUE),
        )
        assertEquals(3, PeerCallLine.liveLegs(talking).size)
    }

    // ── те, що робить цей бік ────────────────────────────────────────────

    /**
     * Дзвінок у гілку — це N ніг, а не одна нога на `group.id`. Саме в цьому
     * місці стара `postToGroup` і ставила одну.
     */
    @Test
    fun `запрошення гілки заводить ногу на кожного`() {
        var cur: PeerCallLine.Room? = null
        for (who in listOf(a, b, c)) {
            cur = PeerCallLine.noteSent(
                cur, who, callId, PeerCall.Signalling.OFFER, NOW, groupId = "grp1",
            ).room
        }
        assertEquals(3, PeerCallLine.liveLegs(cur).size)
        assertTrue(PeerCallLine.liveLegs(cur).all { it.foot == PeerCallLine.Foot.DIALING })
        assertEquals("гілку загублено — шторка покаже хеш замість назви", "grp1", cur!!.groupId)
        // Шторка бачить гілку, а не першого-ліпшого з трьох.
        assertEquals("grp1", PeerCallLine.peerOf(PeerCallLine.face(cur)))
    }

    /** Кожна нога відповідає за себе: один узяв — інші ще дзвонять. */
    @Test
    fun `відповідь піднімає лише свою ногу`() {
        val dialing = room(
            leg(a, PeerCallLine.Foot.DIALING),
            leg(b, PeerCallLine.Foot.DIALING),
        )
        val v = PeerCallLine.apply(dialing, a, frame(PeerCall.Signalling.ANSWER), NOW + 900L)
        assertEquals(PeerCallLine.Foot.TALKING, PeerCallLine.legOf(v.room, a)!!.foot)
        assertEquals(PeerCallLine.Foot.DIALING, PeerCallLine.legOf(v.room, b)!!.foot)
        assertNotNull("відповідь не дійшла до рушія", v.toWeb)
    }

    /**
     * Перезапуск ICE не має відсувати годинник: інакше «не взяли» не наставало
     * б ніколи, поки рушій шле нові описи.
     */
    @Test
    fun `повторний опис тієї самої ноги не відсуває хвилини`() {
        val dialing = room(leg(a, PeerCallLine.Foot.DIALING))
        val again = PeerCallLine.noteSent(
            dialing, a, callId, PeerCall.Signalling.OFFER, NOW + 40_000L,
        )
        assertFalse(again.changed)
        assertEquals(NOW, PeerCallLine.legOf(again.room, a)!!.sinceMs)
        assertNotNull(
            "нога пережила хвилину лише тому, що їй знову надіслали опис",
            PeerCallLine.expire(again.room, NOW + PeerCall.RING_LIFE_MS),
        )
    }

    /** Вихід — кожній живій нозі свій кадр, а не одне «поклав» у порожнечу. */
    @Test
    fun `вихід із кімнати кладе кадр КОЖНІЙ живій нозі`() {
        val live = room(
            leg(a, PeerCallLine.Foot.TALKING),
            leg(b, PeerCallLine.Foot.RINGING),
            PeerCallLine.Leg(c, PeerCallLine.Foot.GONE, NOW, why = PeerCallLine.MISSED),
        )
        val v = PeerCallLine.leave(live)!!
        assertEquals(setOf(a, b), v.out.map { it.toPeerId }.toSet())
        assertFalse("кімната пережила власний вихід", PeerCallLine.isLive(v.room))
        assertNull("виходити нема звідки, а щось повернулось", PeerCallLine.leave(v.room))
    }

    /** Забрати одну ногу — решта розмови мусить це пережити. */
    @Test
    fun `можна забрати одну ногу, не роняючи решти`() {
        val live = room(
            leg(a, PeerCallLine.Foot.TALKING),
            leg(b, PeerCallLine.Foot.TALKING),
        )
        val v = PeerCallLine.drop(live, b)!!
        assertEquals(listOf(b), v.out.map { it.toPeerId })
        assertEquals(1, PeerCallLine.liveLegs(v.room).size)
        assertNull("ту саму ногу забрали двічі", PeerCallLine.drop(v.room, b))
    }

    /** Відмова стосується ДЗВІНКА, а не одного апарата в ньому. */
    @Test
    fun `відхилити — це відхилити всіх, хто дзвонить`() {
        val ringing = room(
            leg(a, PeerCallLine.Foot.RINGING),
            leg(b, PeerCallLine.Foot.RINGING),
        )
        val v = PeerCallLine.decline(ringing)!!
        assertEquals(setOf(a, b), v.out.map { it.toPeerId }.toSet())
        assertTrue(v.out.all { it.reply.reason == PeerCallLine.DECLINED })
        assertNull(
            "«відхилити» запропоновано посеред розмови",
            PeerCallLine.decline(room(leg(a, PeerCallLine.Foot.TALKING))),
        )
    }

    // ── проєкція ─────────────────────────────────────────────────────────

    /**
     * Шторка показує одне, і це одне мусить бути найважчим станом. Інакше
     * апарат дзвенів би людині, яка САМЕ ЗАРАЗ говорить, щойно в кімнату
     * заходить наступний.
     */
    @Test
    fun `нова нога посеред розмови не дзвенить`() {
        val talking = room(leg(a, PeerCallLine.Foot.TALKING))
        val v = PeerCallLine.apply(talking, b, frame(PeerCall.Signalling.OFFER), NOW + 10_000L)
        assertTrue(
            "апарат задзвонив посеред живої розмови",
            PeerCallLine.face(v.room) is PeerCallLine.Line.Talking,
        )
    }

    /** Наше «набираю» важить більше за зустрічну пропозицію учасника сітки. */
    @Test
    fun `зустрічна пропозиція в набраній кімнаті не стає вхідним дзвінком`() {
        val dialing = PeerCallLine.noteSent(
            null, a, callId, PeerCall.Signalling.OFFER, NOW, groupId = "grp1",
        ).room
        val v = PeerCallLine.apply(dialing, b, frame(PeerCall.Signalling.OFFER), NOW + 400L)
        assertTrue(
            "рукостискання сітки стало другим вхідним дзвінком",
            PeerCallLine.face(v.room) is PeerCallLine.Line.Dialing,
        )
    }

    /** Порожня кімната — це Idle, а не «щось». */
    @Test
    fun `порожнеча проєктується в Idle`() {
        assertEquals(PeerCallLine.Line.Idle, PeerCallLine.face(null))
        assertEquals(0, PeerCallLine.legsOf(null).size)
        assertEquals(PeerCallLine.MAX_LEGS, PeerCallLine.roomLeft(null))
        assertFalse(PeerCallLine.isLive(null))
    }
}
