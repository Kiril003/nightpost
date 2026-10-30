package local.phantom.companion.assistant

import java.time.LocalDateTime
import java.time.ZoneId
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.ui.messenger.WebMessengerKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDayCardTest {

    @Test
    fun `today asks get the day card, other day questions stay with the model`() {
        for (ask in listOf(
            "Що в мене сьогодні?", "що у мене на сьогодні", "Які в мене плани на сьогодні?", "Мій день",
            "Nightpost, що сьогодні?", "План на сьогодні", "What's on today?", "my day", "Co mám dnes?",
        )) assertTrue(ask, AssistantDayCard.asks(ask))
        for (ask in listOf(
            "Які в мене плани на вечір?", "Що в мене завтра?", "Що сьогодні за свято?", "Нагадай сьогодні о 18:00 про посилку",
            "Скільки в мене на рахунку?", "сьогодні", "",
        )) assertFalse(ask, AssistantDayCard.asks(ask))
    }

    @Test
    fun `the card carries the local date and nothing from the day itself`() {
        val kyiv = ZoneId.of("Europe/Kyiv")
        val now = LocalDateTime.of(2026, 10, 22, 0, 30).atZone(kyiv).toInstant().toEpochMilli()
        val card = PeerCard.decode(AssistantDayCard.body(now, kyiv))!!
        assertEquals(PeerCard.Card.DAY, card.what)
        assertEquals("widget:day", card.what.webType)
        assertEquals("""{"v":1,"day":"2026-10-22","at":$now}""", card.payload)
        val row = WebMessengerKinds.fields(AssistantDayCard.body(now, kyiv))
        assertEquals("widget:day", row[WebMessengerKinds.KIND])
        assertEquals("dayData", row[WebMessengerKinds.CARD_FIELD])
        assertTrue(WebMessengerKinds.isVisible(AssistantDayCard.body(now, kyiv)))
    }
}
