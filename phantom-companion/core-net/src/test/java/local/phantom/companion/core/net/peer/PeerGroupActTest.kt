package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Склад групи — це не місцева правка, а підписаний лист, і саме тому його
 * правила варто доводити без телефонів: тут перевіряється те, що на двох
 * пристроях побачити майже неможливо, — хто має право змінити склад, що
 * стається з тим, кого прибрали, і чи можна вийти, коли мережі немає.
 */
class PeerGroupActTest {

    private val me = "aaaa000000000001"
    private val olia = "bbbb000000000002"
    private val taras = "cccc000000000003"
    private val marko = "dddd000000000004"

    private val id = "abc123abc123"

    private fun group(
        name: String = "Виїзд",
        members: List<String> = listOf(me, olia, taras),
    ) = PeerGroup.Group(id = id, name = name, memberIds = members)

    private fun msg(
        body: String,
        fromId: String,
        sentAtMs: Long,
        toId: String = me,
    ) = PeerMessage(
        id = "$fromId-$toId-$sentAtMs-${body.hashCode()}",
        fromId = fromId,
        toId = toId,
        body = body,
        sentAtMs = sentAtMs,
    )

    private fun act(
        group: PeerGroup.Group,
        act: PeerGroup.Act,
        fromId: String,
        sentAtMs: Long,
        newName: String = group.name,
    ): PeerMessage {
        val next = PeerGroup.apply(group, fromId, act, newName)
        return msg(PeerGroup.encode(next, PeerGroup.encodeAct(act)), fromId, sentAtMs)
    }

    @Test
    fun `дія їде тілом і читається назад`() {
        listOf(
            PeerGroup.Act.Add(olia),
            PeerGroup.Act.Remove(taras),
            PeerGroup.Act.Leave,
            PeerGroup.Act.Rename,
        ).forEach { a ->
            assertEquals(a, PeerGroup.decodeAct(PeerGroup.encodeAct(a)))
        }
    }

    /**
     * Голова має рівно три поля, і [PeerGroup.decode] відкидає будь-яку іншу.
     * Якби дія їхала четвертим полем, лист став би невидимим для кожної вже
     * зібраної версії — а оновлення до людей поки не доходять.
     */
    @Test
    fun `лист із дією лишається звичайним листом групи для старої збірки`() {
        val body = PeerGroup.encode(group(), PeerGroup.encodeAct(PeerGroup.Act.Remove(taras)))
        val decoded = PeerGroup.decode(body)
        assertNotNull(decoded)
        assertEquals(id, decoded?.group?.id)
        assertEquals("PH-SYS:rm:$taras", decoded?.text)
    }

    @Test
    fun `звичайна репліка дією не є`() {
        assertNull(PeerGroup.decodeAct("виходимо о шостій"))
        assertNull(PeerGroup.decodeAct("PH-SYS:"))
        assertNull(PeerGroup.decodeAct("PH-SYS:add"))
        assertNull(PeerGroup.decodeAct("PH-SYS:add:"))
        assertNull(PeerGroup.decodeAct("PH-SYS:танцюй"))
        assertFalse(PeerGroup.isAct(PeerGroup.encode(group(), "просто текст")))
        assertTrue(PeerGroup.isAct(PeerGroup.encode(group(), PeerGroup.encodeAct(PeerGroup.Act.Leave))))
    }

    /** Місце не має читатись як дія, а дія — як місце. */
    @Test
    fun `дія і місце не плутаються`() {
        assertNull(PeerPayload.decodePlace(PeerGroup.encodeAct(PeerGroup.Act.Leave)))
        assertNull(PeerGroup.decodeAct(PeerPayload.encodePlace(50.45, 30.52)))
    }

