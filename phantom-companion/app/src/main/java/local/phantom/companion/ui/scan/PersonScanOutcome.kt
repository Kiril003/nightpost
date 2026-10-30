package local.phantom.companion.ui.scan

import kotlin.coroutines.cancellation.CancellationException
import local.phantom.companion.core.net.peer.PeerIdentity

/**
 * Що стається після того, як людину додано кодом. Камера і є звірка наживо
 * (одностороння, для того, хто сканував); посилання — ні. І там, і там далі
 * відкривається розмова, а не картка з числом для порівняння.
 */
object PersonScanOutcome {

    data class Outcome(val markVerified: Boolean, val openChat: String?, val openCard: String? = null)

    /** Ключ змінився, а звірки наживо немає — спершу картка, яка це каже, не розмова. */
    fun after(source: PeerIdentity.KeySource, accepted: PeerIdentity): Outcome {
        val camera = source == PeerIdentity.KeySource.CAMERA
        return if (!camera && accepted.keyAlarm) {
            Outcome(markVerified = false, openChat = null, openCard = accepted.id)
        } else {
            Outcome(markVerified = camera, openChat = accepted.id)
        }
    }

    suspend fun land(
        source: PeerIdentity.KeySource,
        accept: suspend (PeerIdentity.KeySource) -> PeerIdentity,
        markVerified: (String) -> Unit,
        openChat: (String) -> Unit,
        openCard: (String) -> Unit,
    ): Outcome? {
        val peer = try {
            accept(source)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val outcome = after(source, peer)
        if (outcome.markVerified) runCatching { markVerified(peer.id) }
        outcome.openCard?.let(openCard)
        outcome.openChat?.let(openChat)
        return outcome
    }
}
