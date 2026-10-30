package local.phantom.companion.feature.godmode.engine

/**
 * Phase 3 W4-5 — pure-Kotlin classifier that decides what to do with
 * an incoming `NotificationListenerService` payload:
 *
 *  • [Classification.IMPORTANT] → push as Stream Notice immediately
 *  • [Classification.ROUTINE]   → queue for the daily 9am digest
 *  • [Classification.SPAM]      → silent, log only, never surface
 *
 * The classifier is tier-1: rule-based regex denylist + per-package
 * routing rules. The (planned-for-Phase-4) AI-assisted classifier
 * extends this with semantic intent — but the rule lane stays the
 * authority for sensitive-content stripping (banks / OTP / 2FA
 * codes never reach the cloud, even via the digest summary).
 *
 * Strip-then-classify pipeline:
 *   1. [redactSensitive] runs first — banking codes / OTP / 2FA
 *      are masked with `***` before any other lane sees the text.
 *   2. [perPackageRouting] checks the operator's per-app rule set
 *      (Settings → Notifications surface) for an explicit override.
 *   3. [defaultClassify] applies sensible defaults when the operator
 *      hasn't set a rule yet — messengers + email default to
 *      Important, OS / launcher / system housekeeping defaults to
 *      Spam, everything else defaults to Routine.
 *
 * Designed to live entirely on the JVM (Robolectric not required) so
 * regression-pinning regex changes don't need an instrumented test.
 */
object NotificationClassifier {

    /** Outcome bucket for a single notification. */
    enum class Classification { IMPORTANT, ROUTINE, SPAM }

    /**
     * @param packageName  source app
     * @param channelId    NotificationChannel id; null on apps that
     *                     pre-date Android 8 channels
     * @param title        notification title (ideally already trimmed)
     * @param redactedTitle `title` under the same rule as the body. У
     *                     банків код живе саме в заголовку, тож усе, що
     *                     ЗБЕРІГАЄТЬСЯ, бере звідси — і архів, і черга
     *                     дайджесту. Два записувачі, одне правило.
     * @param text         notification text body
     * @param redactedText `text` with sensitive content masked. Same
     *                     as `text` when nothing matched a sensitive
     *                     pattern.
     */
    data class ClassificationResult(
        val packageName: String,
        val channelId: String?,
        val title: String,
        val redactedTitle: String,
        val text: String,
        val redactedText: String,
        val classification: Classification,
    )

    /**
     * One-shot per-app rule the operator can set in
     * Settings → Notifications. Missing entries fall through to
     * [defaultClassify].
     */
    data class PackageRule(
        val packageName: String,
        val classification: Classification,
    )

    /**
     * Top-level entry. Pure function: same inputs → same outputs.
     */
    fun classify(
        packageName: String,
        channelId: String?,
        title: String,
        text: String,
        rules: Collection<PackageRule> = emptyList(),
    ): ClassificationResult {
        val redacted = redactSensitive(text)
        val redactionFired = redacted != text
        val classification = perPackageRouting(packageName, rules)
            ?: defaultClassify(packageName, redacted, redactionFired)
        return ClassificationResult(
            packageName = packageName,
            channelId = channelId,
            title = title,
            redactedTitle = redactSensitive(title),
            text = text,
            redactedText = redacted,
            classification = classification,
        )
    }

