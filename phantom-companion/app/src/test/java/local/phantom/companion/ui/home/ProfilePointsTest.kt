package local.phantom.companion.ui.home

import local.phantom.companion.R
import local.phantom.companion.core.solana.StatementOutcome
import local.phantom.companion.data.PointsClaims
import local.phantom.companion.data.VenueClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рядок «Бали» в Профілі — рішення, що він показує, без композиції.
 *
 * Чесні порожні стани: без сервера рядка немає; без мережі — «немає звʼязку», а
 * не нуль; нуль — лише коли сервер сам сказав «0».
 */
class ProfilePointsTest {

    private val answer = """{"total":15,"events":[
        {"kind":"meetup.happened","ref":"a","points":10,"at":1759860000000},
        {"kind":"payment.first","ref":"first","points":5,"at":1759870000000}],
        "rewards_eligible":false,"private":true}"""

    @Test
    fun `відповідь сервера читається, найновіше зверху, а кількість — з сервера`() {
        val view = PointsPanel.parse(answer)!!
        assertEquals(15, view.total)
        assertEquals(listOf("payment.first", "meetup.happened"), view.events.map { it.kind })
        assertEquals(listOf(5, 10), view.events.map { it.points })
        assertEquals(1759870000000L, view.events.first().atMs)
    }

    @Test
    fun `порожній журнал — це нуль із сервера, а кривa відповідь — не нуль`() {
        assertEquals(PointsView(0, emptyList()), PointsPanel.parse("""{"total":0,"events":[]}"""))
        for (bad in listOf("", "nope", "[]", "{}", """{"total":"x","events":[]}""", """{"total":1}""",
            """{"total":1,"events":[{"kind":"a"}]}""", """{"total":1,"events":[1]}""")) {
            assertNull("«$bad» прочиталось як бали", PointsPanel.parse(bad))
        }
        // Крива відповідь не малюється нулем: це «немає звʼязку», а не «0 балів».
        assertEquals(PointsLoad.Offline, PointsPanel.load(VenueClient.Answer.Ok("not json")))
    }

    @Test
    fun `що показує рядок у кожному стані`() {
        val shown = PointsPanel.load(VenueClient.Answer.Ok(answer))
        assertEquals(PointsRowValue.Number(15), PointsPanel.rowValue(shown, hasPending = false))
        assertEquals(PointsRowValue.Number(0), PointsPanel.rowValue(PointsLoad.Shown(PointsView(0, emptyList())), false))
        // Оплата чекає — рядок кличе її зарахувати, а не ховає це за сумою.
        assertEquals(PointsRowValue.Word(R.string.points_value_settle), PointsPanel.rowValue(shown, hasPending = true))
        assertEquals(PointsRowValue.Word(R.string.points_value_offline), PointsPanel.rowValue(PointsLoad.Offline, false))
        assertEquals(PointsRowValue.None, PointsPanel.rowValue(PointsLoad.Loading, false))
    }

    @Test
    fun `без сервера чи ключа рядка немає взагалі, без мережі — є`() {
        for (hidden in listOf(VenueClient.Answer.NotEnabled, VenueClient.Answer.NoKey, VenueClient.Answer.Missing)) {
            val load = PointsPanel.load(hidden)
            assertEquals(PointsLoad.Hidden, load)
            assertFalse(PointsPanel.visible(load))
            assertEquals(PointsRowValue.None, PointsPanel.rowValue(load, hasPending = true))
        }
        for (down in listOf(VenueClient.Answer.Unreachable, VenueClient.Answer.Refused(429, null, null))) {
            assertEquals(PointsLoad.Offline, PointsPanel.load(down))
            assertTrue(PointsPanel.visible(PointsLoad.Offline))
        }
        assertTrue(PointsPanel.visible(PointsLoad.Loading))
    }

    @Test
    fun `кожен рід події має своє слово, невідомий — загальне`() {
        val known = listOf("meetup.happened", "payment.first", "invite.joined").map(PointsPanel::kindLabel)
        assertEquals(3, known.toSet().size)
        assertEquals(R.string.points_kind_other, PointsPanel.kindLabel("якась.нова"))
        assertFalse(R.string.points_kind_other in known)
    }

    @Test
    fun `після «Зарахувати» людина чує, що сталося`() {
        fun note(o: StatementOutcome<PointsClaims.Settled>, en: Boolean = false) = PointsPanel.note(o, en)
        assertEquals(PointsNote(R.string.points_note_declined), note(StatementOutcome.Declined))
        assertEquals(PointsNote(R.string.msgr_scene_points_built_in), note(StatementOutcome.Unsupported))
        assertEquals(PointsNote(R.string.msgr_scene_no_wallet), note(StatementOutcome.NoWallet))
        assertEquals(PointsNote(R.string.points_note_counted, 5), note(StatementOutcome.Signed(PointsClaims.Settled.Counted(5))))
        assertEquals(PointsNote(R.string.points_note_already), note(StatementOutcome.Signed(PointsClaims.Settled.Counted(0))))
        assertEquals(PointsNote(R.string.points_note_not_confirmed), note(StatementOutcome.Signed(PointsClaims.Settled.NotConfirmedYet)))
        assertEquals(PointsNote(R.string.points_note_wallet_changed), note(StatementOutcome.Signed(PointsClaims.Settled.WalletChanged)))
        // Відмова сервера говорить мовою людини його власними словами.
        val refused = StatementOutcome.Signed(PointsClaims.Settled.Refused("Не ваш платіж", "Not your payment"))
        assertEquals("Не ваш платіж", note(refused).server)
        assertEquals("Not your payment", note(refused, en = true).server)
        assertNotEquals(
            PointsPanel.note(StatementOutcome.Signed(PointsClaims.Settled.Failed(VenueClient.Answer.Unreachable)), false),
            PointsPanel.note(StatementOutcome.Signed(PointsClaims.Settled.Failed(VenueClient.Answer.NotEnabled)), false),
        )
    }
}
