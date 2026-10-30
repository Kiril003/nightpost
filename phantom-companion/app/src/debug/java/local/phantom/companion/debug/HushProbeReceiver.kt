package local.phantom.companion.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.service.PeerNotifications

/**
 * Debug only: a fixture chat for the per-chat mute check, no real person involved.
 *   adb shell am broadcast -n local.phantom.companion.debug/local.phantom.companion.debug.HushProbeReceiver --es cmd mute
 *   … --es cmd notify | unmute
 */
class HushProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PhantomApp ?: return
        val store = app.container.chatHush
        when (intent.getStringExtra("cmd")) {
            "mute" -> store.set(FIXTURE, intent.getLongExtra("until", 0L).takeIf { it > 0 }, TITLE)
            "unmute" -> store.clear(FIXTURE)
            "notify" -> PeerNotifications.notifyMessage(
                app,
                PeerAnnounce.Notice(key = FIXTURE, fromId = FIXTURE, groupId = FIXTURE, groupName = TITLE, text = "fixture"),
                TITLE,
                "fixture ${System.currentTimeMillis() % 100_000}",
                System.currentTimeMillis(),
            )
        }
        Log.i(TAG, "cmd=${intent.getStringExtra("cmd")} muted=${store.hushesNow(FIXTURE)}")
    }

    private companion object {
        const val FIXTURE = "fixture-hush"
        const val TITLE = "Fixture"
        const val TAG = "PhantomHush"
    }
}
