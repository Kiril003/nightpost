package local.phantom.companion.core.memory

/**
 * Опора відповіді — дзеркало `ground.py` ПК: питання про факт власника без
 * доречного запису → «не знаю» без моделі. Сторож обережний: «не знаю» лише
 * коли питання точно про власника; порада й дія — слово моделі.
 */
object Grounding {

    const val DONT_KNOW = "Не знаю — ти мені цього не казав."
    const val GROUND_MIN = 0.30

    /** Регекси пишуться ʼ і згортаються тим самим [Text.foldApostrophes], що й запит у [Text.normalize]. */
    private fun rx(p: String) = Regex(Text.foldApostrophes(p))

    private val POSSESSIVE = rx("(?:^|\\s)(?:мій|моя|моє|мої|мого|моєї|мою|моїх|моїм|моєму|моїй|у\\s+мене|в\\s+мене|мене\\s+звати)(?=\\s|$)")
    private val I = rx("(?:^|\\s)(?:я|мене|мені)(?=\\s|$)")
    private val RECALL = rx("(?:^|\\s)(?:памʼятаєш|казав|казала|розповідав|розповідала|нагадай)(?=\\s|$)")
    private val INTERROGATIVE = rx(
        "^(?:а\\s+|і\\s+|то\\s+)?(?:як|де|коли|хто|що|чи|скільки|який|яка|яке|які|якого|якої|якому|якій|яку|яким|" +
            "куди|звідки|на\\s+що|на\\s+якому|на\\s+якій|у\\s+якому|в\\s+якому|у\\s+якій|в\\s+якій|з\\s+ким|про\\s+що|за\\s+яку|ти\\s+памʼятаєш)(?=\\s|$)",
    )
    private val NOT_RECALL = rx(
        "(?:^|\\s)(?:(?:що|як|де|куди|чи|коли)\\s+мені|порадь|підкажи|допоможи|напиши|розкажи|поясни|знайди|зроби|" +
            "покажи|переклади|порахуй|чи\\s+варто|чи\\s+можна|котра\\s+година|яка\\s+погода)(?=\\s|$)",
    )

    fun ownerQuestion(query: String): Boolean {
        val q = Text.normalize(query)
        if (q.isEmpty() || NOT_RECALL.containsMatchIn(q)) return false
        val asks = INTERROGATIVE.containsMatchIn(q) || query.trim().endsWith("?")
        if (!asks) return false
        return POSSESSIVE.containsMatchIn(q) || RECALL.containsMatchIn(q) ||
            (I.containsMatchIn(q) && Text.hintedPredicates(query).isNotEmpty())
    }

    data class Result(val askedAboutOwner: Boolean, val bestScore: Double, val items: List<MemoryItem>) {
        val ungrounded: Boolean get() = askedAboutOwner && items.isEmpty()
    }

    fun ground(ledger: Ledger, query: String, live: List<MemoryItem>, topK: Int = 5): Result {
        val about = ownerQuestion(query)
        val ranked = if (live.isEmpty()) emptyList() else ledger.rank(query, live)
        val best = ranked.firstOrNull()?.first ?: 0.0
        val asked = Text.terms(query).toSet()
        val items = ranked.filter { (s, it) ->
            s >= GROUND_MIN || Text.terms("${it.displayText()} ${it.value}").any { t -> t in asked }
        }.map { it.second }.take(topK)
        return Result(about, best, items)
    }
}