    @Test
    fun `додавання приводить людину в групу на кожному телефоні`() {
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            act(group(), PeerGroup.Act.Add(marko), fromId = olia, sentAtMs = 2_000L),
        )
        assertEquals(
            listOf(me, olia, taras, marko),
            PeerGroup.known(outbox).single().memberIds,
        )
    }

    /**
     * Той, кого прибрали, і далі возить себе у власному заголовку. Доки склад
     * складали з найновішого листа, будь-яка його репліка тихо вертала його
     * назад — на КОЖНОМУ телефоні, і жоден екран цього не показував.
     */
    @Test
    fun `прибраний не повертає себе власною реплікою`() {
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            act(group(), PeerGroup.Act.Remove(taras), fromId = olia, sentAtMs = 2_000L),
            // Тарасів телефон ще не почув — його заголовок несе старий склад.
            msg(PeerGroup.encode(group(), "я тут"), taras, 3_000L),
        )
        assertEquals(listOf(me, olia), PeerGroup.known(outbox).single().memberIds)
    }

    /** Те саме, але про назву: перейменування не відкочується чужою реплікою. */
    @Test
    fun `назва не відкочується реплікою того, до кого зміна ще не дійшла`() {
        val renamed = act(group(), PeerGroup.Act.Rename, fromId = olia, sentAtMs = 2_000L, newName = "Виїзд · субота")
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            renamed,
            msg(PeerGroup.encode(group("Виїзд"), "ок"), taras, 3_000L),
        )
        assertEquals("Виїзд · субота", PeerGroup.known(outbox).single().name)
    }

    @Test
    fun `дія від чужого до групи не належить`() {
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            act(group(), PeerGroup.Act.Remove(olia), fromId = marko, sentAtMs = 2_000L),
        )
        assertEquals(listOf(me, olia, taras), PeerGroup.known(outbox).single().memberIds)
    }

    /** Хто прибрав себе сам — вийшов; група лишається групою решти. */
    @Test
    fun `вихід прибирає з групи того, хто вийшов`() {
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            act(group(), PeerGroup.Act.Leave, fromId = taras, sentAtMs = 2_000L),
        )
        assertEquals(listOf(me, olia), PeerGroup.known(outbox).single().memberIds)
    }

    /**
     * Головне про вихід: він не залежить від мережі. Копії лежать QUEUED, бо
     * нікого не чути, — і все одно на цьому телефоні групи вже нема.
     */
    @Test
    fun `вихід чинний, навіть коли жодна копія не пішла`() {
        val leaving = act(group(), PeerGroup.Act.Leave, fromId = me, sentAtMs = 2_000L, )
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            leaving.copy(toId = olia, state = PeerMessage.State.QUEUED),
            leaving.copy(id = leaving.id + "b", toId = taras, state = PeerMessage.State.QUEUED),
        )
        val known = PeerGroup.known(outbox).single()
        assertFalse(me in known.memberIds)
        assertEquals(PeerGroup.Standing.LEFT, PeerGroup.standing(outbox, known, me))
        assertNotNull(PeerGroup.myLeave(outbox, id, me))
    }

    /** Вийшов сам чи прибрали — різні речення, тож мусить бути видно різницю. */
    @Test
    fun `прибраний бачить, що це зробив не він`() {
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            act(group(), PeerGroup.Act.Remove(me), fromId = olia, sentAtMs = 2_000L),
        )
        val known = PeerGroup.known(outbox).single()
        assertEquals(PeerGroup.Standing.REMOVED, PeerGroup.standing(outbox, known, me))
        assertNull(PeerGroup.myLeave(outbox, id, me))
    }

    /** Поки хто ми — невідомо, екран не сміє казати, що нас звідкись прибрали. */
    @Test
    fun `без власного id жодних висновків про членство`() {
        val outbox = listOf(msg(PeerGroup.encode(group(), "перше"), olia, 1_000L))
        assertEquals(
            PeerGroup.Standing.IN,
            PeerGroup.standing(outbox, PeerGroup.known(outbox).single(), meId = ""),
        )
    }

    /** Група, з якої вийшли, більше не тягне назад значком непрочитаного. */
    @Test
    fun `після виходу нові листи групи не рахуються непрочитаними`() {
        val outbox = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            act(group(), PeerGroup.Act.Leave, fromId = me, sentAtMs = 2_000L),
            msg(PeerGroup.encode(group(), "а ти де?"), olia, 3_000L),
        )
        assertEquals(0, PeerGroup.unread(outbox, id, me, readUpToMs = 0L))
    }

    /** Той, хто щойно доданий, складає групу з того самого листа, що його додав. */
    @Test
    fun `доданий дізнається про групу з листа, який його додав`() {
        val added = act(group(), PeerGroup.Act.Add(marko), fromId = olia, sentAtMs = 2_000L, )
        val onMarkosPhone = listOf(added.copy(toId = marko))
        val known = PeerGroup.known(onMarkosPhone).single()
        assertEquals(listOf(me, olia, taras, marko), known.memberIds)
        assertEquals(PeerGroup.Standing.IN, PeerGroup.standing(onMarkosPhone, known, marko))
    }

    @Test
    fun `більше за стелю складу не додається`() {
        val full = group(members = (1..PeerGroup.MAX_MEMBERS).map { "id%016d".format(it) })
        assertEquals(
            full.memberIds,
            PeerGroup.apply(full, full.memberIds.first(), PeerGroup.Act.Add(marko), full.name).memberIds,
        )
    }

    @Test
    fun `порожня назва не стирає стару`() {
        assertEquals(
            "Виїзд",
            PeerGroup.apply(group(), olia, PeerGroup.Act.Rename, "   ").name,
        )
    }

    // ── вступ: ключі їдуть усередині акту ────────────────────────────────

    private fun card(
        id: String,
        name: String = "",
        host: String = "",
        port: Int = 0,
    ): PeerInvite.Invite {
        val dh = dhKeypair()
        return PeerInvite.Invite(
            peerId = id,
            name = name,
            pubKey = b64(ByteArray(32) { id[it % id.length].code.toByte() }),
            dhPubKey = b64(dh.publicKey),
            host = host,
            port = port,
        )
    }

    /**
     * СТІНА, ЗАРАДИ ЯКОЇ ЦЕ ВСЕ.
     *
     * Лист у гілку — це N попарних листів, тож доданий, у чиєму реєстрі немає
     * жодного члена, бачить склад із трьох людей і не може написати нікому:
     * кожна копія помирає в черзі ще до сокета ([PeerMesh.flush] валить лист,
     * якому нема чим запечататись). Ключ новачка їде разом із «додай його» —
     * інакше «додати» лишається обіцянкою.
     */
    @Test
    fun `акт з конвертом доносить ключі того, кого додають`() {
        val envelope = PeerInvite.envelopeOf(card(marko, name = "Марко"))
        val act = PeerGroup.Act.Add(marko, envelope)
        val read = PeerGroup.decodeAct(PeerGroup.encodeAct(act)) as? PeerGroup.Act.Add
        assertNotNull(read)
        assertEquals(marko, read?.id)
        assertEquals(envelope, read?.invite)
        assertNotNull("ключі не дістались з акту", read?.let { PeerGroup.invited(it) })
    }

    /**
     * Дзеркало двох зрізів. Довідник ріже значення (він його підписує), гілка —
     * рядок; розійтись їм ніде, і саме це тут і звіряється. Мутація, якою це
     * перевірено: прибрати `name = ""` з [PeerInvite.envelopeOf].
     */
    @Test
    fun `гілка й довідник ріжуть конверт однаково`() {
        val full = card(marko, name = "Марко", host = "10.0.0.5", port = 45_111)
        assertEquals(
            PeerInvite.encode(PeerDirectory.addressless(full)),
            PeerInvite.envelopeOf(full),
        )
    }

    /**
     * Конверт їде ЧЕТВЕРТИМ полем саме тому, що [PeerGroup.decodeAct] його доти
     * відкидав: збірка без цієї хвилі прочитає перші три так само, як читала, і
     * склад у неї зміниться так само. Дня-Х немає.
     */
    @Test
    fun `стара форма акту читається й далі`() {
        val read = PeerGroup.decodeAct("PH-SYS:add:$marko")
        assertEquals(PeerGroup.Act.Add(marko), read)
        assertNull("конверт узявся нізвідки", (read as? PeerGroup.Act.Add)?.let { PeerGroup.invited(it) })
        // І навпаки: акт із конвертом лишається звичайним листом групи для тих,
        // хто про конверт не знає, — склад вони перечитають із заголовка.
        val body = PeerGroup.encode(
            group(),
            PeerGroup.encodeAct(PeerGroup.Act.Add(marko, PeerInvite.envelopeOf(card(marko)))),
        )
        assertEquals(id, PeerGroup.decode(body)?.group?.id)
        assertTrue(PeerGroup.isAct(body))
    }

    /**
     * Адреса в конверті — не дрібниця: телефон, який її послухається, першим же
     * листом піде за нею. У гілці її возити нема за чим — той, хто поруч,
     * знайдеться маяком, а хто ні — сховком.
     */
    @Test
    fun `конверт з адресою ключів не дає`() {
        val withAddress = PeerInvite.encode(card(marko, host = "192.168.0.14", port = 45_111))
        assertNull(PeerGroup.invited(PeerGroup.Act.Add(marko, withAddress)))
        // І зріз на боці того, хто складає, справді ріже: той самий конверт,
        // пропущений через [PeerInvite.envelopeOf], уже приймається.
        assertNotNull(
            PeerGroup.invited(
                PeerGroup.Act.Add(marko, PeerInvite.envelopeOf(card(marko, host = "192.168.0.14", port = 45_111))),
            ),
        )
    }

    /** Акт «додай X» із конвертом Y поклав би ключ під чужим ідентифікатором. */
    @Test
    fun `конверт про іншого ключів не дає`() {
        assertNull(PeerGroup.invited(PeerGroup.Act.Add(marko, PeerInvite.envelopeOf(card(taras)))))
    }

    /**
     * Імʼя в конверті склав ТОЙ, ХТО ДОДАВАВ, а на екрані воно читалось би як
     * назване самою людиною. Зрізається на прийманні, а не лише при складанні:
     * складає чужий телефон.
     */
    @Test
    fun `конверт з іменем імені в реєстр не кладе`() {
        val named = PeerInvite.encode(card(marko, name = "Марко Петренко"))
        assertEquals("Марко Петренко", inviteIn(named)?.name)
        assertEquals("", PeerGroup.invited(PeerGroup.Act.Add(marko, named))?.name)
        assertEquals("", PeerGroup.invited(PeerGroup.Act.Add(marko, PeerInvite.envelopeOf(card(marko, "Марко"))))?.name)
    }

    /** Стеля складу не обходиться конвертом: 33-й не член і ключів не лишає. */
    @Test
    fun `тридцять третій не додається навіть із конвертом`() {
        val full = group(members = (1..PeerGroup.MAX_MEMBERS).map { "id%016d".format(it) })
        val act = PeerGroup.Act.Add(marko, PeerInvite.envelopeOf(card(marko)))
        val after = PeerGroup.apply(full, full.memberIds.first(), act, full.name)
        assertEquals(full.memberIds, after.memberIds)
        assertFalse("33-й потрапив у склад", marko in after.memberIds)
        // Заголовок із 33 іменами класичною гілкою не стає — склад не можна
        // роздути й в обхід акту. Розбір пускає до стелі простору, тож стелю
        // гілки тримає згортка. Складається руками, бо [PeerGroup.encode]
        // ріже сам: він обмежує НАС, а голову подає чужий телефон.
        val name = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(full.name.toByteArray(Charsets.UTF_8))
        val ids = (full.memberIds + marko).joinToString(",")
        val wide = PeerMessage(id = "w", fromId = full.memberIds.first(), toId = "me", body = "PH-GRP:$id:$name:$ids\nтук", sentAtMs = 1)
        assertNull("33 у класичній гілці", PeerGroup.stateOf(listOf(wide), id))
        val fits = wide.copy(body = "PH-GRP:$id:$name:${full.memberIds.joinToString(",")}\nтук")
        assertNotNull("тридцять два — ще склад", PeerGroup.stateOf(listOf(fits), id))
        val past = (1..PeerGroup.MAX_SPACE_MEMBERS + 1).joinToString(",") { "id%016d".format(it) }
        assertNull(PeerGroup.decode("PH-GRP:$id:$name:$past\nтук"))
    }

    /**
     * Довжина міряється на ЗАКОДОВАНОМУ полі, до розбору: база64 роздуває
     * вчетверо, тож «512 після розбору» означало б платити розбором за рядок,
     * який надіслав чужий телефон.
     */
    @Test
    fun `завеликий конверт не розбирається`() {
        val huge = "PH2:" + "A".repeat(PeerInvite.ENVELOPE_MAX * 2) + ":zz"
        val read = PeerGroup.decodeAct(PeerGroup.encodeAct(PeerGroup.Act.Add(marko, huge))) as? PeerGroup.Act.Add
        assertEquals("акт загинув разом із конвертом", marko, read?.id)
        assertEquals("", read?.invite)
    }

    /**
     * Склад ключів для новачка — не дія: те, хто в гілці, і далі читається із
     * заголовка. Інакше рано чи пізно склад поїхав би з тіла.
     */
    @Test
    fun `склад ключів читається назад і складу не міняє`() {
        val envelope = PeerInvite.envelopeOf(card(olia, name = "Оля"))
        val line = PeerGroup.encodeRoster(olia, envelope)
        val read = PeerGroup.decodeRoster(line)
        assertEquals(PeerGroup.Roster(olia, envelope), read)
        assertNull("склад ключів прочитався як зміна складу", PeerGroup.decodeAct(line))
        assertEquals("", read?.let { PeerGroup.invited(it) }?.name)

        // І він не лічиться непрочитаним: вступ у гілку на 32 людей — це 31
        // такий рядок, і плашка «31» над порожньою стрічкою була б брехнею.
        val log = listOf(
            msg(PeerGroup.encode(group(), "перше"), olia, 1_000L),
            msg(PeerGroup.encode(group(), line), olia, 2_000L),
        )
        assertEquals(0, PeerGroup.unread(log, id, me, readUpToMs = 1_500L))
        assertEquals(1_000L, PeerGroup.lastAt(log, id))
    }
}
