package local.phantom.companion.data.intent

import android.content.Context
import local.phantom.companion.core.net.intent.IntentGateway
import local.phantom.companion.data.EncryptedSlot
import local.phantom.companion.data.PeerLogSlot

/** Лічильник шлюзу на диску — зашифровано: там номери тих, хто чекає відповідь. */
class GatewayLedgerSlot internal constructor(private val slot: PeerLogSlot) : IntentGateway.LedgerSlot {

    constructor(context: Context) : this(EncryptedSlot(context, FILE_NAME, durable = true))

    override fun read(): String? = slot.read(KEY)

    override fun write(value: String) = slot.write(KEY, value)

    companion object {
        const val FILE_NAME = "phantom_gateway"
        private const val KEY = "ledger"
    }
}
