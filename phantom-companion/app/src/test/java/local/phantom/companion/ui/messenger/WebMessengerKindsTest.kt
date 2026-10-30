package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerLive
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerPayload
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_788_517_702_898L
private const val THEM = "them"
private const val ME = "me"

/**
 * Що міст віддає вебові за рід і за поля.
 *
 * Власник бачив у месенджері рядок `PH-RTE:1788…;337;258;foot;…`, розірваний на
 * шматки: міст класифікував лист лише за вкладенням, тож маршрут і місце їхали
 * родом «text» із сирим тілом. Тут перевіряється ЗНАЧЕННЯ, яке міст складає, —
 * не текст його джерела.
 *
 * `org.json` у модульних тестах `app` є заглушкою, яка кидає на першому виклику,
 * тож рід і поля живуть у [WebMessengerKinds] без жодного `JSONObject`. Що міст
 * саме їх і розкладає — видно з місця виклику, і це єдина ланка тут, яку тест не
 * тримає.
 */
class WebMessengerKindsTest {

    private val route = PeerLive.Route(
        expiresAtMs = NOW + PeerLive.ROUTE_LIFE_MS,
        distanceM = 337.0,
        durationS = 258L,
        profile = "foot",
        waypoints = listOf(50.45010 to 30.52340, 50.45120 to 30.52330),
        shape = listOf(50.45010 to 30.52340, 50.45050 to 30.52100, 50.45120 to 30.52330),
    )

    @Test
    fun `справжній кадр маршруту приходить розібраним`() {
        val f = WebMessengerKinds.fields(PeerLive.encodeRoute(route)!!)

        assertEquals(WebMessengerKinds.ROUTE, f[WebMessengerKinds.KIND])
        assertEquals("foot", f["route_profile"])
        assertEquals(337.0, f["route_distance_m"] as Double, 0.5)
        assertEquals(258L, f["route_duration_s"])
        assertEquals(route.expiresAtMs, f["route_expires_at_ms"])
        // Ламана їде кодуванням із точністю 1e-5, тож звіряється з допуском:
        // рівність до біта тут перевіряла б арифметику, а не поле.
        val waypoints = f["route_waypoints"] as List<*>
        assertEquals(2, waypoints.size)
        route.waypoints.forEachIndexed { i, (lat, lon) ->
            val point = waypoints[i] as List<*>
            assertEquals(lat, point[0] as Double, 1e-5)
            assertEquals(lon, point[1] as Double, 1e-5)
        }
        // Ламана для мініатюри: стільки ж точок, скільки поїхало дротом.
        assertEquals(3, (f["route_shape"] as List<*>).size)
    }

    @Test
    fun `справжній кадр місця приходить розібраним`() {
        val f = WebMessengerKinds.fields(PeerPayload.encodePlace(50.45010, 30.52340, 25))

        assertEquals(WebMessengerKinds.PLACE, f[WebMessengerKinds.KIND])
        assertEquals(50.45010, f["place_lat"] as Double, 1e-5)
        assertEquals(30.52340, f["place_lon"] as Double, 1e-5)
        assertEquals(25, f["place_accuracy_m"])
    }

    /** Точності не сказали — null, а не нуль: нуль тут читався б як вимір. */
    @Test
    fun `місце без точності не вигадує її`() {
        val f = WebMessengerKinds.fields(PeerPayload.encodePlace(50.45010, 30.52340))
        assertEquals(WebMessengerKinds.PLACE, f[WebMessengerKinds.KIND])
        assertNull(f["place_accuracy_m"])
    }

    @Test
    fun `пошкоджений кадр каже про пошкодження, а не друкує дріт`() {
        listOf(
            PeerFrames.Kind.ROUTE.mark + "не число;не число",
            PeerFrames.Kind.PLACE.mark + "тут десь",
        ).forEach { wire ->
            val f = WebMessengerKinds.fields(wire)
            assertEquals(
                "пошкоджений кадр не назвався пошкодженим: $wire",
                WebMessengerKinds.BROKEN,
                f[WebMessengerKinds.KIND],
            )
            assertTrue(
                "у полях лишився сирий дріт: $wire",
                f.values.none { it is String && it.contains(PeerFrames.Kind.ROUTE.mark.take(3)) },
            )
        }
    }

