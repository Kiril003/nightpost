package local.phantom.companion.feature.godmode.engine

/**
 * Phase 3 W4-4 — guard rail in front of the AccessibilityService
 * action dispatcher. Failure mode is suspend-the-sequence-and-tell-
 * the-operator: never silently drop, never silently downgrade.
 *
 *  • Vault locked → ALL actions denied. The vault clamp is the
 *    operator's «do not act on my behalf» signal; respect it
 *    blanket-style.
 *  • Any touch in a [ProtectedWindows] window, or in a window nobody
 *    can name → denied.
 *  • Click / Type into a sensitive app (banks, password managers,
 *    OTP, crypto wallets) → action denied. Sensitive-app lookup is
 *    callable-injected so the authorizer doesn't pull a Compose /
 *    Android dependency just for the package list — the lookup
 *    lives in `:feature-ambient/SensitiveAppDenylist` and is wired
 *    via the AppContainer.
 *
 * Read / Scroll / Back-Home-Recents / Screenshot remain allowed
 * even on sensitive apps — those are inert UX gestures, not
 * credential-hostile actions. (The deeper "no taps on sensitive
 * UI at all" stance can be re-tuned per-operator in a follow-up.)
 */
class AppActionAuthorizer(
    /** Returns `true` when the named package is on the sensitive denylist. */
    private val isSensitiveApp: (String) -> Boolean,
    /** Returns `true` when the operator's vault clamp is active. */
    private val isVaultLocked: () -> Boolean = { false },
    private val ownPackage: String = ProtectedWindows.OWN_APPLICATION_ID,
) {

    fun authorize(action: AppAction, currentPackage: String): AuthorizationResult {
        if (isVaultLocked()) {
            return AuthorizationResult.Denied(
                reasonKey = REASON_VAULT_LOCKED,
                displayText = "сховище замкнуто · спочатку розблокуй",
            )
        }
        val ownLocked = ProtectedWindows.isOwn(currentPackage, ownPackage) && !ProtectedWindows.leaves(action)
        if (ownLocked || (ProtectedWindows.touches(action) && ProtectedWindows.covers(currentPackage, ownPackage))) {
            return AuthorizationResult.Denied(
                reasonKey = REASON_PROTECTED_WINDOW,
                displayText = "дія заблокована · захищене вікно",
            )
        }
        val touchesSensitive = action is AppAction.Click ||
            action is AppAction.Type ||
            action is AppAction.Swipe ||
            action is AppAction.Tap ||
            action is AppAction.LongPress
        if (touchesSensitive && isSensitiveApp(currentPackage)) {
            return AuthorizationResult.Denied(
                reasonKey = REASON_SENSITIVE_APP,
                displayText = "дія заблокована · банкінг",
            )
        }
        return AuthorizationResult.Allowed
    }

    companion object {
        const val REASON_VAULT_LOCKED: String = "vault-locked"
        const val REASON_SENSITIVE_APP: String = "sensitive-app"
        const val REASON_PROTECTED_WINDOW: String = "protected-window"
    }
}

/** Вікна, де помічник не торкається нічого: наш застосунок, системний UI, дозволи, інсталятор, Налаштування. */
object ProtectedWindows {
    const val OWN_APPLICATION_ID: String = "local.phantom.companion"
    const val STORE_APPLICATION_ID: String = "dev.mobilabs.app"

    private val SYSTEM: List<String> = listOf(
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.settings",
    )

    fun touches(action: AppAction): Boolean = when (action) {
        is AppAction.Click, is AppAction.Tap, is AppAction.Swipe,
        is AppAction.LongPress, is AppAction.Type, is AppAction.Scroll -> true
        else -> false
    }

    /** У нашому вікні дозволено лише піти з нього: додому, в інший застосунок, на мапу в процесі. */
    fun leaves(action: AppAction): Boolean =
        action is AppAction.Home || action is AppAction.OpenApp || MapActionRouter.isMapAction(action)

    fun isOwn(windowPackage: String?, ownPackage: String): Boolean {
        val pkg = windowPackage?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return false
        val own = ownPackage.trim().lowercase()
        return (own.isNotEmpty() && family(pkg, own)) || family(pkg, OWN_APPLICATION_ID) || family(pkg, STORE_APPLICATION_ID)
    }

    fun covers(windowPackage: String?, ownPackage: String): Boolean {
        val pkg = windowPackage?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return true
        return isOwn(pkg, ownPackage) || SYSTEM.any { family(pkg, it) }
    }

    private fun family(pkg: String, root: String): Boolean = pkg == root || pkg.startsWith("$root.")
}

/** Result of an authorize() call — outcome carries display text for the operator. */
sealed class AuthorizationResult {
    object Allowed : AuthorizationResult()

    /**
     * @param reasonKey  stable, machine-grep-able tag (`vault-locked`,
     *                    `sensitive-app`, etc.) — useful for tests and
     *                    LifeLog audit entries
     * @param displayText operator-visible Ukrainian Notice copy
     */
    data class Denied(val reasonKey: String, val displayText: String) : AuthorizationResult()
}
