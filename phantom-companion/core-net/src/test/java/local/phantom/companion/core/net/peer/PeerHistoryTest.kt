package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПЕРЕКАЗ, ЯКИЙ НЕ ВДАЄ ОРИГІНАЛ.
 *
 * Гілка, у яку можна зайти будь-коли, коштує рівно одного: хтось із тих, хто
 * вже був усередині, переказує новачкові те, що казали до нього. Підпис автора
 * накриває адресата ([PeerSealing]), тож переказана копія його нести не може —
 * і саме тому головне тут не «чи доїхало», а «чи не збрехало по дорозі».
 */
class PeerHistoryTest {

    private val me = "aaaa000000000001"
    private val olia = "bbbb000000000002"
    private val taras = "cccc000000000003"
    private val newcomer = "dddd000000000004"

    private val groupId = "abc123abc123"
    private val group = PeerGroup.Group(groupId, "Виїзд", listOf(me, olia, taras))

    private fun said(
        text: String,
        from: String,
        at: Long,
        to: String = me,
        state: PeerMessage.State = PeerMessage.State.DELIVERED,
        id: String = "$from>$to@$at",
    ) = PeerMessage(
        id = id,
        fromId = from,
        toId = to,
        body = PeerGroup.encode(group, text),
        sentAtMs = at,
        state = state,
    )

    private fun packLetter(body: String, from: String, at: Long = 9_000L) = PeerMessage(
        id = "pack-$from-$at",
        fromId = from,
        toId = newcomer,
        body = body,
        sentAtMs = at,
        state = PeerMessage.State.DELIVERED,
    )

    @Test
    fun `пакет читається назад тим самим, що в нього поклали`() {
        val lines = listOf(
            PeerHistory.Line("l1", olia, 1_000L, "виходимо о шостій"),
            PeerHistory.Line("l2", taras, 2_000L, "я на місці"),
        )
        val pack = PeerHistory.packs(groupId, lines).single()
        val read = PeerHistory.decode(pack)
        assertNotNull(read)
        assertEquals(groupId, read?.groupId)
        assertEquals(lines, read?.lines)
    }

    /** Двокрапка й вертикальна риска в словах не мають ставати полями. */
    @Test
    fun `роздільники в словах не ламають пакет`() {
        val line = PeerHistory.Line("l1", olia, 1_000L, "о 18:00 | коло мосту: чекаю")
        val read = PeerHistory.decode(PeerHistory.packs(groupId, listOf(line)).single())
        assertEquals(listOf(line), read?.lines)
    }

    /**
     * Стеля пакета виводиться зі стелі журналу. Переказ на 600 рядків приїхав
     * би й тут-таки поліг під [PeerOutbox.prune] — мовчазна поразка з боку
     * сховища, якої на екрані не видно взагалі.
     */
    @Test
    fun `більше за стелю журналу не переказується`() {
        val many = (1..PeerHistory.MAX_LINES + 120).map {
            PeerHistory.Line("l$it", olia, 1_000L + it, "рядок $it")
        }
        val read = PeerHistory.packs(groupId, many).mapNotNull(PeerHistory::decode).flatMap { it.lines }
        assertEquals(PeerHistory.MAX_LINES, read.size)
        // Обтинається СТАРЕ, не нове: минуле, у якому бракує вчорашнього, —
        // не минуле.
        assertEquals("рядок ${many.size}", read.last().text)
    }

    /** Кожен пакет мусить влазити в корзину сховка, інакше минуле доїде лише по LAN. */
    @Test
    fun `жоден пакет не переростає корзину сховка`() {
        val many = (1..PeerHistory.MAX_LINES).map {
            PeerHistory.Line("l$it", olia, 1_000L + it, "х".repeat(200))
        }
        val packs = PeerHistory.packs(groupId, many)
        assertTrue("один пакет на все — у сховок він не влізе", packs.size > 1)
        packs.forEach {
            assertTrue(
                "пакет ${it.length} Б більший за корзину ${PeerHistory.PACK_MAX_BYTES}",
                it.toByteArray(Charsets.UTF_8).size <= PeerHistory.PACK_MAX_BYTES,
            )
        }
        // І при цьому нічого не загубилось між пакетами.
        assertEquals(many, packs.mapNotNull(PeerHistory::decode).flatMap { it.lines })
    }

