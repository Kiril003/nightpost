package local.phantom.companion.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.concurrent.ConcurrentHashMap
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerCallLine

/**
 * Вхідний дзвінок як дзвінок для системи: самокерований обліковий запис
 * Telecom. Плашку малюємо самі ([PeerCallNotifications.postRinging]), але
 * система знає, що йде розмова, і веде гарнітуру, авто й годинник.
 *
 * Дзвінок не губиться ніколи: система відмовила, мовчить або її нема —
 * застосунок дзвонить плашкою сам.
 */
object IncomingCallTelecom {
    const val ACCOUNT_ID = "nightpost.call"
    const val EXTRA_CALL = "local.phantom.companion.call.id"
    const val EXTRA_PEER = "local.phantom.companion.call.peer"
    const val EXTRA_NAME = "local.phantom.companion.call.name"
    const val EXTRA_SINCE = "local.phantom.companion.call.since"

    @Volatile
    internal var telecomOf: (Context) -> TelecomManager? = { it.getSystemService(TelecomManager::class.java) }

    @Volatile
    internal var deadlineMs = 2_500L

    @Volatile
    private var registered = false

    internal val live = ConcurrentHashMap<String, PeerCallConnection>()

    /** Віддані системі, плашку ще ніхто не показав. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, PeerCallConnectionService::class.java), ACCOUNT_ID)

    private fun register(context: Context, telecom: TelecomManager): Boolean {
        if (registered) return true
        registered = runCatching {
            telecom.registerPhoneAccount(
                PhoneAccount.builder(handle(context), context.getString(R.string.app_name))
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .build(),
            )
        }.isSuccess
        return registered
    }

    /** True — дзвінок у системи, і плашку вона попросить сама; false — дзвонити мусить викликач. */
    fun announce(context: Context, line: PeerCallLine.Line.Ringing, callerName: String): Boolean {
        val telecom = telecomOf(context) ?: return false
        if (live.containsKey(line.callId) || line.callId in asked) return true
        if (!register(context, telecom)) return false
        val extras = Bundle().apply {
            putString(EXTRA_CALL, line.callId)
            putString(EXTRA_PEER, line.peerId)
            putString(EXTRA_NAME, callerName)
            putLong(EXTRA_SINCE, line.sinceMs)
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts(PhoneAccount.SCHEME_SIP, line.peerId, null),
            )
        }
        asked += line.callId
        val accepted = runCatching { telecom.addNewIncomingCall(handle(context), extras) }.isSuccess
        if (!accepted) {
            asked -= line.callId
            return false
        }
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ ringIfUnshown(app, line, callerName) }, deadlineMs)
        return true
    }

    internal fun ringIfUnshown(context: Context, line: PeerCallLine.Line.Ringing, name: String) {
        if (asked.remove(line.callId)) PeerCallNotifications.postRinging(context, line, name)
    }

    internal fun isPending(callId: String): Boolean = callId in asked

    internal fun shown(callId: String) {
        asked -= callId
    }

    internal fun adopt(connection: PeerCallConnection) {
        live[connection.line.callId] = connection
    }

    internal fun release(callId: String) {
        live -= callId
        asked -= callId
    }

    /** Лінія змінилась: системне зʼєднання йде за нею. */
    fun follow(line: PeerCallLine.Line) {
        when (line) {
            is PeerCallLine.Line.Talking -> {
                asked -= line.callId
                live[line.callId]?.follow(line)
            }
            is PeerCallLine.Line.Over -> {
                asked -= line.callId
                live[line.callId]?.follow(line)
            }
            PeerCallLine.Line.Idle -> {
                asked.clear()
                live.values.toList().forEach { it.follow(line) }
            }
            is PeerCallLine.Line.Ringing, is PeerCallLine.Line.Dialing -> Unit
        }
    }

    internal fun reset() {
        telecomOf = { it.getSystemService(TelecomManager::class.java) }
        deadlineMs = 2_500L
        registered = false
        live.clear()
        asked.clear()
    }
}
