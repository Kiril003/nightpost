package local.phantom.companion.service

import android.net.Uri
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import local.phantom.companion.core.net.peer.PeerCallLine

/** Що система вміє попросити в застосунку. Слухавку бере рушій у вебі, тож «взяти» лише відчиняє месенджер. */
internal interface CallDoor {
    fun showRinging(line: PeerCallLine.Line.Ringing, name: String)
    fun answer()
    fun decline()
    fun hangUp()
}

internal class PeerCallConnection(
    private val door: CallDoor,
    val line: PeerCallLine.Line.Ringing,
    val name: String,
) : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        setAudioModeIsVoip(true)
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_SIP, line.peerId, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        setRinging()
    }

    override fun onShowIncomingCallUi() {
        IncomingCallTelecom.shown(line.callId)
        if (state == STATE_RINGING) door.showRinging(line, name)
    }

    override fun onAnswer() = door.answer()

    override fun onAnswer(videoState: Int) = door.answer()

    override fun onReject() {
        door.decline()
        end(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        door.hangUp()
        end(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        door.hangUp()
        end(DisconnectCause.CANCELED)
    }

    fun follow(next: PeerCallLine.Line) {
        when (next) {
            is PeerCallLine.Line.Talking ->
                if (next.callId == line.callId && state != STATE_DISCONNECTED) setActive()
            is PeerCallLine.Line.Over -> if (next.callId == line.callId) end(causeOf(next.why))
            PeerCallLine.Line.Idle -> end(DisconnectCause.LOCAL)
            is PeerCallLine.Line.Ringing, is PeerCallLine.Line.Dialing -> Unit
        }
    }

    private fun end(code: Int) {
        if (state != STATE_DISCONNECTED) {
            setDisconnected(DisconnectCause(code))
            destroy()
        }
        IncomingCallTelecom.release(line.callId)
    }

    private fun causeOf(why: String?): Int = when (why) {
        PeerCallLine.MISSED -> DisconnectCause.MISSED
        PeerCallLine.DECLINED -> DisconnectCause.REJECTED
        PeerCallLine.HUNG_UP -> DisconnectCause.REMOTE
        else -> DisconnectCause.LOCAL
    }
}
