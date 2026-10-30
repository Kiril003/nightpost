package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val NOW = 1_788_517_702_898L

/**
 * Набране людиною не може вдавати кадр пристрою.
 *
 * Сторожі дивляться на РЕЗУЛЬТАТ — що каже реєстр про справжній кадр, зібраний
 * справжнім кодувальником, і чому відмовляє [PeerFrames.refuseTyped], — а не на
 * текст джерела. У цьому дереві вже є зелений сторож, який шукав підрядок у
 * файлі екрана, якого власник не відкриває, і саме тому не побачив сирого дроту
 * на склі.
 */
class PeerFramesTest {

    private val route = PeerLive.Route(
        expiresAtMs = NOW + PeerLive.ROUTE_LIFE_MS,
        distanceM = 337.0,
        durationS = 258L,
        profile = "foot",
        waypoints = listOf(50.45010 to 30.52340, 50.45120 to 30.52330),
        shape = listOf(50.45010 to 30.52340, 50.45050 to 30.52100, 50.45120 to 30.52330),
    )

    private val group = PeerGroup.Group(
        id = "a1b2c3d4e5f6",
        name = "ланка",
        memberIds = listOf("me", "them"),
    )

    /** Номер дзвінка мінить рушій у вебі, а не ми. */
    private val callId = "call_9f31ac2b-17"

    /** Id рядка, на який указує позначка, — такий, яким його мінить PeerMesh. */
    private val targetId = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2"

    /** Справжній кадр кожного роду — від того кодувальника, що його й пише. */
    private fun encoded(kind: PeerFrames.Kind): String = when (kind) {
        PeerFrames.Kind.ROUTE -> PeerLive.encodeRoute(route)!!
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
                "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n",
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
        PeerFrames.Kind.WHERE -> PeerWhere.encode(PeerWhere.Erase(NOW))!!
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
        PeerFrames.Kind.MOMENT -> MomentWire.encode(MomentWire.Op.Seen("m1abcdefgh", NOW))!!
        PeerFrames.Kind.CONTACT -> PeerContact.encode(PeerContact.Op.Withdraw("r1"))
        PeerFrames.Kind.GROUP_JOIN -> PeerGroupJoin.encode(PeerGroupJoin.Frame.Ask("g0001", "Abcdefgh23456789"))
        PeerFrames.Kind.STAFF -> StaffWire.encode(StaffWire.Pass("Зерно", 1L, emptyList()))!!
        // Тег, якого не знає жодна збірка, — «кадр з завтрашньої».
        PeerFrames.Kind.FUTURE -> "PH-QQQ:1788517702898;щось із завтрашньої збірки"
    }

    private fun letter(body: String): PeerMessage =
        PeerMessage(id = "m", fromId = "them", toId = "me", body = body, sentAtMs = NOW)

    @Test
    fun `кожен кадр, складений пристроєм, впізнає реєстр`() {
        val seen = PeerFrames.Kind.entries.associateWith { PeerFrames.kindOf(encoded(it)) }
        assertEquals(
            "кодувальник пише кадр, якого реєстр не впізнає",
            PeerFrames.Kind.entries.associateWith { it },
            seen,
        )
    }

    /**
     * РІВНІСТЬ МНОЖИН, і в тому боці, який ловить підробку.
     *
     * Ліворуч — роди, що доходять до розмови ([PeerOutbox.isSpoken]); праворуч —
     * роди, яким відмовляє вхід набраного ([PeerFrames.refuseTyped], та сама
     * перевірка, що стоїть у `PeerMesh.sendTyped`). Рід, що доходить до розмови
     * без відмови, — це намальована картка, яку склали пальцями.
     *
     * Різниця в другий бік лишається НАВМИСНО: набір відмовляє й тим родам, яких
     * розмова не показує (шматок файла, груповий конверт, тік положення). Якби
     * він їм не відмовляв, набране тіло з таким початком стало б у чергу, поїхало
     * б і не зʼявилось у жодній розмові — мовчазна поразка замість слова. Саме
     * тому ця ширша частина оголошена в реєстрі, а не мовчить: її звіряє сторож
     * нижче.
     */
    @Test
    fun `рід, що доходить до розмови, не набирається пальцями`() {
        val spoken = PeerFrames.Kind.entries
            .filter { PeerOutbox.isSpoken(letter(encoded(it))) }
            .map { it.name }.toSet()
        val refused = PeerFrames.Kind.entries
            .filter { PeerFrames.refuseTyped(encoded(it)) != null }
            .map { it.name }.toSet()
        assertEquals(
            "рід доходить до розмови, а вхід набраного йому не відмовляє — " +
                "набране тіло з таким початком стане карткою",
            emptySet<String>(),
            spoken - refused,
        )
    }