    /**
     * Спільний сторож обох задач: жоден лист, тіло якого починається зі
     * службового початку, не віддається вебові як «text». Саме цей рід малює
     * бульбашку з тілом — тобто саме він і клав протокол людині на скло.
     */
    @Test
    fun `жоден службовий початок не віддається родом text`() {
        val leaking = PeerFrames.Kind.entries.filter { kind ->
            listOf(kind.mark, kind.mark + "1", "  " + kind.mark + "1").any {
                WebMessengerKinds.fields(it)[WebMessengerKinds.KIND] == WebMessengerKinds.TEXT
            }
        }.map { it.name }
        assertEquals("службовий початок приїхав до веба як text", emptyList<String>(), leaking)
    }

    @Test
    fun `слово людини лишається словом`() {
        val f = WebMessengerKinds.fields("виходь на звʼязок")
        assertEquals(WebMessengerKinds.TEXT, f[WebMessengerKinds.KIND])
        assertEquals(1, f.size)
    }

    @Test
    fun `вкладення й далі розпізнається за описом`() {
        val head = "{\"blob_id\":\"" + "0".repeat(32) + "\",\"key_hex\":\"aa\"," +
            "\"sha256\":\"bb\",\"size\":10,\"mime\":"
        assertEquals(
            WebMessengerKinds.IMAGE,
            WebMessengerKinds.fields(head + "\"image/jpeg\"}")[WebMessengerKinds.KIND],
        )
        assertEquals(
            WebMessengerKinds.FILE,
            WebMessengerKinds.fields(head + "\"application/pdf\"}")[WebMessengerKinds.KIND],
        )
    }

    /**
     * Роди композера, яких дріт не знає.
     *
     * Рідний екран розмови знав їх тринадцять і мав заслін за замовчуванням на
     * будь-який `PH-`; веб теж має свій; міст не мав жодного, тож кожна така
     * картка їхала вебові родом «text» із протоколом усередині. Перелік нижче
     * узятий з того рідного заслону (`ChatHub.previewKindRes`), поки він ще
     * стояв у дереві: 06.09 екран пішов, а тринадцять родів лишились у чергах
     * і в композері, тож перелік лишається дійсним і без нього.
     */
    @Test
    fun `картка композера ніколи не їде як text із сирим тілом`() {
        val cards = listOf(
            "PH-VOICE:12;voice_1", "PH-TABLE:кошторис;а,б", "PH-TASKLIST:план;один:0:Марта",
            "PH-POLL:куди;ліворуч;праворуч", "PH-BILL:вечеря;650;Марта:325:0",
            "PH-CHART:темп;1,2,3;росте", "PH-MULTIQUOTE:нарада;раз;два",
            "PH-EVENT:виїзд;12.09;07:00;міст", "PH-CODE:патч;kotlin;val x = 1",
            "PH-FILE:plan.pdf;148KB", "PH-IMAGE:recon.jpg", "PH-KEY:abc",
            // Рід, якого сьогодні немає: заслін мусить ловити і його.
            "PH-NEWKIND:будь-що",
        )
        cards.forEach { body ->
            val f = WebMessengerKinds.fields(body)
            val kind = f[WebMessengerKinds.KIND]
            // Рід «other» звідси прибрано разом із вадою, яку він створював:
            // у вебі немає жодного читача `other_mark`, тож картка приїжджала
            // порожньою бульбашкою. Тепер кінців два, і обидва щось кажуть.
            val drawn = f[WebMessengerKinds.CARD] != null
            val said = f[WebMessengerKinds.NOTICE] != null
            assertTrue(
                "картка композера не отримала ані вмісту, ані слів: $body → $kind",
                drawn || said || kind == WebMessengerKinds.HIDDEN,
            )
            // Тіло веб бере не звідси, а з полів, які МІСТ перетворює на
            // рядок. Тож сирий дріт не сміє лежати в жодному з полів, що
            // їдуть до веба як є.
            val payload = body.substringAfter(':', "")
            assertTrue("зразок без двокрапки нічого не доводить: $body", payload.isNotEmpty())
            assertFalse(
                "сирий дріт протік у поля, що їдуть вебові: $body",
                WebMessengerKinds.shipped(f).values.any { it is String && it.contains(payload) },
            )
        }
    }

    /**
     * Заслін ловить позначку, а не речення, що з неї починається.
     *
     * Без цієї межі «PH-3 котушки, забери» поїхало б родом «other» з порожнім
     * тілом — тобто речення людини зникло б у неї з очей. Це та сама мовчазна
     * підміна набраного, від якої вся ця хвиля й починалась.
     */
    @Test
    fun `речення людини, що починається з позначки, лишається словом`() {
        listOf(
            "PH-3 котушки, забери сьогодні",
            "PH- це не позначка",
            "PHANTOM: наш",
            "ph-rte: малими літерами це не кадр",
        ).forEach { said ->
            assertEquals(
                "речення людини визнано карткою: $said",
                WebMessengerKinds.TEXT,
                WebMessengerKinds.fields(said)[WebMessengerKinds.KIND],
            )
        }
    }

