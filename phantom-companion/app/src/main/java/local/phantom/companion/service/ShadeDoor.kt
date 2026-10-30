package local.phantom.companion.service

import android.content.Context

/** Двері шторки для листа: свіжий лист — одна нагода; розмову побачили — плашки немає. */
object ShadeDoor {

    fun arrived(context: Context, profile: String, chat: String, messageId: String, post: () -> Unit) {
        if (ShadeOnce.of(context).first(profile, chat, messageId)) post()
    }

    fun seen(context: Context, chat: String) = PeerNotifications.clear(context, chat)
}
