package local.phantom.companion.ui.messenger

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import local.phantom.companion.R

/** Службові рядки помічника обома мовами: відмова й поразка, а не сказане. */
internal class AssistantNotices(
    private val exact: Set<String>,
    private val shapes: List<Regex>,
) : (String) -> Boolean {

    override fun invoke(text: String): Boolean =
        WebMessengerKinds.noticeKey(text).let { key -> key in exact || shapes.any { it.matches(key) } }

    companion object {
        private val PLAIN = listOf(
            R.string.self_no_brain,
            R.string.self_ask_no_mind,
            R.string.ai_fail_no_brain,
            R.string.ai_fail_no_network,
            R.string.ai_fail_key,
            R.string.ai_fail_silent,
            R.string.ai_fail_cloud_down,
            R.string.ai_fail_daily_limit,
            R.string.ai_fail_too_often,
            R.string.ai_fail_cancelled,
            R.string.ai_fail_unknown,
            R.string.ai_fail_empty_ask,
            R.string.ai_fail_group_unsent,
            R.string.ai_group_unavailable,
            R.string.agency_no_reply,
            R.string.assistant_sees_text_only,
        )
        private val FORMATTED = listOf(
            R.string.ai_fail_pc,
            R.string.ai_fail_cloud,
            R.string.ai_fail_unknown_with_tail,
            R.string.agency_council_halted,
        )

        fun of(plain: List<String>, formats: List<String>) =
            AssistantNotices(plain.map(WebMessengerKinds::noticeKey).toSet(), formats.map(::shape))

        private fun shape(format: String): Regex = Regex(
            WebMessengerKinds.noticeKey(format).split(Regex("""%\d+\$[sd]""")).joinToString(".+") { Regex.escape(it) },
        )

        fun load(app: Context): AssistantNotices {
            val plain = ArrayList<String>()
            val formats = ArrayList<String>()
            listOf(Locale.forLanguageTag("uk"), Locale.ENGLISH, Locale.forLanguageTag("cs")).forEach { locale ->
                val ctx = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })
                PLAIN.mapTo(plain) { ctx.getString(it) }
                FORMATTED.mapTo(formats) { ctx.getString(it) }
            }
            return of(plain, formats)
        }
    }
}
