package local.phantom.companion.feature.stream.domain

/**
 * Presence phase for the LivePresenceHeader's animated phase line.
 *
 * Design source of truth:
 * `docs/design-refs/phantom-app2/project/live-pieces.jsx` §1
 * `LivePresenceHeader` — the `phaseLabel` map. Labels are the verbatim
 * Ukrainian copy from the prototype (Familiar speaks «ти», warm tone).
 */
enum class TurnPhase(val label: String) {
    IDLE("тут і зараз"),
    LISTENING("слухаю"),
    THINKING("думаю"),
    TYPING("пишу"),
    ACTING("дію");

    companion object {
        /**
         * Pure phase derivation. Precedence, highest first:
         *  - a held mic ([capturing]) → LISTENING (operator is speaking now);
         *  - a running tool ([toolRunning]) → ACTING;
         *  - an in-flight turn that has begun streaming tokens
         *    ([sending] + [streamingText]) → TYPING;
         *  - an in-flight turn still silent ([sending]) → THINKING;
         *  - otherwise → IDLE.
         */
        fun derive(
            capturing: Boolean,
            toolRunning: Boolean,
            sending: Boolean,
            streamingText: Boolean,
        ): TurnPhase = when {
            capturing -> LISTENING
            toolRunning -> ACTING
            sending && streamingText -> TYPING
            sending -> THINKING
            else -> IDLE
        }
    }
}
