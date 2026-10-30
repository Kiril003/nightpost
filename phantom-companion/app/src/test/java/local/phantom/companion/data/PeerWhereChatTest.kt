package local.phantom.companion.data

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.core.net.peer.PeerWhereGrid
import local.phantom.companion.ui.messenger.WebMessengerWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val T0 = 1_790_000_000_000L

/** «Показати, де я» з розмови: той самий хаб, що й «Хто мене бачить», два телефони без радіо. */
class PeerWhereChatTest {

    private var now = T0

    private class MemoryGrants : PeerWhereGrants {
        var saved: Map<String, PeerWhere.Precision> = emptyMap()
        var ends: Map<String, Long> = emptyMap()
        override fun load() = saved
        override fun save(grants: Map<String, PeerWhere.Precision>) { saved = grants }
        override fun loadEnds() = ends
        override fun saveEnds(ends: Map<String, Long>) { this.ends = ends }
    }

    private fun person(id: String, proven: Boolean = true) = PeerIdentity(
        id = id,
        name = id,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pub-$id",
        dhPubKey = "dh-$id",
        verifiedAtMs = 1L,
        keySource = if (proven) PeerIdentity.KeySource.CAMERA else PeerIdentity.KeySource.SHARED,
        introducedAtMs = 1L,
    )

    private val here = LocationFix(50.4501, 30.5234, accuracyM = 8f, grade = FixGrade.NAVIGATION, register = FixRegister.MEASURED)

    private fun hub(people: List<PeerIdentity>, grants: PeerWhereGrants = MemoryGrants(), send: (String, String) -> Unit) =
        PeerWhereHub(
            points = PeerPointsRepository { now },
            roster = { people },
            fix = { here },
            send = send,
            fixAllowed = { true },
            grantStore = grants,
            nowMs = { now },
        )

    private lateinit var oksanaPhone: PeerWhereHub
    private val grants = MemoryGrants()
    private val mine by lazy {
        hub(listOf(person("oksana"), person("stranger", proven = false)), grants) { to, body ->
            if (to == "oksana") oksanaPhone.absorb(PeerMessage("m-$now", "me", "oksana", body, now))
        }
    }

    @Before
    fun setUp() {
        oksanaPhone = hub(listOf(person("me"))) { _, _ -> }
    }

    @Test
    fun `незвіреному контакту місце йде без камери`() {
        val v = mine.view("stranger")
        assertNull(v.whyNot)
        assertEquals(true, WebMessengerWhere.fields(v, now)["eligible"])
        assertTrue(mine.setPrecision("stranger", PeerWhere.Precision.DISTRICT, T0 + 15 * 60_000L))
    }

    @Test
    fun `новий телефон у людини — місце не йде, а розмова знає чому`() {
        val alarmed = hub(listOf(person("oksana").copy(keyChangedAtMs = T0))) { _, _ -> }
        assertFalse(alarmed.setPrecision("oksana", PeerWhere.Precision.DISTRICT, T0 + 15 * 60_000L))
        val v = alarmed.view("oksana")
        assertEquals(PeerWhereView.WhyNot.KEY_ALARM, v.whyNot)
        assertNull(v.mine)
        assertEquals(false, WebMessengerWhere.fields(v, now)["eligible"])
    }

    @Test
    fun `«15 хв» з розмови — картка з обох боків, і кадр гасне разом зі строком`() {
        val until = T0 + 15 * 60_000L
        assertTrue(mine.setPrecision("oksana", PeerWhere.Precision.DISTRICT, until))
        assertEquals(PeerWhereView.Mine(PeerWhere.Precision.DISTRICT, until), mine.view("oksana").mine)
        assertEquals(until, grants.ends["oksana"])

        val theirs = oksanaPhone.view("me").theirs!!
        assertEquals(PeerWhere.Precision.DISTRICT, theirs.precision)
        assertEquals(PeerWhereGrid.Nearness.SAME, theirs.near)
        assertEquals("кадр обіцяє більше, ніж людина дала", until, theirs.expiresAtMs)

        now = until
        oksanaPhone.sweep()
        assertNull("місце пережило строк у одержувача", oksanaPhone.view("me").theirs)
    }

    @Test
    fun `«Зупинити» в розмові стирає місце в одержувача одразу`() {
        assertTrue(mine.setPrecision("oksana", PeerWhere.Precision.EXACT, 0L))
        assertNotNull(oksanaPhone.view("me").theirs)
        now += 1_000L
        assertTrue(mine.setPrecision("oksana", null))
        assertNull(mine.view("oksana").mine)
        assertNull("стерте лишилось у одержувача", oksanaPhone.view("me").theirs)
        assertTrue(grants.saved.isEmpty())
    }

    @Test
    fun `рядок для веба не несе ні координат, ні метрів`() {
        mine.setPrecision("oksana", PeerWhere.Precision.EXACT, 0L)
        val row = WebMessengerWhere.fields(oksanaPhone.view("me"), now).toString()
        assertTrue(row.contains("precision=exact"))
        listOf("lat", "lon", "accuracy", "_m=", "50.45", "30.52").forEach {
            assertFalse("у рядку веба «$it»: $row", row.contains(it))
        }
        assertNull("меню не пропонує 30 хв", WebMessengerWhere.untilFor(30, now))
        assertEquals(0L, WebMessengerWhere.untilFor(null, now))
    }
}