    /**
     * РЯДОК ПЕРЕЛІКУ РОЗМОВ — друге місце, де власник бачив сире.
     *
     * Бульбашку полагодили першою хвилею, а `last_snippet` і далі віддавав
     * `PH-RTE:1788…` цілим. Рід і пласкі поля рядка мусять бути ті самі, що й
     * у листі, а ламана — не їхати зовсім.
     */
    @Test
    fun `рядок розмови несе розібрані поля, а не дріт`() {
        val row = WebMessengerKinds.rowFields(PeerLive.encodeRoute(route)!!)
        assertEquals(WebMessengerKinds.ROUTE, row["last_kind"])
        assertEquals("foot", row["last_route_profile"])
        assertEquals(337.0, row["last_route_distance_m"] as Double, 0.5)
        assertEquals(258L, row["last_route_duration_s"])
        // Ламана в рядок списку не їде: перелік малює слово, не мініатюру.
        assertNull(row["last_route_shape"])
        assertNull(row["last_route_waypoints"])

        val place = WebMessengerKinds.rowFields(PeerPayload.encodePlace(50.45010, 30.52340, 25))
        assertEquals(WebMessengerKinds.PLACE, place["last_kind"])
        assertEquals(50.45010, place["last_place_lat"] as Double, 1e-5)
        assertEquals(30.52340, place["last_place_lon"] as Double, 1e-5)
    }

    /**
     * Спільний сторож РЯДКА: жодне тіло з машинною позначкою не лишає в рядку
     * розмови роду, за яким веб намалює тіло, і жодного поля з сирим дротом.
     */
    @Test
    fun `жоден рядок розмови не віддає машинну позначку словом людини`() {
        val bodies = PeerFrames.Kind.entries.map { it.mark + "1" } +
            listOf("PH-POLL:куди;ліворуч", "PH-VOICE:12;voice_1", PeerLive.encodeRoute(route)!!)
        bodies.forEach { body ->
            val row = WebMessengerKinds.rowFields(body)
            // «Слово людини» тут дозволене рівно тоді, коли міст ПІДМІНИТЬ
            // тіло чесним реченням: рід стає text, але рядком поїде не дріт, а
            // складене з ресурсів речення. Без цієї умови вийшов би заслін,
            // який забороняє єдиний чесний кінець для голосового без звуку.
            val substituted = WebMessengerKinds.fields(body)[WebMessengerKinds.NOTICE] != null
            assertFalse(
                "рядок розмови вважає машинну позначку словом людини: $body",
                WebMessengerKinds.isSpokenKind(row["last_kind"]) && !substituted,
            )
            assertFalse(
                "у полях рядка лишився сирий дріт: $body",
                row.values.any { it is String && it.contains(PeerFrames.MARK) && it.contains(';') },
            )
        }
    }

    private fun letter(body: String) =
        PeerMessage(id = body.take(16), fromId = THEM, toId = "me", body = body, sentAtMs = 1_000L)

