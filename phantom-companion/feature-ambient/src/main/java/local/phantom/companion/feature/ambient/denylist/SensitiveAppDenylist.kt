package local.phantom.companion.feature.ambient.denylist

/**
 * Phase 3-E4 — packages where the Whisper Line overlay must hide.
 *
 * The drift ribbon is a peripheral cue, not a privacy hazard — but
 * showing «олег чекає 23 хв» on top of a banking screen is at minimum
 * uncomfortable and at worst a screenshot artifact in evidence (people
 * screenshot bank confirmations all the time). The denylist is a
 * practical floor: a curated set of known-sensitive package names
 * (banks, password managers, OTP apps), plus prefix-based catch-alls
 * for common naming patterns.
 *
 * Curated rather than enumerated because the long tail of regional
 * banks is unbounded; the prefix matchers cover «com.lastpass.*»,
 * «com.privatbank.*», «com.monobank.*», etc. without forcing a
 * release per new app. Operator can extend at runtime via Settings →
 * Whisper → Sensitive apps (Phase 3-E5).
 *
 * Verbatim from the prompt's «monobank|privat|com.lastpass|com.google
 * .android.apps.authenticator2|...» list — kept as a Set so the
 * classifier is O(1) per exact match and falls through to prefix
 * scan only on miss.
 */
object SensitiveAppDenylist {

    /** Exact package names known to host sensitive content. */
    val EXACT: Set<String> = setOf(
        // Ukraine
        "ua.privatbank.ap24",                       // Privat24
        "ua.privatbank.privat24",                   // Privat24 alt
        "com.ftband.mono",                          // Monobank
        "ua.com.cuprum.cuprum",                     // PUMB
        "ua.alfabank.mobile.android",               // Sense (Alfa-Bank UA)
        "ua.oschadbank.online",                     // Oschadbank
        // Global banks operators commonly install
        "com.revolut.revolut",                      // Revolut
        "com.wise",                                 // Wise (TransferWise)
        "com.bbva.bbvacontigo",                     // BBVA
        // Password managers
        "com.lastpass.lpandroid",                   // LastPass
        "com.bitwarden",                            // Bitwarden
        "com.bitwarden.x8",                         // Bitwarden alt
        "com.dashlane.dashlanepasswordmanager",     // Dashlane
        "com.agilebits.onepassword",                // 1Password
        "com.keepersecurity.gem",                   // Keeper
        "org.mozilla.firefox.lockwise",             // Firefox Lockwise
        "io.enpass.app",                            // Enpass
        // OTP / 2FA apps
        "com.google.android.apps.authenticator2",   // Google Authenticator
        "com.azure.authenticator",                  // Microsoft Authenticator
        "com.duosecurity.duomobile",                // Duo
        "com.authy.authy",                          // Authy
        "org.shadowice.flocke.andotp",              // andOTP
        "com.beemdevelopment.aegis",                // Aegis Authenticator
        // Crypto wallets (sensitive seed phrases on screen)
        "com.coinbase.android",                     // Coinbase
        "io.metamask",                              // MetaMask
        "com.wallet.crypto.trustapp",               // Trust Wallet
        // Government / identity apps that show passport / ID data
        "ua.gov.diia.app",                          // Дія
    )

    /**
     * Substring patterns that catch the long tail of regional banks
     * + naming-convention apps. Lowercased and matched against the
     * lowercase candidate package. Order doesn't matter — we OR
     * across the whole set on first sensitive-classification call.
     */
    val SUBSTRING_HINTS: Set<String> = setOf(
        "monobank", "privatbank", "alfabank", "oschadbank",
        // "onepassword" — actual AgileBits package uses spelled-out
        // form (com.agilebits.onepassword); "1password" doesn't match.
        "lastpass", "bitwarden", "onepassword", "dashlane", "keeper",
        "authenticator", "authy", "andotp", "aegis",
        "wallet", "metamask", "coinbase", "trustwallet",
        "bank.android", "bank.app", "banking",
    )

    /**
     * True when [packageName] is on the denylist. Null/blank inputs
     * are treated as «not sensitive» — the overlay stays up if we
     * don't know what's on top (failing closed in this direction
     * would mean a false positive blanks the whisper line on every
     * launcher transition, which the operator would notice as a
     * regression).
     *
     * Match strategy:
     *   1. Exact membership (O(1) HashSet lookup)
     *   2. Substring hint scan against the lowercased candidate
     *
     * The substring scan is `contains`, not `startsWith`, because
     * common patterns like «com.foo.bank.app» live mid-name. False
     * positives in the substring lane are tolerated — the only
     * cost is a missed whisper while operator is in that app.
     */
    fun isSensitive(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (EXACT.contains(packageName)) return true
        val lower = packageName.lowercase()
        return SUBSTRING_HINTS.any { hint -> lower.contains(hint) }
    }
}