    /**
     * Mask numeric / alpha-numeric secrets that look like OTP, 2FA,
     * banking authorisation codes, or PINs. Patterns intentionally
     * over-redact — better to lose a fragment of a routine notification
     * than to leak a one-time code into the AI digest. The redacted
     * form is what gets persisted into [NotificationDigestEntity], so
     * even the local DB never sees the secret.
     *
     * Patterns:
     *  • 3–8 digit standalone groups → mask (canonical OTP shape)
     *  • Long alphanum tokens following keywords like «code», «otp»,
     *    «password», «код», «пароль»
     *  • SMS-style "Your code is 1234"
     */
    fun redactSensitive(text: String): String {
        var out = text

        // Pattern 1: phrase-anchored "code" / "otp" / "пароль" / "код"
        // / "verification" / "auth" followed by 3–8 digits. The
        // anchor word stays; only the secret is masked.
        out = SECRET_PHRASE_PATTERN.replace(out) { match ->
            val keyword = match.groupValues[1]
            "$keyword ***"
        }

        // Pattern 2: bare 3–8 digit groups when surrounded by
        // word-boundary text (catches "1234 is your code" too).
        out = BARE_DIGIT_PATTERN.replace(out) { match ->
            // Don't mask plain prices like "$25.99" (handled by
            // including a $/€/₴ guard in the lookahead) — we just
            // want loose digits that smell like a code.
            "***"
        }

        return out
    }

    private fun perPackageRouting(
        packageName: String,
        rules: Collection<PackageRule>,
    ): Classification? = rules.firstOrNull { it.packageName == packageName }?.classification

    private fun defaultClassify(
        packageName: String,
        redactedText: String,
        redactionFired: Boolean,
    ): Classification {
        if (SYSTEM_NOISE_PACKAGES.any { packageName.startsWith(it) }) {
            return Classification.SPAM
        }
        // Mail clients land here before the redaction-as-spam rule so
        // a personal email containing a forwarded code stays Important.
        if (MAIL_PACKAGES.contains(packageName) ||
            packageName.contains("mail", ignoreCase = true)
        ) {
            return Classification.IMPORTANT
        }
        if (MESSENGER_PACKAGES.any { packageName == it }) {
            return Classification.IMPORTANT
        }
        // Banking SMS / OTP confirmations land in arbitrary packages
        // (carrier SMS, vendor-specific banking apps not in any of our
        // curated lists). If the redactor fired, treat that as a hard
        // SPAM signal — better to lose a fragment than to forward a
        // one-time code into Stream / the AI digest.
        if (redactionFired) {
            return Classification.SPAM
        }
        // Empty-after-redact tail (no keyword matched, just bare digits)
        // also classifies SPAM — a 4-digit-only notification is almost
        // always an authorisation echo.
        if (redactedText.replace("*", "").trim().length < MIN_BODY_AFTER_REDACT) {
            return Classification.SPAM
        }
        return Classification.ROUTINE
    }

    private val SECRET_PHRASE_PATTERN: Regex = Regex(
        pattern = "\\b(code|otp|pin|password|пароль|код|verification|auth|2fa)" +
            "(?:\\s*(?:is|:|—))?\\s*[0-9A-Za-z\\-]{3,12}",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    private val BARE_DIGIT_PATTERN: Regex = Regex(
        pattern = "(?<![\\$€₴.0-9])\\b\\d{3,8}\\b(?![\\.,]\\d)",
    )

    /** Matched as `startsWith` so subdomains of system services qualify. */
    private val SYSTEM_NOISE_PACKAGES: Set<String> = setOf(
        "android.system",
        "com.android.systemui",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.providers",
    )

    private val MESSENGER_PACKAGES: Set<String> = setOf(
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",   // Signal
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.viber.voip",
        "com.facebook.orca",
    )

    /**
     * Mail clients we know by exact-package match. Names that don't
     * include "mail" in the package id (Gmail, Outlook, Yandex Mail,
     * K-9 Mail) need this list because the substring fallback misses
     * them. Per-app override via [PackageRule] still wins.
     */
    private val MAIL_PACKAGES: Set<String> = setOf(
        "com.google.android.gm",            // Gmail
        "com.microsoft.office.outlook",     // Outlook
        "ru.yandex.mail",                   // Yandex Mail
        "com.fsck.k9",                      // K-9 Mail
        "ch.protonmail.android",            // ProtonMail
    )

    private const val MIN_BODY_AFTER_REDACT: Int = 4
}
