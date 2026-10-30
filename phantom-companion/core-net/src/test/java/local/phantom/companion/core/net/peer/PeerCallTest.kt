package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кадр дзвінка — ОДИН, і це заміряно тут, а не оголошено в описі.
 *
 * Чотирьох сусідів (`PH-CAL:`, `PH-CAA:`, `PH-CAX:`, `PH-CAE:`) більше немає:
 * їх заступив [PeerCall.SIGNAL] один в один, писаря вони не дістали за весь
 * свій вік, і жоден із двох кінців — ані котлін, ані зібраний веб — їх не знає.
 */
class PeerCallTest {

    /** Номер, який мінить рушій у вебі, а не наш шістнадцятковий. */
    private val callId = "call_9f31ac2b-17"

    /**
     * Мертві роди не воскресають мовчки.
     *
     * Найдешевший спосіб зіпсувати це рішення — дописати пʼятий рід «бо так
     * було раніше». Тоді на дроті знову опиниться слово, якого рушій не знає,
     * а дзвінок помре тихо: кадр доїде й нікуди не ляже. Тому реєстр
     * перевіряється числом, а мертві початки — тим, що вони НЕ дзвінок.
     */
    @Test
    fun `дзвінок на дроті має рівно один рід`() {
        assertEquals(
            "у реєстрі знову більше одного роду дзвінка — рушій знає лише SIGNAL",
            listOf(PeerFrames.Kind.CALL_SIGNAL),
            PeerFrames.Kind.entries.filter { it.mark.startsWith("PH-CA") },
        )
        listOf("PH-CAL:", "PH-CAA:", "PH-CAX:", "PH-CAE:").forEach { dead ->
            assertFalse("мертвий рід $dead знову вважається дзвінком", PeerCall.isCall(dead + callId))
            // Не дзвінок і не рід реєстру — але й не слово людини: від збірки 18
            // незнайомий тег ховає заслін на майбутнє (PeerFramesFutureTest),
            // тож кадр дуже старої збірки теж не ляже бульбашкою.
            assertEquals(
                "мертвий рід $dead повернувся в реєстр",
                PeerFrames.Kind.FUTURE,
                PeerFrames.kindOf(dead + callId),
            )
        }
    }

    @Test
    fun `слово людини не є кадром дзвінка`() {
        listOf("подзвони мені", "PH-CAS", "PH-", "дзвінок PH-CAS: колись", "")
            .forEach { assertFalse("слово визнано дзвінком: $it", PeerCall.isCall(it)) }
    }

    /**
     * Справжня пропозиція SDP: переводи рядків і крапки з комою — рівно ті два
     * знаки, на яких тримається цей кодувальник. Сирий SDP розвалив би кадр на
     * шматки, і саме тому тіло їде base64.
     */
    @Test
    fun `SDP переживає дріт цілим`() {
        val sdp = "v=0\r\n" +
            "o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n" +
            "s=-\r\nt=0 0\r\n" +
            "a=group:BUNDLE 0\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111;103\r\n" +
            "a=fingerprint:sha-256 AB:CD:EF\r\n"
        PeerCall.Signalling.entries.forEach { what ->
            val frame = PeerCall.encodeSignal(PeerCall.Signal(callId, what, sdp))!!
            assertFalse("сирий SDP проліз у кадр", frame.contains("\r\n"))
            val back = PeerCall.decodeSignal(frame)
            assertEquals(PeerCall.Signal(callId, what, sdp), back)
        }
    }

    /**
     * Словник родів — ЗАКРИТИЙ і його слова не наші.
     *
     * Це рівно ті пʼять слів, які зібраний веб ставить у
     * `POST /messenger/call/<рід>` і читає назад у `window.__phantomCallSignal`.
     * Своя назва поруч із його назвою була б другою правдою про той самий кадр.
     */
    @Test
    fun `рід читається словом рушія і тільки ним`() {
        assertEquals(
            listOf("offer", "answer", "ice", "hangup", "radio"),
            PeerCall.Signalling.entries.map { it.webKind },
        )
        PeerCall.Signalling.entries.forEach {
            assertEquals(it, PeerCall.Signalling.ofWeb(it.webKind))
        }
        listOf("invite", "cancel", "end", "OFFER", "").forEach {
            assertNull("рід «$it» їде, хоч рушій його не шле", PeerCall.Signalling.ofWeb(it))
        }
    }

    /**
     * Стеля стоїть ДО сокета. Кадр, зрізаний на дроті, не відмовляється — він
     * мовчки не доїжджає, і дзвінок «просто не дзвонить».
     */
    @Test
    fun `завелике тіло сигналу відмовляється складачем`() {
        val huge = "ю".repeat(PeerCall.MAX_SIGNAL_CHARS)
        assertNull(
            PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, huge)),
        )
        assertNull(
            PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, "")),
        )
        assertNull(
            "кирилиця в номері дзвінка проїхала",
            PeerCall.encodeSignal(PeerCall.Signal("дзвінок", PeerCall.Signalling.OFFER, "x")),
        )
        assertNull(
            "крапка з комою в номері дзвінка розвалила б кадр",
            PeerCall.encodeSignal(PeerCall.Signal("a;b", PeerCall.Signalling.OFFER, "x")),
        )
        // Номер, який мінить рушій у вебі, — не наш шістнадцятковий, і кадр
        // мусить його прийняти, інакше дзвінки веба не поїдуть узагалі.
        assertNotNull(
            PeerCall.encodeSignal(PeerCall.Signal(callId, PeerCall.Signalling.OFFER, "x")),
        )
    }

    @Test
    fun `покалічений сигнал не розбирається`() {
        val good = PeerCall.encodeSignal(
            PeerCall.Signal(callId, PeerCall.Signalling.OFFER, "v=0"),
        )!!
        listOf(
            PeerCall.SIGNAL + callId,
            PeerCall.SIGNAL + callId + ";o",
            PeerCall.SIGNAL + callId + ";o;",
            PeerCall.SIGNAL + callId + ";щось;dj0w",
            PeerCall.SIGNAL + "дзвінок;o;dj0w",
            // Не base64: тіло, яке не розбирається, — не «порожній сигнал».
            PeerCall.SIGNAL + callId + ";o;не base64!",
        ).forEach { assertNull("покалічений сигнал розібрався: $it", PeerCall.decodeSignal(it)) }
        assertNotNull(PeerCall.decodeSignal(good))
    }

    /**
     * Хвилина — межа ОДНА на обидва кінці.
     *
     * Тут вона лише оголошена числом, а працює в [PeerCallLine.expire]: там її
     * і перевіряють з обох боків лінії. Розійтись їм не дасть саме те, що число
     * одне.
     */
    @Test
    fun `дзвінок живе хвилину`() {
        assertEquals(60_000L, PeerCall.RING_LIFE_MS)
        assertTrue(PeerCall.MAX_SIGNAL_CHARS in 1..PeerWire.MAX_BODY_BYTES)
    }
}
