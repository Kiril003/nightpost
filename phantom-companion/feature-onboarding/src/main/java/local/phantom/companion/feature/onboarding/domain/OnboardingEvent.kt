package local.phantom.companion.feature.onboarding.domain

sealed interface OnboardingEvent {

    data class NameSubmitted(val displayName: String) : OnboardingEvent

    data object Restart : OnboardingEvent
}
