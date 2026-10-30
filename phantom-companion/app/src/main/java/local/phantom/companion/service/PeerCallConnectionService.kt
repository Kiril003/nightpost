package local.phantom.companion.service

import android.content.Context
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.net.peer.PeerCallLine

/**
 * Двері, якими система приносить вхідний дзвінок застосунку й повертає кнопки
 * гарнітури. Вихідних через Telecom немає: набирає месенджер.
 */
class PeerCallConnectionService : ConnectionService() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val (line, name) = ringOf(request)
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        if (!IncomingCallTelecom.isPending(line.callId)) return Connection.createCanceledConnection()
        return PeerCallConnection(AppCallDoor(applicationContext), line, name).also(IncomingCallTelecom::adopt)
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        val (line, name) = ringOf(request) ?: return
        IncomingCallTelecom.ringIfUnshown(applicationContext, line, name)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection = Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

    /** Ким і про що дзвонять: з додатків запиту, пласких або вкладених, як їх кладе Telecom. */
    private fun ringOf(request: ConnectionRequest?): Pair<PeerCallLine.Line.Ringing, String>? {
        val outer = request?.extras ?: return null
        val extras: Bundle = outer.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
            ?.takeIf { it.containsKey(IncomingCallTelecom.EXTRA_CALL) } ?: outer
        val callId = extras.getString(IncomingCallTelecom.EXTRA_CALL) ?: return null
        val peerId = extras.getString(IncomingCallTelecom.EXTRA_PEER) ?: return null
        val since = extras.getLong(IncomingCallTelecom.EXTRA_SINCE)
        return PeerCallLine.Line.Ringing(callId, peerId, since) to extras.getString(IncomingCallTelecom.EXTRA_NAME).orEmpty()
    }

    private class AppCallDoor(private val context: Context) : CallDoor {
        override fun showRinging(line: PeerCallLine.Line.Ringing, name: String) =
            PeerCallNotifications.postRinging(context, line, name)

        override fun answer() = PeerCallNotifications.openCall(context)

        override fun decline() = PeerCallActionReceiver.decline(context)

        override fun hangUp() {
            val container = runCatching { PhantomApp.container() }.getOrNull() ?: return
            container.scope.launch {
                runCatching {
                    if (container.callHub.line.value is PeerCallLine.Line.Ringing) container.callHub.declineNow()
                    else container.callHub.leaveNow()
                }
            }
        }
    }
}
