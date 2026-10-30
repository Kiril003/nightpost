package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Кадр НОВІШОЇ збірки на старішому апараті.
 *
 * Вада, заради якої це існує, — змішані версії в одній розмові. Збірка 18
 * поклала в дріт `PH-CST:` (стан людини на картці зустрічі). Збірки 15 і 17
 * вирішують «це кадр чи слово» реєстром [PeerFrames.kindOf], а в того реєстру
 * останньою гілкою стояло `else -> null`; [PeerOutbox.isSpoken] так само знав
 * лише свої початки. Тобто старий апарат побачив би `PH-CST:<id>;+;<base64>`
 * бульбашкою, «1 непрочитане» і плашкою в шторці — у груповому конверті теж.
 *
 * Збірку 15 уже не перепишеш: її захищає лише те, що їй такого не шлють (це
 * робота моста, N2). Але з ЦІЄЇ збірки будь-який незнайомий початок форми
 * `PH-<три великі>:` — прихований рід [PeerFrames.Kind.FUTURE]: не рядок
 * розмови, не непрочитане, не плашка, і пальцями не набирається. Наступний
 * новий тег так уже не протече.
 */
class PeerFramesFutureTest {

    /** Початок, якого не знає жодна збірка — «кадр з завтрашньої». */
    private val tomorrow = "PH-QQQ:1788517702898;щось із завтрашньої збірки"

    private fun letter(body: String, from: String = "them"): PeerMessage =
        PeerMessage(id = "m-" + body.hashCode(), fromId = from, toId = "me", body = body, sentAtMs = NOW)

    private val cardState: String = PeerMark.encode(
        PeerMark.Mark(
            PeerMark.What.entries.single { it.mark == "PH-CST:" },
            "card-meetup-1",
            on = true,
            value = "rsvp:yes",
        ),
    )!!

    /**
     * Реєстр збірки 15 (`09126847`, опубліковано в кабінеті) — переписаний
     * сюди ЗАМОРОЖЕНИМ, бо саме таким він лежить на чужих телефонах. Це
     * початки, які її [PeerOutbox.isSpoken] ховав; решту вона показувала
     * словами людини.
     */
    private val build15Hidden = listOf(
        "PH-GRP:", "PH-FCH:", "PH-LIV:", "PH-LVX:", "PH-CAS:", "PH-REA:", "PH-VOT:",
        "PH-HIST:", "PH-PRF:", "PH-WHR:", "PH-EDT:", "PH-UNS:", "PH-RDU:", "PH-TYP:",
    )

    private fun build15Spoken(body: String): Boolean = build15Hidden.none { body.startsWith(it) }

    /**
     * ЧЕРВОНИЙ ЗА ПОБУДОВОЮ: так збірка 15 бачить новий кадр. Сторож тримає
     * факт, через який міст збірки 18 шле `PH-CST:` лише тим, про кого відомо,
     * що вони його знають, — і не дасть забути, чому.
     */
    @Test
    fun `збірка 15 бачить стан картки словом людини`() {
        assertTrue(
            "старий реєстр раптом ховає PH-CST: — тоді вимогу «лише збірці 18+» можна переглянути",
            build15Spoken(cardState),
        )
        assertTrue("і так само кадр з завтрашньої", build15Spoken(tomorrow))
        // Негативний контроль заморожених рядків: свій смайл збірка 15 ховала.
        assertFalse(
            build15Spoken(PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, "x1", true, "❤️"))!!),
        )
    }

    @Test
    fun `незнайомий тег — прихований рід, а не слово`() {
        val kind = PeerFrames.kindOf(tomorrow)
        assertNotNull("незнайомий PH-XXX: досі судиться словом людини", kind)
        assertFalse("незнайомий тег оголошено таким, що доходить до розмови", kind!!.reachesConversation)
        assertFalse("незнайомий тег став рядком розмови", PeerOutbox.isSpoken(letter(tomorrow)))
        assertEquals(
            "незнайомий тег рахується непрочитаним",
            0,
            PeerOutbox.unread(listOf(letter(tomorrow)), peerId = "them", readUpToMs = 0L),
        )
        assertNull(
            "незнайомий тег загримів у шторці",
            PeerAnnounce.notice(letter(tomorrow), visibleChat = null, visibleGroup = null),
        )
    }

    @Test
    fun `незнайомий тег у груповому конверті теж мовчить`() {
        val group = PeerGroup.Group(id = "a1b2c3d4e5f6", name = "вечеря", memberIds = listOf("me", "them"))
        val wrapped = letter(PeerGroup.encode(group, tomorrow))
        assertNull(
            "незнайомий тег у групі загримів у шторці",
            PeerAnnounce.notice(wrapped, visibleChat = null, visibleGroup = null),
        )
        // Негативний контроль: слово в тому самому конверті дзвенить.
        assertEquals(
            "о сьомій",
            PeerAnnounce.notice(letter(PeerGroup.encode(group, "о сьомій")), null, null)?.text,
        )
    }

    @Test
    fun `незнайомий тег пальцями не набирається`() {
        assertNotNull(PeerFrames.refuseTyped(tomorrow))
        assertNotNull("пробіл попереду проніс тег", PeerFrames.refuseTyped("  " + tomorrow))
    }

    /**
     * Чого заслін НЕ сміє зачепити. `PH-PAY:` — переказ у розмові: це СЛОВО
     * (картка з сумою), його складає `core-solana`, якого `core-net` не бачить,
     * і міст розбирає його лише тоді, коли реєстр сказав «не кадр». Решта —
     * інша форма, ніж три великі літери: позначки карток композера, відповідь
     * помічника, слова людини.
     */
    @Test
    fun `переказ і слова лишаються словами`() {
        val said = listOf(
            "PH-PAY:1;devnet;usdc;2500000;x;y",
            "PH-POLL:куди;ліворуч;праворуч",
            "PH-AI:чернетка",
            "PH-abc:малими",
            "PH-QQ:дві",
            "PH-QQQQ:чотири",
            "PH-QQQ без двокрапки",
            "виходимо о сьомій, PH-QQQ:1 — це не кадр",
        )
        said.forEach { body ->
            assertNull("слово визнано кадром: $body", PeerFrames.kindOf(body))
            assertTrue("слово сховано з розмови: $body", PeerOutbox.isSpoken(letter(body)))
        }
    }

    /**
     * Знайомий тег із покаліченим тілом лишається тим, чим був: заслін
     * стосується лише НЕЗНАЙОМИХ тегів, інакше він мовчки змінив би долю
     * вже відомих родів.
     */
    @Test
    fun `знайомі теги судяться своїми, а не заслоном`() {
        PeerFrames.Kind.entries.filter { it != PeerFrames.Kind.FUTURE }.forEach { kind ->
            assertFalse(
                "заслін на майбутнє накрив знайомий тег ${kind.mark}",
                PeerFrames.isFuture(kind.mark + "1"),
            )
        }
        assertFalse(PeerFrames.isFuture("PH-PAY:1"))
    }
}