    @Test
    fun `зіпсоване поле не забирає з собою пакет`() {
        val pack = PeerHistory.packs(
            groupId,
            listOf(
                PeerHistory.Line("l1", olia, 1_000L, "перше"),
                PeerHistory.Line("l2", olia, 2_000L, "друге"),
            ),
        ).single()
        val broken = pack.substringBeforeLast(':') + ":не-база64-зовсім-ні"
        assertEquals(listOf("перше"), PeerHistory.decode(broken)?.lines?.map { it.text })
        assertNull("не пакет визнано пакетом", PeerHistory.decode("PH-HIST:"))
        assertNull(PeerHistory.decode("виходимо о шостій"))
    }

    /**
     * ГОЛОВНЕ: ПЕРЕКАЗАНЕ НІКОЛИ НЕ ПОКАЗУЄТЬСЯ ЯК ДОСТАВЛЕНЕ.
     *
     * Стан доставки — це [PeerMessage.State], і `DELIVERED` у ньому означає «той
     * телефон сказав, що має це». Про переказаний рядок жоден телефон нікому
     * такого не казав. Тому стан не їде дротом узагалі, а прийняте — це [Told],
     * тип БЕЗ стану: з нього не можна скласти рядок черги, навіть помилково.
     */
    @Test
    fun `переказаний рядок не везе стану доставки`() {
        val log = listOf(said("виходимо", olia, 1_000L, state = PeerMessage.State.DELIVERED))
        val pack = PeerHistory.packs(groupId, PeerHistory.linesOf(log, groupId)).single()
        val told = PeerHistory.open(packLetter(pack, from = olia)).single()
        assertEquals("виходимо", told.line.text)
        assertEquals("автор рядка загубився в переказі", olia, told.line.fromId)

        // Перевіряється не ОДНЕ значення стану, а те, що значення нема звідки
        // взяти: ані поля в типах переказаного, ані дороги від пакета до рядка
        // черги. Інакше сторож зеленів би рівно доти, доки хтось не поставив би
        // `DELIVERED` руками — тобто не вмів би почервоніти взагалі.
        listOf(PeerHistory.Told::class.java, PeerHistory.Line::class.java).forEach { type ->
            assertTrue(
                "у ${type.simpleName} зʼявилось поле стану — екран скаже «доставлено» про те, " +
                    "чого ця пара телефонів одне одному не посилала",
                type.declaredFields.none { PeerMessage.State::class.java.isAssignableFrom(it.type) },
            )
        }
        assertTrue(
            "зʼявилась дорога від переказу до рядка черги — покладене в чергу отримає стан",
            PeerHistory::class.java.methods.none {
                PeerMessage::class.java.isAssignableFrom(it.returnType)
            },
        )
    }

    /**
     * Через кого переказано — з КОНВЕРТА, не з тіла. Поле в тілі склав би той
     * самий телефон, що й пакет, і приписав би переказ комусь іншому.
     */
    @Test
    fun `переказувача називає підписаний конверт, а не сам пакет`() {
        val lines = listOf(PeerHistory.Line("l1", olia, 1_000L, "виходимо"))
        val pack = PeerHistory.packs(groupId, lines).single()
        assertEquals(taras, PeerHistory.open(packLetter(pack, from = taras)).single().viaId)
        assertEquals(olia, PeerHistory.open(packLetter(pack, from = olia)).single().viaId)
        assertTrue(PeerHistory.open(packLetter(pack, from = "")).isEmpty())
    }

    /**
     * ДЕДУП, І САМЕ ДВОКЛЮЧОВИЙ.
     *
     * Ідентифікатор групового рядка не спільний: кожна копія віяра має свій.
     * Тож Оля переказує той самий сказаний рядок під іншим id, ніж Тарас, і
     * дедуп лише за id показав би новачкові одне речення двічі.
     */
    @Test
    fun `один і той самий рядок від двох переказувачів показується один раз`() {
        val fromOlia = PeerHistory.packs(
            groupId,
            listOf(PeerHistory.Line("копія-до-олі", me, 1_000L, "виходимо о шостій")),
        ).single()
        val fromTaras = PeerHistory.packs(
            groupId,
            listOf(PeerHistory.Line("копія-до-тараса", me, 1_000L, "виходимо о шостій")),
        ).single()
        val log = listOf(
            packLetter(fromOlia, from = olia, at = 9_000L),
            packLetter(fromTaras, from = taras, at = 9_100L),
        )
        val retold = PeerHistory.retold(log, groupId)
        assertEquals(1, retold.size)
        assertEquals(olia, retold.single().viaId)
    }

