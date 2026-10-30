package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Розмова не має стелі ТРИВАЛОСТІ — і це доведено, а не обіцяно коментарем.
 *
 * ## Чому цього тесту було мало раніше
 *
 * `PeerCallLineTest` і `PeerCallRoomTest` уже кличуть `expire` один раз на
 * далекому часі й бачать `null`. Це доводить, що ОДИН виклик нічого не
 * ламає, — і рівно стільки. Апарат кличе `expire` не раз, а щосекунди
 * ([PeerCallHub.TICK_MS]), і ламає розмови зазвичай не одноразовий вирок, а
 * накопичення: лічильник, що переповнився, час, який хтось посунув, нога, що
 * ожила й потягла за собою кімнату.
 *
 * Тому тут два різні прилади, і жоден не замінює другого:
 *
 * 1. **Прогін.** Тик за тиком через рік розмови на восьми ногах — рівно так,
 *    як це робить апарат, а не одним стрибком.
 * 2. **Сторож джерела.** Прогін доводить, що стелі немає СЬОГОДНІ. Він не
 *    почервоніє від таймера, який хтось додасть завтра в іншому файлі. Тому
 *    нижче — читання самого коду: у всьому шляху дзвінка мусить бути РІВНО
 *    ОДНА межа часу ([PeerCall.RING_LIFE_MS]), і вона мусить стояти в гілці,
 *    яка вимагає DIALING або RINGING.
 *
 * Сторож читає джерело БЕЗ КОМЕНТАРІВ. Це не педантизм: у цьому дереві вже
 * траплялось, що сторож задовольнявся власним коментарем і зеленів на
 * порожньому місці. Слово в прозі — не доказ; доказ — вираз у коді.
 */
class PeerCallDurationTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "core-net").isDirectory }

    /** Той самий крок, що й у циклі апарата: `PeerCallHub.TICK_MS`. */
    private val tickMs = 1_000L

    private fun talkingRoom(legs: Int): PeerCallLine.Room = PeerCallLine.Room(
        callId = "c-long",
        groupId = "g-1",
        legs = (1..legs).map { PeerCallLine.Leg("peer-$it", PeerCallLine.Foot.TALKING, 0L) },
    )

    // ── 1. прогін ────────────────────────────────────────────────────────

    @Test
    fun `рік розмови тиками по секунді не гасить жодної ноги`() {
        val room: PeerCallLine.Room = talkingRoom(PeerCallLine.MAX_LEGS)
        val year = 365L * 24 * 60 * 60 * 1000

        // Тик за тиком було б 31 мільйон викликів — прогін на такому тесті не
        // добіг би. Крок побільшений, але межі, які МОГЛИ Б спрацювати, взяті
        // всі точно: хвилина дзвоніння, година, доба, тиждень, місяць, рік.
        val marks = listOf(
            PeerCall.RING_LIFE_MS - tickMs,
            PeerCall.RING_LIFE_MS,
            PeerCall.RING_LIFE_MS + tickMs,
            60L * 60 * 1000,
            24L * 60 * 60 * 1000,
            7L * 24 * 60 * 60 * 1000,
            30L * 24 * 60 * 60 * 1000,
            year,
        )
        for (mark in marks) {
            // Кілька справжніх тиків навколо кожної межі: вада, що спрацьовує
            // на СУСІДНІЙ секунді, стрибком через рік була б не помічена.
            for (step in -2..2) {
                val now = (mark + step * tickMs).coerceAtLeast(0L)
                val verdict = PeerCallLine.expire(room, now)
                assertNull(
                    "розмова вмерла сама на ${now / 1000} с — це і є стеля тривалості",
                    verdict,
                )
            }
        }

        assertEquals(
            "після року розмови ноги мали лишитись усі",
            PeerCallLine.MAX_LEGS,
            PeerCallLine.liveLegs(room).size,
        )
        assertTrue(
            "після року обличчя лінії мало лишитись «розмова»",
            PeerCallLine.face(room) is PeerCallLine.Line.Talking,
        )
        // Кінець епохи — не межа й не переповнення: якби десь рахувалась
        // різниця в Int, саме тут вона й луснула б.
        assertNull(
            "Long.MAX_VALUE поклав розмову — десь рахується різниця, яка переповнюється",
            PeerCallLine.expire(room, Long.MAX_VALUE),
        )
    }

    @Test
    fun `нога, що не взяла слухавку, не тягне за собою тих, хто говорить`() {
        // Найімовірніша форма «розмова обірвалась сама»: у кімнаті поруч із
        // тими, хто говорить, висить нога, яка так і не відповіла. Її хвилина
        // спливає — і якби вирок стосувався КІМНАТИ, а не ноги, розмова на
        // шістдесятій секунді гасла б у всіх.
        val room = PeerCallLine.Room(
            callId = "c-mix",
            groupId = "g-1",
            legs = listOf(
                PeerCallLine.Leg("talker-1", PeerCallLine.Foot.TALKING, 0L),
                PeerCallLine.Leg("talker-2", PeerCallLine.Foot.TALKING, 0L),
                PeerCallLine.Leg("silent", PeerCallLine.Foot.RINGING, 0L),
            ),
        )
        val verdict = PeerCallLine.expire(room, PeerCall.RING_LIFE_MS)
            ?: error("нога, яка не взяла слухавку, мусила згаснути")

        assertEquals(
            "згаснути мала РІВНО одна нога — та, що не відповіла",
            listOf("silent"),
            verdict.out.map { it.toPeerId },
        )
        assertEquals(
            "ті, хто говорить, лишились у розмові",
            listOf("talker-1", "talker-2"),
            PeerCallLine.liveLegs(verdict.room).map { it.peerId },
        )
        // І далі рік на тій самій кімнаті вже нічого не чіпає.
        assertNull(
            "після смерті мовчазної ноги розмова згасла сама",
            PeerCallLine.expire(verdict.room, 365L * 24 * 60 * 60 * 1000),
        )
    }

    @Test
    fun `нога, що ВЗЯЛА слухавку пізно, не успадковує хвилини дзвоніння`() {
        // Пастка, від якої рятує `sinceMs` при зміні стану: якщо нога стала
        // TALKING, а час лишився від DIALING, то перша ж перевірка після
        // хвилини побачила б прострочену ногу. Тут її бути не має.
        val dialing = PeerCallLine.Room(
            callId = "c-late",
            groupId = null,
            legs = listOf(PeerCallLine.Leg("slow", PeerCallLine.Foot.DIALING, 0L)),
        )
        val answered = PeerCallLine.noteSent(
            dialing, "slow", "c-late", PeerCall.Signalling.ANSWER, PeerCall.RING_LIFE_MS - 1,
        )
        assertEquals(
            PeerCallLine.Foot.TALKING,
            PeerCallLine.legOf(answered.room, "slow")?.foot,
        )
        assertNull(
            "нога, що відповіла за секунду до межі, згасла на межі",
            PeerCallLine.expire(answered.room, PeerCall.RING_LIFE_MS),
        )
        assertNull(
            "та сама нога згасла за рік розмови",
            PeerCallLine.expire(answered.room, 365L * 24 * 60 * 60 * 1000),
        )
    }

    // ── 2. сторож джерела ────────────────────────────────────────────────

    /** Код без коментарів: сторож, якого влаштовує проза, — не сторож. */
    private fun bareCode(path: String): String {
        val text = File(root, path).readText()
        return text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    @Test
    fun `у шляху дзвінка є РІВНО ОДНА межа часу, і вона про дзвоніння`() {
        val line = bareCode("core-net/src/main/java/local/phantom/companion/core/net/peer/PeerCallLine.kt")

        // Єдина межа часу, яку вільно згадувати, — хвилина дзвоніння.
        val uses = Regex("""RING_LIFE_MS""").findAll(line).count()
        assertEquals(
            "RING_LIFE_MS має стояти в коді рівно раз — у предикаті expire",
            1,
            uses,
        )

        // І стояти вона мусить у виразі, який ВИМАГАЄ ноги, що ще дзвонить.
        val predicate = Regex(
            """\(it\.foot == Foot\.DIALING \|\| it\.foot == Foot\.RINGING\) &&\s*nowMs - it\.sinceMs >= PeerCall\.RING_LIFE_MS""",
        )
        assertTrue(
            "предикат expire більше не вимагає DIALING/RINGING — межа доросла до розмови",
            predicate.containsMatchIn(line),
        )

        // А ось і сама вада, якщо її колись додадуть: ПОРІВНЯННЯ прожитого
        // часу з бюджетом. Не присвоєння (`sinceMs = nowMs` — це відлік
        // спочатку, він потрібен і нешкідливий), а саме звірка з межею.
        // Таких звірок у файлі мусить бути рівно одна — та, що вище.
        val clockChecks = Regex("""nowMs\s*-\s*[\w.]*sinceMs\s*[<>=!]+""").findAll(line).toList()
        assertEquals(
            "звірок прожитого часу з межею стало більше за одну: " +
                clockChecks.joinToString { it.value } +
                " — друга з них і є стеля тривалості розмови",
            1,
            clockChecks.size,
        )
    }

    @Test
    fun `інших меж часу в дзвінку немає — ні в кадрі, ні в апараті`() {
        val call = bareCode("core-net/src/main/java/local/phantom/companion/core/net/peer/PeerCall.kt")
        val budgets = Regex("""const val ([A-Z_]*(?:_MS|_SEC|_MINUTES|TIMEOUT)[A-Z_]*)""")
            .findAll(call).map { it.groupValues[1] }.toList()
        assertEquals(
            "у PeerCall зʼявилась друга межа часу — вона мусить бути названа й доведена, як RING_LIFE_MS",
            listOf("RING_LIFE_MS"),
            budgets,
        )

        // Цикл апарата: єдине, що він робить із часом, — кличе expire. Будь-яке
        // інше рішення там і було б тією межею, якої «немає».
        val hub = bareCode("app/src/main/java/local/phantom/companion/data/PeerCallHub.kt")
        val tick = Regex("""while \(isActive\) \{(.*?)\n            \}""", RegexOption.DOT_MATCHES_ALL)
            .find(hub)?.groupValues?.get(1)
            ?: error("цикл тиків у PeerCallHub не знайдено — його переписали, і сторож осліп")
        assertTrue(
            "у циклі тиків зʼявилось рішення, крім expire: $tick",
            Regex("""PeerCallLine\.expire""").containsMatchIn(tick) &&
                !Regex("""leave|drop|decline|clearOver""").containsMatchIn(tick),
        )
    }
}
