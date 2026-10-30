package local.phantom.companion.core.truth

enum class Lang { UK, EN }

/** Людський текст вироку. Українська — основна; англійська — дзеркало. */
object Speech {

    fun verdict(v: Verdict, nowMs: Long, lang: Lang = Lang.UK): String {
        val reading = v.reading
        val head = when (v.state) {
            TruthState.LIKELY -> when (lang) {
                Lang.UK -> "Ймовірно ${reading!!.uk}"
                Lang.EN -> "Probably ${reading!!.en}"
            }
            TruthState.UNKNOWN -> if (lang == Lang.UK) "Невідомо" else "Unknown"
            TruthState.STALE -> if (lang == Lang.UK) "Застаріло" else "Out of date"
        }
        val age = nowMs - (v.newestAtMs ?: nowMs)
        val tail = when {
            v.newestAtMs == null -> if (lang == Lang.UK) "даних ще немає" else "no reports yet"
            v.state == TruthState.STALE -> when (lang) {
                Lang.UK -> "востаннє «${reading!!.uk}» ${ago(age, lang)}"
                Lang.EN -> "last “${reading!!.en}” ${ago(age, lang)}"
            }
            v.state == TruthState.UNKNOWN && v.contested -> if (lang == Lang.UK) "думки розходяться" else "reports disagree"
            v.state == TruthState.UNKNOWN && v.supporters <= 1 ->
                if (lang == Lang.UK) "одне непідтверджене повідомлення" else "one unconfirmed report"
            v.state == TruthState.UNKNOWN -> if (lang == Lang.UK) "підтверджень поки замало" else "not enough confirmations yet"
            else -> confirmed(v.supporters, nowMs - (v.lastSupportAtMs ?: nowMs - age), lang)
        }
        val dissent = if (v.state == TruthState.LIKELY && v.dissent) {
            if (lang == Lang.UK) " · останнє повідомлення інше" else " · the latest report differs"
        } else ""
        return "$head · $tail$dissent"
    }

    fun confirmed(n: Int, ageMs: Long, lang: Lang): String = when (lang) {
        Lang.UK -> {
            val verb = if (plural(n) == 0) "підтвердила" else "підтвердили"
            val noun = when (plural(n)) { 0 -> "людина"; 1 -> "людини"; else -> "людей" }
            "$verb $n $noun, останнє ${ago(ageMs, lang)}"
        }
        Lang.EN -> "confirmed by $n ${if (n == 1) "person" else "people"}, last ${ago(ageMs, lang)}"
    }

    fun impact(band: Int, lang: Lang = Lang.UK): String = when (lang) {
        Lang.UK -> when {
            band <= 0 -> "Поки ніхто не переглянув"
            band == 1 -> "Твоє підтвердження вже допомогло кільком людям"
            else -> "Твоє підтвердження побачили щонайменше $band ${if (plural(band) == 1) "людини" else if (plural(band) == 0) "людина" else "людей"}"
        }
        Lang.EN -> when {
            band <= 0 -> "Nobody has looked yet"
            band == 1 -> "Your confirmation has already helped a few people"
            else -> "At least $band people have seen your confirmation"
        }
    }

    /** 0 — одна (1, 21), 1 — кілька (2–4, 22–24), 2 — багато (5–20, 11–14). */
    fun plural(n: Int): Int {
        val a = n % 100
        val b = n % 10
        return when {
            a in 11..14 -> 2
            b == 1 -> 0
            b in 2..4 -> 1
            else -> 2
        }
    }

    fun ago(ms: Long, lang: Lang): String {
        val min = ms / 60_000
        return when {
            min < 1 -> if (lang == Lang.UK) "щойно" else "just now"
            min < 60 -> if (lang == Lang.UK) "$min хв тому" else "$min min ago"
            min < 48 * 60 -> if (lang == Lang.UK) "${min / 60} год тому" else "${min / 60} h ago"
            else -> if (lang == Lang.UK) "${min / (24 * 60)} дн тому" else "${min / (24 * 60)} d ago"
        }
    }
}
