package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Картка доїжджає до стрічки веба — і жоден рід не їде голим тілом.
 *
 * Два виміри в одному файлі, бо вада одна: рід, для якого міст не вміє скласти
 * кадр, мовчки їхав як слово людини. Картки не доїжджали взагалі; живе
 * положення доїжджало голим JSON у чужу бульбашку. `org.json` тут немає
 * свідомо — у модулі `app` юніт-тест бачить від нього заглушку, яка кидає на
 * першому виклику, тож усе перевіряється ЗНАЧЕННЯМ.
 */
class WebMessengerCardReachTest {

    private val payload = "{\"id\":\"kanban_1\",\"columns\":[]}"

    /**
     * Кожен із тринадцяти доїжджає своїм словом веба й своїм полем.
     *
     * Рід [WebMessengerKinds.TEXT] тут був би найгіршим із можливих: картка
     * лягла б у бульбашку сирим дротом. А рід без поля — порожньою бульбашкою з
     * годинником, бо стрічка малює віджет лише коли бачить обоє.
     */
    @Test
    fun `кожна картка приїжджає словом веба і своїм полем`() {
        PeerCard.Card.entries.forEach { what ->
            val frame = PeerCard.encode(PeerCard.Body(what, payload))!!
            val fields = WebMessengerKinds.fields(frame)
            assertEquals(
                "картка ${what.name} приїхала не своїм родом",
                what.webType,
                fields[WebMessengerKinds.KIND],
            )
            assertEquals(
                "картка ${what.name} приїхала не в те поле — стрічка її не намалює",
                what.webField,
                fields[WebMessengerKinds.CARD_FIELD],
            )
            assertEquals(
                "тіло картки ${what.name} змінилось у дорозі",
                payload,
                fields[WebMessengerKinds.CARD_JSON],
            )
        }
    }

    /**
     * Сире тіло не їде вебові НІКОЛИ: `card_field` і `card_json` — службові
     * ключі, з яких міст складає одне поле. Якби вони поїхали як є, у стрічці
     * лежав би рядок, якого ніхто не читає, — запрошення наступному заповнити
     * його правдоподібним.
     */
    @Test
    fun `службові ключі картки не їдуть вебові`() {
        val frame = PeerCard.encode(PeerCard.Body(PeerCard.Card.CANVAS, payload))!!
        val shipped = WebMessengerKinds.shipped(WebMessengerKinds.fields(frame))
        assertNull(shipped[WebMessengerKinds.CARD_FIELD])
        assertNull(shipped[WebMessengerKinds.CARD_JSON])
        assertEquals("рід картки не доїхав", "widget:canvas", shipped[WebMessengerKinds.KIND])
    }

    /** Картка видима в стрічці: інакше вона доїхала б і не зʼявилась. */
    @Test
    fun `картка видима`() {
        val frame = PeerCard.encode(PeerCard.Body(PeerCard.Card.VOTING, payload))!!
        assertTrue("картка сховалась зі стрічки", WebMessengerKinds.isVisible(frame))
    }

    /** Покалічений кадр — це «зламане», а не «текст»: інакше в бульбашці дріт. */
    @Test
    fun `покалічена картка не стає текстом`() {
        val fields = WebMessengerKinds.fields("PH-CRD:kb;!!!не base64!!!")
        assertEquals(WebMessengerKinds.BROKEN, fields[WebMessengerKinds.KIND])
        assertEquals(WebMessengerKinds.CARD, fields[WebMessengerKinds.BROKEN_KIND])
    }

    // ── заслін за замовчуванням ──────────────────────────────────────────

    /**
     * РАТЧЕТ, заради якого все це.
     *
     * Міст складав справжній кадр рівно для одного роду, а все інше падало в
     * гілку `else` і їхало входом набраного — голим тілом у чужу бульбашку.
     * Саме так живе положення (`geo:live`) показувало співрозмовникові
     * `{"session_id":…,"point":{…}}` замість точки.
     *
     * Тут перевіряється не список відомих родів, а ПОВЕДІНКА невідомого: він
     * мусить отримати [WebMessengerKinds.Carry.Unknown], тобто чесну відмову.
     * Рід, доданий у веб завтра, спиниться тут видимо, а не поїде мовчки.
     */
    @Test
    fun `невідомий рід не їде входом набраного`() {
        val strangers = listOf(
            "geo:live",
            "geo:share",
            "webhook:event",
            "widget:gantt",
            "щойно вигаданий рід",
        )
        strangers.forEach { kind ->
            assertEquals(
                "рід «$kind» їде як слово людини — тіло ляже в чужу бульбашку сирим",
                WebMessengerKinds.Carry.Unknown(kind),
                WebMessengerKinds.carry(kind),
            )
        }
    }