    /**
     * Ратчет на майбутнє, а не на сьогодні.
     *
     * Збіг «доходить у розмову» з «є чим підробити» тримається на
     * [PeerOutbox.isSpoken], і зламатись він може мовчки: досить впустити в
     * розмову ще один рід. Тоді ця рівність червоніє й НАЗИВАЄ рід, який щойно
     * туди потрапив, — щоб той, хто розширює розмову, одразу бачив, що має
     * розширити й відмову.
     *
     * Мутація, якою це перевірено: прибрати `!PeerFile.isChunk(...)` з
     * [PeerOutbox.isSpoken].
     */
    @Test
    fun `перелік родів, які доходять до розмови, не міняється мовчки`() {
        val spoken = PeerFrames.Kind.entries
            .filter { PeerOutbox.isSpoken(letter(encoded(it))) }
            .map { it.name }
        assertEquals(
            "розмова прийняла інший набір службових родів, ніж оголошено в " +
                "PeerFrames.Kind.reachesConversation — переконайся, що вхід " +
                "набраного відмовляє новому роду, і лише тоді онови оголошення",
            PeerFrames.Kind.entries.filter { it.reachesConversation }.map { it.name },
            spoken,
        )
    }

    /**
     * [PeerFrames.MARK] ширший за реєстр і мусить лишатись його НАДмножиною:
     * поверхня, що ставить заслін за спільною позначкою, інакше пропустила б
     * рід, чия позначка починається інакше.
     */
    @Test
    fun `спільна позначка починає кожен рід реєстру`() {
        val stray = PeerFrames.Kind.entries
            // Запит у контакти їде дротом ПК (`\u0001phantom-kind:`), а не PH-.
            .filterNot { it.mark.startsWith(PeerFrames.MARK) || it == PeerFrames.Kind.CONTACT }
            .map { "${it.name}=${it.mark}" }
        assertEquals(
            "позначка роду не починається зі спільної — заслін за нею його не спіймає",
            emptyList<String>(),
            stray,
        )
    }

    @Test
    fun `слово людини не судиться за кадр`() {
        val words = listOf(
            "виходь на звʼязок",
            "маршрут PH-RTE ми колись обговорювали",
            "PH",
            "{\"blob_id\":\"" + "0".repeat(32) + "\",\"key_hex\":\"aa\"," +
                "\"sha256\":\"bb\",\"size\":10}",
        )
        words.forEach {
            assertNull("слово людини визнано кадром: $it", PeerFrames.refuseTyped(it))
        }
    }

    /**
     * Композер, поле назви й відповідь зі шторки обтинають набране самі, тож
     * пробіл попереду не має рятувати рядок від суду.
     */
    @Test
    fun `набране судиться обтятим`() {
        PeerFrames.Kind.entries.forEach { kind ->
            assertNotNull(
                "пробіл попереду проніс кадр роду ${kind.name}",
                PeerFrames.refuseTyped("  \n" + encoded(kind) + " "),
            )
        }
    }

    /**
     * Опис вкладення лишається набірним свідомо: веб кладе файл тим самим
     * входом, яким шле слово. Якщо колись відмовлятимемо й йому — надсилання
     * файлів з веба спиниться, і тоді це має бути рішенням, а не наслідком.
     */
    @Test
    fun `опис вкладення не є службовим кадром`() {
        val body = "{\"blob_id\":\"" + "0".repeat(32) + "\",\"key_hex\":\"aa\"," +
            "\"sha256\":\"bb\",\"size\":10,\"mime\":\"image/jpeg\"}"
        assertNotNull("опис вкладення перестав розбиратись", PeerFile.describe(body))
        assertNull("опис вкладення став службовим кадром", PeerFrames.kindOf(body))
    }
}