    /**
     * ЧИСЛО, ПЕРЕДОГЛЯД І СТРІЧКА — ОДНА МНОЖИНА, і рівність тут ДОРЕЧНА.
     *
     * Плашка обіцяла непрочитане, якого в стрічці немає: лічильник рахував через
     * [PeerOutbox.isSpoken], а веб дію над складом (`PH-SYS:`) не малює. Людина
     * бачила «1», відкривала розмову — там нічого нового.
     *
     * ЧОМУ ТУТ РІВНІСТЬ, А ДЛЯ `isSpoken`/`sendTyped` — ОДНОБІЧНИЙ ІНВАРІАНТ.
     * Там дві сторони це ПРИЙОМ і ВІДМОВА: різні шари з різними обовʼязками, і
     * звузити відмову заради симетрії означало б приймати `PH-GRP:` та мовчки
     * ховати — тому там правильно `spoken − refused == ∅`. Тут обидва числа
     * народжуються в ОДНОМУ шарі й описують ОДНУ стрічку, тож розбіжність у
     * будь-який бік — це вже неправда людині. Не «уніфікуй» одне за зразком
     * іншого: правило різне тому, що різні шари.
     *
     * Мутація в обидва боки: прибери [WebMessengerKinds.isVisible] з `unread`
     * (рахує більше, ніж видно) або з `feed` (видно більше, ніж рахує) —
     * червоніє в обох випадках і називає тіло.
     */
    @Test
    fun `видиме, лічильник і передогляд — одна множина`() {
        val bodies = PeerFrames.Kind.entries.map { it.mark + "1" } + listOf(
            "виходь на звʼязок",
            "PH-POLL:куди;ліворуч",
            PeerLive.encodeRoute(route)!!,
            PeerPayload.encodePlace(50.45010, 30.52340, 25),
        )
        val disagreeing = bodies.filter { body ->
            val log = listOf(letter(body))
            val inFeed = WebMessengerKinds.feed(log.filter { PeerOutbox.isSpoken(it) }).isNotEmpty()
            val counted = WebMessengerKinds.unread(log, THEM, 0L) == 1
            val previewed = WebMessengerKinds.lastVisible(log, THEM) != null
            inFeed != counted || counted != previewed
        }
        assertEquals(
            "міст рахує або показує в переліку не те, що віддає стрічкою — " +
                "плашка обіцяє людині лист, якого вона в розмові не знайде",
            emptyList<String>(),
            disagreeing,
        )
    }

    /**
     * Рівно сьогоднішній випадок, названий вголос: дія над складом доходить до
     * розмови ([PeerOutbox.isSpoken] її пускає), але веб її не малює — отже вона
     * не сміє потрапити ані в число, ані в рядок переліку.
     */
    @Test
    fun `дія над складом не рахується і не стає останнім сказаним`() {
        val act = letter(PeerFrames.Kind.SYSTEM.mark + "leave")
        assertTrue("сторож осліп: кадр більше не доходить до розмови", PeerOutbox.isSpoken(act))
        assertEquals(WebMessengerKinds.HIDDEN, WebMessengerKinds.fields(act.body)[WebMessengerKinds.KIND])
        assertEquals(0, WebMessengerKinds.unread(listOf(act), THEM, 0L))
        assertNull(WebMessengerKinds.lastVisible(listOf(act), THEM))
        assertTrue(WebMessengerKinds.feed(listOf(act)).isEmpty())
    }

    // ── дорога ───────────────────────────────────────────────────────────

    private fun mine(
        state: PeerMessage.State = PeerMessage.State.DELIVERED,
        heldNonce: String = "",
        at: Long = 1_000L,
        to: String = THEM,
    ) = PeerMessage(
        id = "me>$to@$at:$heldNonce:$state",
        fromId = ME,
        toId = to,
        body = "поїхали",
        sentAtMs = at,
        state = state,
        heldNonce = heldNonce,
    )

    /**
     * Саме та вада: значок «Доставлено напряму через WebRTC P2P DataChannel»
     * стояв на листі, який пролежав у сховку.
     */
    @Test
    fun `лист зі сховка не називається прямою дорогою`() {
        val held = mine(state = PeerMessage.State.HELD, heldNonce = "nonce")
        assertEquals(WebMessengerKinds.MAILBOX, WebMessengerKinds.road(held, ME))
        // Квитанція не стирає обліку сховка — інакше дійшлий лист починав би
        // брехати про дорогу рівно в мить, коли про неї питають.
        val arrived = held.copy(state = PeerMessage.State.DELIVERED)
        assertEquals(WebMessengerKinds.MAILBOX, WebMessengerKinds.road(arrived, ME))
    }

    @Test
    fun `дорога є лише тоді, коли лист уже кудись доїхав`() {
        assertEquals(WebMessengerKinds.DIRECT, WebMessengerKinds.road(mine(), ME))
        assertNull(WebMessengerKinds.road(mine(state = PeerMessage.State.QUEUED), ME))
        assertNull(WebMessengerKinds.road(mine(state = PeerMessage.State.SENT), ME))
        assertNull(WebMessengerKinds.road(mine(state = PeerMessage.State.FAILED), ME))
    }

    /**
     * `PeerEnvelope` зрізає облік сховка перед запечатуванням, і обидві вхідні
     * двері лишають рядок однаковим — про чужу дорогу цей телефон не знає
     * нічого. Мовчання тут єдина чесна відповідь.
     */
    @Test
    fun `про чужий лист дорога не вигадується`() {
        val theirs = letter("привіт").copy(state = PeerMessage.State.DELIVERED)
        assertNull(WebMessengerKinds.road(theirs, ME))
        // Доки сітка не піднялась, ми не знаємо навіть, хто ми — і мовчимо.
        assertNull(WebMessengerKinds.road(mine(), ""))
    }

