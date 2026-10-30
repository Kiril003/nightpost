package local.phantom.companion.data.intent

import android.content.Context
import local.phantom.companion.core.net.intent.IntentClient
import local.phantom.companion.data.EncryptedSlot
import local.phantom.companion.data.PeerLogSlot

/**
 * «Мої прохання» на диску — зашифровано, як і листи: там питання людини й
 * запечатані кадри, а телефон можна загубити.
 */
class IntentJournalSlot internal constructor(private val slot: PeerLogSlot) : IntentClient.JournalSlot {

    constructor(context: Context) : this(EncryptedSlot(context, FILE_NAME, durable = true))

    override fun read(): String? = slot.read(KEY)

    override fun write(value: String) = slot.write(KEY, value)

    companion object {
        const val FILE_NAME = "phantom_intents"
        private const val KEY = "journal"
    }
}
