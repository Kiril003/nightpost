package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "phantom_symbiote_prefs"
private const val PREFS_FALLBACK_NAME = "phantom_symbiote_prefs_fallback"

private const val KEY_HAPTIC = "tier14_haptic_ticker"
private const val KEY_POSTURE = "tier14_posture_monitor"
private const val KEY_HRV_TEMPO = "tier17_hrv_tempo"
private const val KEY_STRESS_GHOST = "tier17_stress_ghost"
private const val KEY_DOUBT = "tier15_doubt_amplifier"
private const val KEY_CONVICTION = "tier15_conviction_scoring"
private const val KEY_PARK = "tier23_mental_park"
private const val KEY_BASELINE = "tier26_baseline_learning"
private const val KEY_AUDIT = "tier29_daily_audit"

class SymbiotePreferences(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { t ->
        Log.e("SymbiotePrefs", "encrypted prefs init failed, using plain", t)
        context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
    }

    private val _hapticEnabled = MutableStateFlow(prefs.getBoolean(KEY_HAPTIC, false))
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _postureEnabled = MutableStateFlow(prefs.getBoolean(KEY_POSTURE, false))
    val postureEnabled: StateFlow<Boolean> = _postureEnabled.asStateFlow()

    private val _hrvTempoEnabled = MutableStateFlow(prefs.getBoolean(KEY_HRV_TEMPO, false))
    val hrvTempoEnabled: StateFlow<Boolean> = _hrvTempoEnabled.asStateFlow()

    private val _stressGhostEnabled = MutableStateFlow(prefs.getBoolean(KEY_STRESS_GHOST, false))
    val stressGhostEnabled: StateFlow<Boolean> = _stressGhostEnabled.asStateFlow()

    private val _doubtAmplifierEnabled = MutableStateFlow(prefs.getBoolean(KEY_DOUBT, false))
    val doubtAmplifierEnabled: StateFlow<Boolean> = _doubtAmplifierEnabled.asStateFlow()

    private val _convictionEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONVICTION, false))
    val convictionEnabled: StateFlow<Boolean> = _convictionEnabled.asStateFlow()

    private val _parkEnabled = MutableStateFlow(prefs.getBoolean(KEY_PARK, false))
    val parkEnabled: StateFlow<Boolean> = _parkEnabled.asStateFlow()

    private val _baselineEnabled = MutableStateFlow(prefs.getBoolean(KEY_BASELINE, false))
    val baselineEnabled: StateFlow<Boolean> = _baselineEnabled.asStateFlow()

    private val _auditEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUDIT, true))
    val auditEnabled: StateFlow<Boolean> = _auditEnabled.asStateFlow()

    fun setHaptic(v: Boolean) = put(KEY_HAPTIC, v, _hapticEnabled)
    fun setPosture(v: Boolean) = put(KEY_POSTURE, v, _postureEnabled)
    fun setHrvTempo(v: Boolean) = put(KEY_HRV_TEMPO, v, _hrvTempoEnabled)
    fun setStressGhost(v: Boolean) = put(KEY_STRESS_GHOST, v, _stressGhostEnabled)
    fun setDoubtAmplifier(v: Boolean) = put(KEY_DOUBT, v, _doubtAmplifierEnabled)
    fun setConviction(v: Boolean) = put(KEY_CONVICTION, v, _convictionEnabled)
    fun setPark(v: Boolean) = put(KEY_PARK, v, _parkEnabled)
    fun setBaseline(v: Boolean) = put(KEY_BASELINE, v, _baselineEnabled)
    fun setAudit(v: Boolean) = put(KEY_AUDIT, v, _auditEnabled)

    private fun put(key: String, v: Boolean, flow: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, v).apply()
        flow.value = v
    }
}
