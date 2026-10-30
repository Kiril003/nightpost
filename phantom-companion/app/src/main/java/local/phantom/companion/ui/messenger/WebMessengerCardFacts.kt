package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import local.phantom.companion.core.net.peer.PeerCard
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * Рядок картки словами для пошуку: «назва · сума/місце/час», як `services/cardFacts.ts`
 * у вебі. Друга форма — з десятковою крапкою (en).
 */
internal object WebMessengerCardFacts {

    fun lines(fields: Map<String, Any?>, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        (fields[WebMessengerKinds.PAYMENT] as? Map<*, *>)?.let { p ->
            val amount = p["amount"] as? String ?: return emptyList()
            return listOf("$amount ${p["token"] ?: ""}".trim())
        }
        val body = fields[WebMessengerKinds.CARD_JSON] as? String ?: return emptyList()
        val card = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        return when (fields[WebMessengerKinds.KIND]) {
            PeerCard.Card.BILL.webType -> bill(card)
            PeerCard.Card.FAMILY_EXPENSE.webType -> expense(card)
            PeerCard.Card.MEETUP.webType -> meetup(card, zone)
            else -> emptyList()
        }
    }

    private fun bill(card: JsonObject): List<String> {
        val token = text(card["token"]) ?: return emptyList()
        val items = card["items"] as? JsonArray ?: return emptyList()
        var sum = BigDecimal.ZERO
        for (item in items) {
            val price = text((item as? JsonObject)?.get("price"))?.toBigDecimalOrNull() ?: return emptyList()
            sum += price
        }
        val plain = sum.stripTrailingZeros().let { if (token == "USDC" && it.scale() < 2) it.setScale(2) else it }.toPlainString()
        val title = text(card["title"])
        return listOf(plain.replace('.', ','), plain).distinct().map { join(title, "$it $token") }
    }

    private fun expense(card: JsonObject): List<String> {
        val items = card["items"] as? JsonArray ?: return emptyList()
        val total = items.sumOf { ((it as? JsonObject)?.get("amountUAH") as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
        val cents = (total * 100).roundToLong()
        val currency = text(card["currency"]).orEmpty()
        val title = text(card["title"])
        return listOf(money(cents, ' ', ','), money(cents, ',', '.')).distinct().map { join(title, "$it $currency".trim()) }
    }

    private fun meetup(card: JsonObject, zone: ZoneId): List<String> {
        val venue = text((card["venue"] as? JsonObject)?.get("name"))
        val title = text(card["title"])
        val at = (card["at"] as? JsonPrimitive)?.longOrNull
        val time = at?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(it)) }
        val head = title ?: venue ?: return emptyList()
        return listOf(join(head, if (title != null) venue else null, time))
    }

    /** Як `money()` вебу: ціле без копійок, інакше два знаки; групи по три. */
    private fun money(cents: Long, group: Char, decimal: Char): String {
        val whole = (Math.abs(cents) / 100).toString().reversed().chunked(3).joinToString(group.toString()).reversed()
        val sign = if (cents < 0) "-" else ""
        val frac = Math.abs(cents) % 100
        return if (frac == 0L) "$sign$whole" else "$sign$whole$decimal${frac.toString().padStart(2, '0')}"
    }

    private fun join(vararg xs: String?): String = xs.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" · ")

    private fun text(el: Any?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
}
