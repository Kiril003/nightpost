package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import local.phantom.companion.core.net.peer.PeerCard

/**
 * Назва й сума картки для рядка переліку («Pho dinner · 840 ₴»). Тіло картки
 * в рядок не їде — лише ці три пласкі поля.
 */
internal object WebMessengerCardLine {

    private const val MAX_TITLE = 80

    fun row(fields: Map<String, Any?>): Map<String, Any?> {
        val body = fields[WebMessengerKinds.CARD_JSON] as? String ?: return emptyMap()
        val card = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, Any?>()
        listOf("title", "question", "topic")
            .firstNotNullOfOrNull { key -> text(card[key]) }
            ?.let { out["last_card_title"] = it.take(MAX_TITLE) }
        if (fields[WebMessengerKinds.KIND] == PeerCard.Card.FAMILY_EXPENSE.webType) {
            (card["items"] as? JsonArray)?.let { items ->
                out["last_card_total"] = items.sumOf { item ->
                    ((item as? JsonObject)?.get("amountUAH") as? JsonPrimitive)?.doubleOrNull ?: 0.0
                }
            }
            text(card["currency"])?.let { out["last_card_currency"] = it }
        }
        // «Гроші · 5 SOL» — лише коли баланс один: суму з кількох монет не складають.
        if (fields[WebMessengerKinds.KIND] == PeerCard.Card.MONEY.webType) {
            ((card["balances"] as? JsonArray)?.singleOrNull() as? JsonObject)?.let { b ->
                val amount = text(b["amount"])?.toDoubleOrNull()
                val coin = text(b["coin"])
                if (amount != null && coin != null) {
                    out["last_card_total"] = amount
                    out["last_card_currency"] = coin
                }
            }
        }
        return out
    }

    fun isGift(wire: String): Boolean = PeerCard.decode(wire)?.what == PeerCard.Card.GIFT

    private fun text(el: Any?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
}
