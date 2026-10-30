package local.phantom.companion.feature.onboarding.ui

import local.phantom.companion.feature.onboarding.domain.OnboardingState

internal fun narrationLineFor(state: OnboardingState): String? = when (state) {
    is OnboardingState.NameStep -> "вітаю · як до тебе звертатись?"
    is OnboardingState.CreatingProfile -> null
    is OnboardingState.Complete -> "готовий · тут і зараз"
    is OnboardingState.Error -> null
}
