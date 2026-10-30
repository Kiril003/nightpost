package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BillView(val status: BillStatus, val paid: Int, val total: Int, val deadline: Long)

object BillRead {
    /** Відповідь getAccountInfo → стан рахунку; null, якщо рахунку немає, власник не наша програма чи формат чужий. */
    fun parse(reply: JsonElement, program: SolanaKey): BillView? = runCatching {
        val value = reply.jsonObject["value"]?.takeUnless { it is JsonNull }?.jsonObject ?: return null
        if (value["owner"]?.jsonPrimitive?.contentOrNull != program.base58) return null
        val data = value["data"]?.jsonArray ?: return null
        if (data.size != 2 || data[1].jsonPrimitive.content != "base64") return null
        val bill = BillProgram.decodeBill(java.util.Base64.getDecoder().decode(data[0].jsonPrimitive.content)) ?: return null
        BillView(bill.status, bill.shares.count { it.paid }, bill.shares.size, bill.deadline)
    }.getOrNull()
}
