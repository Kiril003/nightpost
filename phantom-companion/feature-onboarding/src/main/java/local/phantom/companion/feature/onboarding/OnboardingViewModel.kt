package local.phantom.companion.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.profile.FamiliarRarity
import local.phantom.companion.core.data.profile.Profile
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.data.profile.Role
import local.phantom.companion.core.net.crypto.PinHasher
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.feature.onboarding.domain.OnboardingEvent
import local.phantom.companion.feature.onboarding.domain.OnboardingState

class OnboardingViewModel(
    private val profileStore: ProfileStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pinHashParams: PinHasher.Params = PinHasher.Params.Default,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.NameStep())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.NameSubmitted -> handleNameSubmitted(event.displayName)
            OnboardingEvent.Restart -> _state.value = OnboardingState.NameStep()
        }
    }

    private fun handleNameSubmitted(rawName: String) {
        if (_state.value !is OnboardingState.NameStep) return
        val name = rawName.trim()
        if (PeerName.length(name) > PeerName.CAP_QR_NAME) {
            _state.value = OnboardingState.Error(
                "Імʼя має бути ≤ ${PeerName.CAP_QR_NAME} символів"
            )
            return
        }
        _state.value = OnboardingState.CreatingProfile
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(ioDispatcher) {
                    val profile = Profile(
                        id = idGenerator(),
                        displayName = name,
                        role = Role.OPERATOR,
                        avatarUri = null,
                        createdAtEpochMs = now(),
                        // Profile не допускає порожнього хешу, а PIN людина ще не обирала.
                        // Хеш випадкового секрету, який ніде не зберігається: жоден PIN
                        // його не відмикає, доки людина не задасть свій у безпеці.
                        pinHashArgon2 = PinHasher.hash(unguessable(), pinHashParams),
                        biometricEnrolled = false,
                        nfcUid = null,
                        voicePrintHashHex = null,
                        familiarRarity = FamiliarRarity.Default,
                        behavioralModelEncryptedBase64 = "",
                        pairedHosts = emptyList(),
                        trustScore = 1f,
                    )
                    profileStore.create(profile)
                    profileStore.setActive(profile.id)
                    profile
                }
            }
            _state.value = outcome.fold(
                onSuccess = { OnboardingState.Complete(profileId = it.id, displayName = it.displayName) },
                onFailure = { OnboardingState.Error(it.message ?: "Не вдалось створити профіль") },
            )
        }
    }

    private fun unguessable(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
