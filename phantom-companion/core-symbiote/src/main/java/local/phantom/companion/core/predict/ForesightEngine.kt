package local.phantom.companion.core.predict

import local.phantom.companion.core.bus.PhantomEvent
import local.phantom.companion.core.bus.PhantomNervousBus
import local.phantom.companion.core.context.ContextFrame
import java.util.UUID

/**
 * DEBT, measured 2026-09-06 — all three rules below are inert in a shipped
 * build, and none of them is broken. [evaluate] really does run on every
 * context frame (AppContainer wires it to `engine.latestFrame`, behind
 * `BuildConfig.ENABLE_CONTEXT`, which is true in every build type), but its
 * three inputs are never filled: [registerPrinciple] and [registerTransition]
 * have no caller anywhere in the tree, and [registerConviction] has exactly
 * one — the `/debug-conviction` slash command, now DEBUG-only. So
 * [activePrinciples] and [activeConvictions] stay empty for the whole life of
 * the process, `doubtAmplifier.evaluate` folds over nothing, `scheduler.due`
 * has nothing due, and `shimmerEngine.suggest` returns null because no
 * transition was ever observed. The engine publishes zero cards.
 *
 * What is missing is the harvest, not the rules: something must notice that
 * the operator stated a principle, decided something worth reviewing, or
 * moved from one context bucket to the next, and call the three registers.
 * Two more instances of [DoubtAmplifier] and [ShimmerEngine] sit in
 * AppContainer with no consumer at all — this class builds its own private
 * pair from the constructor defaults and never sees them.
 *
 * Do not read a green test here as a working feature: the rules are covered,
 * the reach is not.
 */
class ForesightEngine(
    private val bus: PhantomNervousBus,
    private val deviceId: String,
    private val doubtAmplifier: DoubtAmplifier = DoubtAmplifier(),
    private val convictionScheduler: ConvictionScheduler? = null,
    private val shimmerEngine: ShimmerEngine = ShimmerEngine(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val scheduler = convictionScheduler ?: ConvictionScheduler(clock)
    private val alreadyFiredReviews = mutableSetOf<Pair<String, ConvictionMilestone>>()
    private val activePrinciples = mutableListOf<Principle>()
    private val activeConvictions = mutableListOf<ConvictionScore>()

    fun registerPrinciple(principle: Principle) {
        activePrinciples.add(principle)
    }

    fun registerConviction(score: ConvictionScore) {
        activeConvictions.add(score)
    }

    fun registerTransition(bucket: ShimmerEngine.Bucket, nextAction: String) {
        shimmerEngine.observe(bucket, nextAction)
    }

    fun submitReviewResult(decisionId: String, newScore: Int, timestampMs: Long) {
        val newConviction = ConvictionScore(
            decisionId = decisionId,
            score = newScore,
            recordedAtMs = timestampMs
        )
        activeConvictions.add(newConviction)
    }

    fun getConvictionTrend(decisionId: String): List<ConvictionScore> {
        return activeConvictions.filter { it.decisionId == decisionId }.sortedBy { it.recordedAtMs }
    }

    fun evaluate(frame: ContextFrame) {
        val now = clock()

        // 1. Check principles (Doubt Amplifier)
        val activeApp = frame.what.activeAppPackage
        if (activeApp != null) {
            val pending = PendingAction(
                verb = "open_app",
                timestampMs = now,
                contextTags = setOf(activeApp) + frame.what.detectedSounds.toSet()
            )
            val doubtPrompts = doubtAmplifier.evaluate(pending, activePrinciples)
            for (p in doubtPrompts) {
                publishProactiveCard(
                    title = "Сумнів щодо дії",
                    body = "Твій принцип: «${p.quote}»",
                    kind = "doubt_warning",
                    why = "щойно відкрито $activeApp",
                )
            }
        }

        // 2. Check conviction reviews
        val dueReviews = scheduler.due(activeConvictions, alreadyFiredReviews)
        for (rev in dueReviews) {
            alreadyFiredReviews.add(rev.decisionId to rev.milestone)
            publishProactiveCard(
                title = "Перевірка переконання",
                body = "Як справи з цією метою?",
                kind = "conviction_review",
                why = "плановий перегляд — ${rev.milestone.daysAfter} дн. з рішення",
                decisionId = rev.decisionId,
                milestoneDays = rev.milestone.daysAfter
            )
        }

        // 3. Shimmer Engine suggestions
        val bucket = ShimmerEngine.Bucket(
            state = frame.state,
            hour = getHourFromTimestamp(frame.timestampMs),
            location = frame.where.placeLabel,
            lastAction = frame.what.activeAppPackage
        )
        shimmerEngine.suggest(bucket)?.let { sugg ->
            publishProactiveCard(
                title = "Рекомендація дії",
                body = "Відкрити ${sugg.action}?",
                kind = "shimmer_suggestion",
                actionPayload = sugg.action,
                why = "у цей час і в цьому місці ти робиш так регулярно",
                confidence = sugg.probability.toFloat(),
            )
        }
    }

    private fun getHourFromTimestamp(ts: Long): Int {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    private fun publishProactiveCard(
        title: String,
        body: String,
        kind: String,
        actionPayload: String? = null,
        decisionId: String? = null,
        milestoneDays: Int? = null,
        /** What tripped this, in the operator's words. */
        why: String? = null,
        /** 0..1 where the engine can measure it; null where it cannot. */
        confidence: Float? = null,
    ) {
        // Built, not concatenated. A principle quoted back to the operator
        // carries quotation marks by definition, and glued into a JSON
        // template they closed the string early: the card became unparseable
        // and was swallowed by the reader's runCatching — a doubt warning
        // that never once reached the screen.
        val json = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString()))
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            put("body", kotlinx.serialization.json.JsonPrimitive(body))
            put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
            put("actionPayload", kotlinx.serialization.json.JsonPrimitive(actionPayload))
            put("decisionId", kotlinx.serialization.json.JsonPrimitive(decisionId))
            put("milestoneDays", kotlinx.serialization.json.JsonPrimitive(milestoneDays))
            put("why", kotlinx.serialization.json.JsonPrimitive(why))
            put("confidence", kotlinx.serialization.json.JsonPrimitive(confidence))
        }.toString()

        bus.publish(
            PhantomEvent(
                id = UUID.randomUUID().toString(),
                timestampMs = clock(),
                sourceOrgan = "ForesightEngine",
                type = TYPE_PROACTIVE_CARD,
                payloadJson = json,
                deviceOrigin = deviceId,
                privacy = "trusted"
            )
        )
    }

    companion object {
        const val TYPE_PROACTIVE_CARD = "proactive_card"
    }
}
