package local.phantom.companion.core.data.errors

import java.util.UUID

/**
 * QH4 — operator-facing error envelope.
 *
 * Why a centralised type instead of throwing or logging at each call
 * site: subsystem errors used to be swallowed via
 * `runCatching {...}.getOrElse { Log.e(...); null }`, leaving the
 * operator with `null` and silence (he typed, AI replied, no voice
 * came out — was TTS init broken? was the model missing? was the
 * router dead? logcat-only). [PhantomError] gives every failure a
 * canonical operator-facing message + a severity that decides whether
 * the failure surfaces immediately as a Stream Notice or sits quietly
 * in Settings → Діагностика until the operator looks for it.
 *
 * The [cause] Throwable is preserved for the Diagnostics screen's
 * stack-trace view but does NOT bleed into the [message] — operators
 * read message, devs read cause.
 */
data class PhantomError(
    val id: String,
    val ts: Long,
    /** Subsystem name — e.g. "VoiceManager", "AIRouter", "PhantomLink". */
    val source: String,
    val severity: Severity,
    /** Operator-facing copy. Ukrainian, ти-form, lowercase. */
    val message: String,
    /** Raw cause — Logcat-only audience; never shown verbatim to operator. */
    val cause: Throwable? = null,
) {
    enum class Severity {
        /** Heads-up; not actionable on its own. */
        Info,

        /** Something degraded but the feature still works (e.g. fell back to system TTS). */
        Warn,

        /** Feature broke but the app is still usable. */
        Error,

        /** App can't continue meaningful operation without operator action. */
        Critical,
    }

    companion object {
        /**
         * Convenience constructor that fills in a fresh UUID + the
         * current wall-clock timestamp. Most callers don't need to
         * override either; tests using a fake clock can pass `id`/`ts`
         * directly through the data class constructor.
         */
        fun create(
            source: String,
            severity: Severity,
            message: String,
            cause: Throwable? = null,
            nowMs: Long = System.currentTimeMillis(),
        ): PhantomError = PhantomError(
            id = UUID.randomUUID().toString(),
            ts = nowMs,
            source = source,
            severity = severity,
            message = message,
            cause = cause,
        )
    }
}
