package local.phantom.companion.feature.onboarding.domain

/**
 * Перший запуск — один крок: імʼя, і людина вже в чатах. PIN, пара з ПК і
 * доступ до екрана питаються пізніше, там, де вони потрібні, а не на порозі.
 */
sealed interface OnboardingState {

    data class NameStep(val draftName: String = "") : OnboardingState

    data object CreatingProfile : OnboardingState

    data class Complete(
        val profileId: String,
        val displayName: String,
    ) : OnboardingState

    data class Error(val message: String) : OnboardingState
}