    @Test
    fun `у групі дорога є лише тоді, коли всі копії згодні`() {
        val all = listOf(mine(to = "a", heldNonce = "n1"), mine(to = "b", heldNonce = "n2"))
        assertEquals(WebMessengerKinds.MAILBOX, WebMessengerKinds.road(all, ME))
        val direct = listOf(mine(to = "a"), mine(to = "b"))
        assertEquals(WebMessengerKinds.DIRECT, WebMessengerKinds.road(direct, ME))
        val mixed = listOf(mine(to = "a", heldNonce = "n1"), mine(to = "b"))
        assertNull("одна копія сказала за решту", WebMessengerKinds.road(mixed, ME))
        val moving = listOf(mine(to = "a"), mine(to = "b", state = PeerMessage.State.QUEUED))
        assertNull(WebMessengerKinds.road(moving, ME))
        assertNull(WebMessengerKinds.road(emptyList<PeerMessage>(), ME))
    }

    /**
     * Мутація, якою цей сторож перевіряється: зніми відмову в
     * [PeerFrames.refuseTyped] — і набране тіло стає карткою.
     */
    @Test
    fun `набране тіло зі службовим початком не доїде до розбору`() {
        PeerFrames.Kind.entries.forEach { kind ->
            assertNotEquals(
                "набране з початком ${kind.name} не відмовлено, отже стане карткою",
                null,
                PeerFrames.refuseTyped(kind.mark + "1"),
            )
        }
    }

    // ── доля листа поза цим апаратом ─────────────────────────────────────

    private fun held(
        state: PeerMessage.State,
        nonce: String = "",
        heldUntilMs: Long = 0L,
    ) = PeerMessage(
        id = "m1",
        fromId = ME,
        toId = THEM,
        body = "слово",
        sentAtMs = NOW,
        state = state,
        heldNonce = nonce,
        heldUntilMs = heldUntilMs,
    )

    @Test
    fun `покладене в сховок не можна показати як те, що лежить у нас`() {
        // Сховок відповів 200 і назвав nonce — це ВИМІР, і згорнути його в
        // «лист у вашій черзі» означає сказати людині, що телефон ще тримає
        // лист, якого він уже віддав.
        val stored = held(PeerMessage.State.HELD, nonce = "n1", heldUntilMs = NOW + 1_000L)
        assertEquals(WebMessengerKinds.RELAY_STORED, WebMessengerKinds.relayState(stored))
        assertNotEquals(WebMessengerKinds.RELAY_LOCAL, WebMessengerKinds.relayState(stored))
    }

    @Test
    fun `без квитанції сховок не вдає доставку`() {
        val stored = held(PeerMessage.State.HELD, nonce = "n1", heldUntilMs = NOW + 1_000L)
        assertNotEquals(WebMessengerKinds.RELAY_ACKED, WebMessengerKinds.relayState(stored))
        assertEquals(
            WebMessengerKinds.RELAY_ACKED,
            WebMessengerKinds.relayState(held(PeerMessage.State.DELIVERED)),
        )
    }

    @Test
    fun `HELD без nonce — це ще не сховок`() {
        // Стан без nonce означає, що вкидання не підтверджене нічим; казати
        // «покладено» тут було б тим самим позитивним твердженням навпаки.
        assertEquals(
            WebMessengerKinds.RELAY_LOCAL,
            WebMessengerKinds.relayState(held(PeerMessage.State.HELD)),
        )
        assertEquals(
            WebMessengerKinds.RELAY_LOCAL,
            WebMessengerKinds.relayState(held(PeerMessage.State.QUEUED)),
        )
    }

    @Test
    fun `картка переказу несе нотатку з кадру або null без неї`() {
        val to = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
        val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
        val withNote = "PH-PAY:2;devnet;usdc;2500000;${to.base58};$sig;n0123456789a;pizza"
        val paymentWithNote = WebMessengerKinds.fields(withNote)[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertEquals("n0123456789a", paymentWithNote["ref"])
        assertEquals("pizza", paymentWithNote["note"])

        val withoutNote = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_500_000, to, sig).encode()
        val paymentWithoutNote = WebMessengerKinds.fields(withoutNote)[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertNull(paymentWithoutNote["ref"])
        assertNull(paymentWithoutNote["note"])
    }
}