    /**
     * Негативний контроль сторожа вище: якби [WebMessengerKinds.carry] відмовляв
     * геть усьому, попередній випадок зеленів би, а месенджер не слав би нічого.
     */
    @Test
    fun `слово, місце й картка далі їдуть`() {
        assertEquals(WebMessengerKinds.Carry.Typed, WebMessengerKinds.carry(WebMessengerKinds.TEXT))
        assertEquals(WebMessengerKinds.Carry.Typed, WebMessengerKinds.carry(WebMessengerKinds.IMAGE))
        assertEquals(WebMessengerKinds.Carry.Typed, WebMessengerKinds.carry(WebMessengerKinds.FILE))
        // Голосове й кружок — вкладення з описом (до 02.10 їх тут відмовляли,
        // і людям голос не їхав зовсім, хоч композер записував).
        assertEquals(WebMessengerKinds.Carry.Typed, WebMessengerKinds.carry("voice"))
        assertEquals(WebMessengerKinds.Carry.Typed, WebMessengerKinds.carry("video-note"))
        // Порожній рід — це лист без оголошення, тобто слово. Відмовити йому
        // означало б спинити звичайне надсилання.
        assertEquals(WebMessengerKinds.Carry.Typed, WebMessengerKinds.carry(""))
        assertEquals(
            WebMessengerKinds.Carry.Place,
            WebMessengerKinds.carry(WebMessengerKinds.GEO_POINT),
        )
        PeerCard.Card.entries.filterNot { it in PeerCard.ASSISTANT_ONLY }.forEach {
            assertEquals(
                "картка ${it.name} перестала їхати",
                WebMessengerKinds.Carry.Card(it),
                WebMessengerKinds.carry(it.webType),
            )
        }
        // Картки помічника веб не везе нікому: людині вони були б підробкою фактів.
        PeerCard.ASSISTANT_ONLY.forEach {
            assertEquals(WebMessengerKinds.Carry.Unknown(it.webType), WebMessengerKinds.carry(it.webType))
        }
    }

    /**
     * Рід картки, складений мостом, мусить лишитись НЕнабірним: він доходить до
     * розмови, тож рядок із таким початком, набраний пальцями, став би карткою,
     * якої ніхто не складав.
     */
    @Test
    fun `картку не можна набрати пальцями`() {
        val frame = PeerCard.encode(PeerCard.Body(PeerCard.Card.RACI, payload))!!
        assertNotNull("вхід набраного пропустив кадр картки", PeerFrames.refuseTyped(frame))
    }

    /** Веб шле таблицю словом `table` — міст бере її карткою, а не відмовою 422. */
    @Test
    fun `таблиця з веба їде карткою і повертається полем tableData`() {
        val carry = WebMessengerKinds.carry("table")
        assertEquals(WebMessengerKinds.Carry.Card(PeerCard.Card.TABLE), carry)
        val frame = PeerCard.encode(PeerCard.Body(PeerCard.Card.TABLE, payload))!!
        val fields = WebMessengerKinds.fields(frame)
        assertEquals("table", fields[WebMessengerKinds.KIND])
        assertEquals("tableData", fields[WebMessengerKinds.CARD_FIELD])
        assertEquals(payload, fields[WebMessengerKinds.CARD_JSON])
    }

    @Test
    fun `голосове без опису вкладення не їде словом`() {
        val described = """{"blob_id":"0123456789abcdef0123456789abcdef","key_hex":"aa","sha256":"cc","size":3000,"name":"voice_1.webm","mime":"audio/webm","kind":"voice"}"""
        assertEquals(false, WebMessengerKinds.mediaWithoutDescription("voice", described))
        assertEquals(true, WebMessengerKinds.mediaWithoutDescription("voice", "привіт"))
        assertEquals(true, WebMessengerKinds.mediaWithoutDescription("video-note", "{}"))
        assertEquals(false, WebMessengerKinds.mediaWithoutDescription("text", "привіт"))
    }

    @Test
    fun `голосове й кружок приходять своїм родом, а не файлом`() {
        fun body(kind: String?, name: String, mime: String) =
            """{"blob_id":"0123456789abcdef0123456789abcdef","key_hex":"aa","sha256":"cc","size":3000,"name":"$name","mime":"$mime"${kind?.let { ",\"kind\":\"$it\"" } ?: ""},"duration":4,"waveform":[0.1,0.9]}"""
        assertEquals("voice", WebMessengerKinds.fields(body("voice", "запис.webm", "audio/webm"))[WebMessengerKinds.KIND])
        assertEquals("video-note", WebMessengerKinds.fields(body("video-note", "x.webm", "video/webm"))[WebMessengerKinds.KIND])
        // Старе тіло без роду — за іменем, як і веб.
        assertEquals("voice", WebMessengerKinds.fields(body(null, "voice_1.webm", "application/octet-stream"))[WebMessengerKinds.KIND])
        assertEquals("image", WebMessengerKinds.fields(body(null, "IMG.jpg", "image/jpeg"))[WebMessengerKinds.KIND])
        assertEquals("file", WebMessengerKinds.fields(body(null, "звіт.pdf", "application/pdf"))[WebMessengerKinds.KIND])
        assertEquals(true, WebMessengerKinds.isSpokenKind("voice"))
    }
}
