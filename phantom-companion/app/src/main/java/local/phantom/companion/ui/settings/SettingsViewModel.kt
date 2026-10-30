package local.phantom.companion.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.data.PhantomDatabase
import local.phantom.companion.data.AppContainer
import java.io.File

/**
 * The settings state seam. Nothing here hands the operator a credential field:
 * the two key flows survive only as a boolean — «є що прибрати» — and the one
 * write left is the erase.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val ttsEnabled: StateFlow<Boolean> = container.voicePrefs.ttsEnabled
    val screenAwarenessEnabled: StateFlow<Boolean> = container.voicePrefs.screenAwarenessEnabled
    val micMuted: StateFlow<Boolean> = container.voicePrefs.micMuted
    val liveDialogEnabled: StateFlow<Boolean> = container.voicePrefs.liveDialogEnabled
    val proactiveEnabled: StateFlow<Boolean> = container.voicePrefs.proactiveEnabled

    /**
     * Чи є ключ, яким МОЖНА ВІДПОВІСТИ. Тільки Gemini.
     *
     * Тут стояло `gemini != null || hf != null`, і поки поля для токена HF не
     * існувало, друга половина була завжди false — вада спала. Щойно поле
     * зʼявилось, вона прокидалась би найгіршим чином: збережений токен
     * HuggingFace (кредитив на ЗАВАНТАЖЕННЯ файлу) вмикав би «розум у хмарі»
     * в [BrainPane] і зелену «хмару» в [capabilities], хоча відповідати
     * як не було чим, так і не стало.
     */
    val keysStored: StateFlow<Boolean> =
        container.aiKeyStore.geminiKey
            .map { !it.isNullOrBlank() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Чи є ЩО прибирати — єдине, для чого обидва ключі рівні. */
    val anyKeyStored: StateFlow<Boolean> =
        combine(container.aiKeyStore.geminiKey, container.aiKeyStore.hfToken) { gemini, hf ->
            !gemini.isNullOrBlank() || !hf.isNullOrBlank()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _regionsLoaded = MutableStateFlow(false)

    /** null until the first poll answers — «читаю…» is honest, «0» is not. */
    val offlineRegionCount: StateFlow<Int?> =
        combine(container.offlineRegions.regions, _regionsLoaded.asStateFlow()) { regions, loaded ->
            if (loaded) regions.count { it.isComplete } else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val ttsDisplayName: String get() = container.ttsEngine.displayName

    /* ---- Retention: the operator's number, made real ---- */

    /** The live retention term in days. 0 = «назавжди». */
    val retentionDays: StateFlow<Int> = container.voicePrefs.cleanupAfterDays

    private val _retention = MutableStateFlow<RetentionUiState?>(null)

    /** Counts at rest, recomputed on entry and after every apply. Null until read. */
    val retention: StateFlow<RetentionUiState?> = _retention.asStateFlow()

    private val streamDao get() = container.database.streamItemDao()

    fun refreshRetention() {
        viewModelScope.launch {
            _retention.value = runCatching { computeRetention() }.getOrNull()
        }
    }

    private suspend fun computeRetention(): RetentionUiState {
        val now = System.currentTimeMillis()
        val perDetent = RETENTION_FINITE_DETENTS.associateWith { d ->
            streamDao.countOlderThan(now - d.toLong() * ONE_DAY_MS)
        }
        return RetentionUiState(
            totalItems = streamDao.count(),
            oldestMs = streamDao.minCreatedAt(),
            pinnedCount = streamDao.pinnedCount(),
            approxBytes = retentionDbBytes(),
            computedAtMs = now,
            perDetentDelete = perDetent,
        )
    }

    /** Length of the encrypted DB plus its WAL/SHM sidecars — an honest «приблизно». */
    private fun retentionDbBytes(): Long {
        val base = container.applicationContext.getDatabasePath(PhantomDatabase.DB_NAME)
        return listOf("", "-wal", "-shm").sumOf { suffix ->
            File(base.path + suffix).let { if (it.exists()) it.length() else 0L }
        }
    }

    /** Lengthening, or a shortening that would delete nothing: apply on tap. */
    fun applyRetention(days: Int) {
        container.voicePrefs.setCleanupAfterDays(days)
        refreshRetention()
    }

    /**
     * A confirmed shortening: apply the term, then run the sweep immediately
     * (`force = true`) so consent and deletion coincide — the number the pill
     * promised is removed now, not by a reaper the operator never sees.
     */
    fun confirmShortenRetention(days: Int) {
        container.voicePrefs.setCleanupAfterDays(days)
        viewModelScope.launch {
            runCatching { container.streamCleanupScheduler.runOnceIfDue(force = true) }
            refreshRetention()
        }
    }

    private companion object {
        const val ONE_DAY_MS = 86_400_000L
    }

    fun refreshRegions() {
        viewModelScope.launch {
            runCatching { container.offlineRegions.refresh() }
            _regionsLoaded.value = true
        }
    }

    fun clearKeys() {
        container.aiKeyStore.saveGeminiKey(null)
        container.aiKeyStore.saveHfToken(null)
    }

    fun setTtsEnabled(enabled: Boolean) = container.voicePrefs.setTtsEnabled(enabled)
    fun setProactiveEnabled(enabled: Boolean) = container.voicePrefs.setProactiveEnabled(enabled)

    fun setScreenAwarenessEnabled(enabled: Boolean) =
        container.voicePrefs.setScreenAwarenessEnabled(enabled)
    fun setMicMuted(muted: Boolean) = container.voicePrefs.setMicMuted(muted)
    fun setLiveDialogEnabled(enabled: Boolean) = container.voicePrefs.setLiveDialogEnabled(enabled)

    /**
     * The copy runs on the ModelManager's application scope, not
     * [viewModelScope] — a multi-hundred-MB import must not be aborted by the
     * operator backing out of Settings.
     */
    fun importModel(type: ModelType, uri: Uri, resolver: ContentResolver) {
        runCatching { resolver.openInputStream(uri) }
            .getOrNull()
            ?.let { input -> container.modelManager.startImport(type, input) }
    }
}
