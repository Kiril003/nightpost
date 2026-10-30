package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Стан людини на картці зустрічі чи рахунку — позначка `PH-CST:`
 * (`docs/scene/MEETUP_CONTRACT.md` §2).
 *
 * «Іду», «буду о 19:10», «я їв борщ», «лишити групу», «я на місці» — це не
 * листи і не поле в тілі картки: картку склав один, а позначки кладуть усі
 * учасники й пізніше. Тому механіка та сама, що в смайла: кадр їде журналом,
 * а міст згортає його в `card_marks` на рядку картки. Сенс значень знає лише
 * веб; дріт — німий перевізник.
 *
 * Рід береться за позначкою з договору (`PH-CST:`), а не за іменем константи:
 * договір написано раніше за код, і сторож стоїть саме на дроті.
 */
class PeerCardStateMarkTest {

    /** Id рядка картки — такий, яким його мінить PeerMesh. */
    private val card = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2"

    private val cardState: PeerMark.What
        get() = PeerMark.What.entries.single { it.mark == "PH-CST:" }

    private fun frame(value: String, on: Boolean = true, targetId: String = card): String =
        PeerMark.encode(PeerMark.Mark(cardState, targetId, on, value))!!

    private fun cast(from: String, at: Long, value: String, on: Boolean = true): PeerMessage =
        PeerMessage(id = "$from-$at", fromId = from, toId = "me", body = frame(value, on), sentAtMs = at)

    /** Кожне значення з таблиці договору — з найдовшим допустимим id страви. */
    private val contract = listOf(
        "rsvp:yes",
        "rsvp:no",
        "eta:${(NOW / 60_000L)}",
        "dish:" + "D".repeat(36),
        "keep:1",
        "here:1",
    )

    @Test
    fun `кожне значення договору доїжджає слово в слово`() {
        contract.forEach { value ->
            listOf(true, false).forEach { on ->
                val wire = frame(value, on)
                assertTrue("стан картки їде не своєю позначкою: $wire", wire.startsWith("PH-CST:$card;"))
                assertEquals(
                    "«$value» доїхало іншим",
                    PeerMark.Mark(cardState, card, on, value),
                    PeerMark.decode(wire),
                )
            }
        }
    }

    /**
     * Межа — 64 байти UTF-8, як у смайла (`MAX_MARK_BYTES` у `types/meetup.ts`).
     * Задовге значення відмовляє ДО сокета: зрізаний на дроті кадр не
     * відмовляється, а мовчки не доїжджає.
     */
    @Test
    fun `межа значення та сама, що в договорі`() {
        assertEquals(64, PeerMark.MAX_VALUE_BYTES)
        val fits = "dish:" + "x".repeat(PeerMark.MAX_VALUE_BYTES - 5)
        assertNotNull("значення рівно під стелею не поїхало", PeerMark.encode(PeerMark.Mark(cardState, card, true, fits)))
        assertNull(
            "значення над стелею поїхало — зріжеться на дроті",
            PeerMark.encode(PeerMark.Mark(cardState, card, true, fits + "x")),
        )
    }

    /**
     * Стан картки — не лист. Окремий рядок «rsvp:yes» у стрічці, плашка в
     * шторці й «1 чекає» в смузі — три неправди про один дотик. І в групі
     * теж: зустріч живе саме в групі, і кадр туди їде в груповому конверті.
     */
    @Test
    fun `стан картки не стає рядком, не дзвонить і не чекає`() {
        val wire = frame("rsvp:yes")
        val kind = PeerFrames.kindOf(wire)
        assertNotNull("реєстр не знає стану картки — він поїхав би повз заслони", kind)
        assertEquals("PH-CST:", kind!!.mark)
        assertFalse("стан картки оголошено реплікою", kind.reachesConversation)

        val letter = PeerMessage(id = "m", fromId = "them", toId = "me", body = wire, sentAtMs = NOW)
        assertFalse("стан картки став рядком розмови", PeerOutbox.isSpoken(letter))
        assertEquals("стан картки порахувався непрочитаним", 0, PeerOutbox.unread(listOf(letter), "them", 0L))
        assertEquals("стан картки порахувався листом, що чекає", 0, PeerOutbox.waiting(listOf(letter)))
        assertNull("стан картки загримів у шторці", PeerAnnounce.notice(letter, null, null))

        val group = PeerGroup.Group(id = "a1b2c3d4e5f6", name = "Кафе Ікс · 19:00", memberIds = listOf("me", "them"))
        val inGroup = letter.copy(id = "g", body = PeerGroup.encode(group, wire))
        assertNull("стан картки в групі загримів у шторці", PeerAnnounce.notice(inGroup, null, null))
    }

    /** Позначку пристрою не можна набрати пальцями — інакше «іду» за людину пише будь-хто текстом. */
    @Test
    fun `стан картки не набирається пальцями`() {
        val wire = frame("here:1")
        assertEquals(PeerFrames.kindOf(wire), PeerFrames.refuseTyped(" $wire\n"))
        assertNotNull(PeerFrames.refuseTyped(wire))
    }

    /**
     * Згортання — те саме, що в смайла, без правила «один вибір»: людина їла
     * дві страви, і обидві мусять лишитись за нею. Зміну «іду» на «не йду» веб
     * робить двома кадрами (зняв старе, поставив нове), і згортач це показує.
     */
    @Test
    fun `згортання тримає багато значень на автора й знімає лише зняте`() {
        val log = listOf(
            cast("olena", 10, "dish:borshch"),
            cast("olena", 20, "dish:varenyky"),
            cast("petro", 30, "dish:borshch"),
            cast("olena", 40, "rsvp:yes"),
            cast("olena", 50, "rsvp:yes", on = false),
            cast("olena", 60, "rsvp:no"),
            cast("petro", 70, "dish:borshch", on = false),
        )
        assertEquals(
            mapOf(
                "dish:borshch" to listOf("olena"),
                "dish:varenyky" to listOf("olena"),
                "rsvp:no" to listOf("olena"),
            ),
            PeerMark.foldAll(log, cardState)[card],
        )
    }

    /** Смайл і стан на тому самому рядку не змішуються: ❤️ — не «я їв», «rsvp:yes» — не смайл. */
    @Test
    fun `стан картки й смайл на одному рядку не змішуються`() {
        val heart = PeerMessage(
            id = "h",
            fromId = "olena",
            toId = "me",
            body = PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, card, true, "❤️"))!!,
            sentAtMs = 5,
        )
        val log = listOf(heart, cast("olena", 10, "rsvp:yes"))
        assertEquals(mapOf("rsvp:yes" to listOf("olena")), PeerMark.foldAll(log, cardState)[card])
        assertEquals(mapOf("❤️" to listOf("olena")), PeerMark.foldAll(log, PeerMark.What.REACTION)[card])
    }
}
