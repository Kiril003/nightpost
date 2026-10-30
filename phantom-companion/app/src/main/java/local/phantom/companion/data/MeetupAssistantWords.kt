package local.phantom.companion.data

import android.content.Context
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.ReplyLanguage
import local.phantom.companion.core.tool.impl.MeetupWords

/**
 * Слова `meetup.create` і `bill.split` мовою месенджера.
 *
 * Мова — та сама, що в усіх відповідей помічника: [ReplyLanguage] (Профіль ›
 * Мова, інакше українська), а не мова системи. Інструмент у `:core-symbiote`
 * не має ні ресурсів, ні мови — він питає ці слова на кожну відповідь, тож
 * зміна мови застосунку чується одразу.
 *
 * [str] — це `getString`; у тесті на його місці читач справжніх файлів
 * ресурсів, тож слова перевіряються такими, які їх побачить людина.
 */
internal class MeetupAssistantWords(private val str: (resId: Int, args: Array<out Any>) -> String) : MeetupWords {

    private fun s(resId: Int, vararg args: Any) = str(resId, args)

    override fun drafted(people: List<String>, place: String?, at: String?): String {
        val parts = listOfNotNull(people.takeIf { it.isNotEmpty() }?.joinToString(", "), place, at)
        if (parts.isEmpty()) return emptyDraft()
        val blank = buildList {
            if (people.isEmpty()) add(s(R.string.meetup_ask_field_people))
            if (place == null) add(s(R.string.meetup_ask_field_place))
            if (at == null) add(s(R.string.meetup_ask_field_time))
        }
        val done = s(R.string.meetup_ask_drafted, parts.joinToString(" · "))
        return if (blank.isEmpty()) done else done + " " + s(R.string.meetup_ask_still_empty, blank.joinToString(", "))
    }

    override fun emptyDraft() = s(R.string.meetup_ask_empty)

    // Те саме речення, що й для переказів: «Не знайшов «…» серед людей у ваших розмовах.»
    override fun notFound(name: String) = s(R.string.money_nobody, name)

    override fun which(name: String, candidates: List<String>) = s(R.string.money_which_one, candidates.joinToString(", "))

    override fun splitOpened(mode: String) =
        s(if (mode == "equal") R.string.meetup_ask_split_equal else R.string.meetup_ask_split_claimed)

    override fun rosterUnreadable() = s(R.string.meetup_ask_roster_unreadable)

    override fun draftFailed() = s(R.string.meetup_ask_draft_failed)

    override fun badMode(mode: String) = s(R.string.meetup_ask_bad_mode, mode)

    companion object {
        /** Слова з ресурсів мовою відповіді помічника — на кожну відповідь заново. */
        fun replyLanguage(app: () -> Context): MeetupAssistantWords =
            MeetupAssistantWords { resId, args -> ReplyLanguage.context(app()).getString(resId, *args) }
    }
}