    /** Те, що телефон має власним листом, переказом не дублюється. */
    @Test
    fun `власний лист старший за переказ про нього`() {
        val mine = said("виходимо", olia, 1_000L, id = "мій-рядок")
        val pack = PeerHistory.packs(
            groupId,
            listOf(PeerHistory.Line("чужа-копія", olia, 1_000L, "виходимо")),
        ).single()
        val log = listOf(mine, packLetter(pack, from = taras))
        assertTrue("переказ продублював те, що вже є", PeerHistory.retold(log, groupId).isEmpty())
    }

    /**
     * Службові рядки не переказуються: склад новачок однаково перечитає із
     * заголовка кожного листа, а стрічка отримала б події без часу й наслідку.
     */
    @Test
    fun `рядки каналів простору в переказ не потрапляють`() {
        val log = listOf(
            said("виходимо", olia, 1_000L),
            said(PeerGroup.inChannel("staff", "зарплати")!!, olia, 2_000L),
        )
        assertEquals(listOf("виходимо"), PeerHistory.linesOf(log, groupId).map { it.text })
    }

    @Test
    fun `дії над складом у переказ не потрапляють`() {
        val log = listOf(
            said("виходимо", olia, 1_000L),
            said(PeerGroup.encodeAct(PeerGroup.Act.Add(newcomer)), olia, 2_000L),
            said(PeerHistory.encodeAsk(0L), newcomer, 3_000L),
        )
        assertEquals(listOf("виходимо"), PeerHistory.linesOf(log, groupId).map { it.text })
    }

    @Test
    fun `прохання про минуле читається назад і дією не є`() {
        val ask = PeerHistory.encodeAsk(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, PeerHistory.decodeAsk(ask))
        assertNull("прохання прочиталось як зміна складу", PeerGroup.decodeAct(ask))
        assertTrue(PeerHistory.isAsk(PeerGroup.encode(group, ask)))
        assertFalse(PeerHistory.isAsk(PeerGroup.encode(group, "виходимо")))
        assertNull(PeerHistory.decodeAsk("PH-SYS:hist"))
        assertNull(PeerHistory.decodeAsk("PH-SYS:hist:завтра"))
        assertNull(PeerHistory.decodeAsk("PH-SYS:hist:-5"))
    }

    /** Просять від миті — і раніше за неї не переказують. */
    @Test
    fun `переказ починається з названої миті`() {
        val log = listOf(
            said("давнє", olia, 1_000L),
            said("свіже", olia, 5_000L),
        )
        assertEquals(listOf("свіже"), PeerHistory.linesOf(log, groupId, sinceMs = 3_000L).map { it.text })
    }

    /**
     * Ані пакет, ані прохання не повинні ані дзвонити, ані рахуватись
     * непрочитаними: вступ у гілку інакше будив би телефон пакетом за пакетом.
     */
    @Test
    fun `переказ не дзвонить і не рахується непрочитаним`() {
        val pack = packLetter(
            PeerHistory.packs(groupId, listOf(PeerHistory.Line("l1", olia, 1_000L, "виходимо"))).single(),
            from = olia,
        )
        assertNull("пакет переказу став плашкою", PeerAnnounce.notice(pack, null, null))
        assertFalse("пакет переказу дійшов до розмови", PeerOutbox.isSpoken(pack))
        assertNotNull("набраний пакет не отримав відмови", PeerFrames.refuseTyped(pack.body))

        val asking = said(PeerHistory.encodeAsk(0L), newcomer, 3_000L)
        assertNull("прохання про минуле стало плашкою", PeerAnnounce.notice(asking, null, null))
        assertEquals(0, PeerGroup.unread(listOf(asking), groupId, me, readUpToMs = 0L))
    }
}
