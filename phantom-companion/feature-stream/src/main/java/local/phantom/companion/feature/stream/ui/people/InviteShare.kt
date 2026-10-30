package local.phantom.companion.feature.stream.ui.people

import android.content.Context
import android.content.Intent
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerRoster
import local.phantom.companion.feature.stream.R

/**
 * Запрошення-артефакт: єдине, що вилітає з PHANTOM туди, де друг уже сидить.
 *
 * Летить не голий код, а лист людською мовою: хто кличе, тапне посилання —
 * і що робити, поки застосунку немає. Секрет у листі не їде: посилання несе
 * публічні ключі й адресу, сеансові ключі складаються при зустрічі апаратів,
 * а підміну ловлять шість цифр уголос — стара мережа тут курʼєр, не свідок.
 */
internal object InviteShare {

    fun message(context: Context, invite: PeerInvite.Invite): String =
        context.getString(
            R.string.people_invite_message,
            invite.name.ifBlank { PeerRoster.shortId(invite.peerId) },
            PeerInvite.link(invite),
        )

    fun send(context: Context, invite: PeerInvite.Invite) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message(context, invite))
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.people_invite_call_cta)),
        )
    }
}
