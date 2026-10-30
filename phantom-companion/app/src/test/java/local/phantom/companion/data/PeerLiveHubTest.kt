package local.phantom.companion.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerLive
import local.phantom.companion.core.net.peer.PeerLiveShare
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ХАБ СЕАНСІВ — НА СТОЛІ, БЕЗ РАДІО.
 *
 * Дріт заходить у [PeerLiveHub] лямбдами саме заради цього: усе, що тут
 * доводиться, раніше можна було перевірити лише годиною на чужому телефоні.
 *
 * Три закони, кожен з яких одного разу був порушений:
 *
 * **№1 — сеанс без виміру нічого не обіцяє.** Свіжа установка без дозволу на
 * локацію заводила сеанс, не слала нічого й казала «телефон поруч — піде
 * одразу».
 *
 * **№2 — перша репліка йде з першим фіксом**, а не «через хвилину після того,
 * як зʼявиться».
 *
 * **№3 — після строку не лишається запису НІДЕ**, і вимітає його власна мітла
 * хаба, а не шар маршрутів на мапі: контакт, що поділився самим положенням, не
 * вимітався звідси ніколи.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLiveHubTest {

    private val THEM = "b".repeat(64)

    private fun peer(id: String = THEM) = PeerIdentity(
        id = id,
        name = "Марта",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "p",
        dhPubKey = "dh",
    )

    private class Wire {
        val sent = mutableListOf<Pair<String, String>>()
        val forgotten = mutableListOf<Set<String>>()
    }

    private fun hub(
        wire: Wire,
        points: PeerPointsRepository,
        clock: () -> Long,
        fix: () -> LocationFix?,
        allowed: () -> Boolean = { true },
        outbox: () -> List<PeerMessage> = { emptyList() },
    ) = PeerLiveHub(
        points = points,
        roster = { listOf(peer()) },
        fix = fix,
        fixAllowed = allowed,
        selfId = { "a".repeat(64) },
        addressed = { setOf(THEM) },
        relayOpen = { true },
        outbox = outbox,
        send = { id, body -> wire.sent += id to body },
        forget = { ids -> wire.forgotten += ids },
        nowMs = clock,
    )

    private fun measured(lat: Double = 50.45, lon: Double = 30.52) = LocationFix(
        lat = lat,
        lon = lon,
        register = FixRegister.MEASURED,
    )

    // ── №1 ───────────────────────────────────────────────────────────────

    @Test
    fun `сеанс без фікса не шле нічого й не обіцяє доставки`() = runTest {
        val wire = Wire()
        var now = 1_000_000L
        val h = hub(wire, PeerPointsRepository { now }, { now }, fix = { null })
        h.attach(backgroundScope)

        h.start(THEM, PeerLiveShare.Span.HOUR)

        assertEquals("без виміру по радіо не поїхало нічого", emptyList<Any>(), wire.sent)
        val banner = h.banners.value.single()
        assertFalse("плашка обіцяє доставку положення, якого немає", PeerLiveShare.promises(banner))
        assertEquals(PeerLiveShare.Position.SEARCHING, banner.position)
        assertFalse(banner.sent)
    }

    @Test
    fun `без дозволу сказано саме це, а не «шукаю»`() = runTest {
        val wire = Wire()
        var now = 1_000_000L
        val h = hub(wire, PeerPointsRepository { now }, { now }, fix = { null }, allowed = { false })
        h.attach(backgroundScope)

        h.start(THEM, PeerLiveShare.Span.HOUR)

        assertEquals(
            "«шукаю супутники» без дозволу — це порада чекати вічно",
            PeerLiveShare.Position.NO_PERMISSION,
            h.banners.value.single().position,
        )
        assertEquals(PeerLiveShare.Position.NO_PERMISSION, h.position.value)
    }

    // ── №2 ───────────────────────────────────────────────────────────────

    @Test
    fun `перша репліка йде щойно зʼявився фікс`() = runTest {
        val wire = Wire()
        var now = 1_000_000L
        var here: LocationFix? = null
        val h = hub(wire, PeerPointsRepository { now }, { now }, fix = { here })
        h.attach(backgroundScope)

        h.start(THEM, PeerLiveShare.Span.HOUR)
        assertTrue(wire.sent.isEmpty())

        here = measured()
        now += PeerLiveShare.TICK_MS
        advanceTimeBy(PeerLiveShare.TICK_MS + 1)
        runCurrent()

        assertEquals("перший фікс не поїхав", 1, wire.sent.size)
        val banner = h.banners.value.single()
        assertTrue("плашка мовчить про те, що положення вже пішло", banner.sent)
        assertTrue(PeerLiveShare.promises(banner))
    }

    // ── №3 ───────────────────────────────────────────────────────────────

    @Test
    fun `після строку положення не лишається ані в хабі, ані в сховищі`() = runTest {
        val wire = Wire()
        var now = 1_000_000L
        val points = PeerPointsRepository { now }
        val h = hub(wire, points, { now }, fix = { measured() })
        h.attach(backgroundScope)

        val expiresAt = now + 60_000L
        h.absorb(letter(expiresAt, now))

        assertNotNull("лист не ліг", h.standingFor(THEM))
        assertEquals(1, points.points.value.size)

        // Мітла тут прокидається ВЖЕ ПІСЛЯ спливу — так буває щоразу, коли
        // процес спав. Вийти мовчки, бо «строків у майбутньому немає», означало
        // б лишити чуже положення жити далі.
        now = expiresAt + 1
        advanceTimeBy(61_000L)
        runCurrent()

        assertNull("хаб тримає чуже положення після його строку", h.standingFor(THEM))
        assertEquals(
            "сховище тримає чуже положення після його строку",
            emptyList<Any>(),
            points.points.value,
        )
    }

    @Test
    fun `мітла заводиться від самого положення, без жодного маршруту`() = runTest {
        val wire = Wire()
        var now = 1_000_000L
        val points = PeerPointsRepository { now }
        val h = hub(wire, points, { now }, fix = { measured() })
        h.attach(backgroundScope)
        h.absorb(letter(now + 30_000L, now))
        // Мітла заводиться, доки строк ще попереду — звичайний хід.
        runCurrent()

        // Мутація, яка це червонить: прибрати rearmBroom() з absorb — тоді
        // нікому прокинутись, бо шар маршрутів на мапі тут ніхто не малює.
        now += 31_000L
        advanceTimeBy(31_000L)
        runCurrent()

        assertNull("хаб тримає положення після строку", h.standingFor(THEM))
        assertEquals(
            "сховище тримає положення після строку — мітла не дійшла до нього",
            emptyList<Any>(),
            points.points.value,
        )
    }

    private fun letter(expiresAtMs: Long, atMs: Long): PeerMessage = PeerMessage(
        id = "m1",
        fromId = THEM,
        toId = "a".repeat(64),
        body = PeerLive.encodeFix(
            PeerLive.Fix(
                lat = 50.45,
                lon = 30.52,
                accuracyM = 12,
                atMs = atMs,
                expiresAtMs = expiresAtMs,
                seq = 1,
            ),
        )!!,
        sentAtMs = atMs,
    )
}
