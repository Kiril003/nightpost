package local.phantom.companion.core.context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import local.phantom.companion.core.bus.PhantomEvent
import local.phantom.companion.core.bus.PhantomNervousBus
import java.util.UUID

/**
 * Assembles a unified [ContextFrame] from [ContextInput], [ContextSnapshot],
 * and any transient events received via [PhantomNervousBus].
 *
 * Runs inside the ContextEngine tick loop: each tick calls [build] which
 * reads accumulated bus signals, merges them with fresh sensor data, and
 * produces the canonical perception snapshot. After build, the resulting
 * ContextFrame is also published back to the bus as a CONTEXT_FRAME event
 * so every downstream organ can consume it.
 */
class ContextFrameBuilder(
    private val bus: PhantomNervousBus,
    private val deviceId: String,
    private val profileIdProvider: () -> String? = { null },
) {
    private val recentSounds = mutableListOf<String>()
    private val recentScreenPackages = mutableListOf<String?>()
    private val _latestFrame = MutableStateFlow<ContextFrame?>(null)
    val latestFrame: StateFlow<ContextFrame?> = _latestFrame.asStateFlow()

    /**
     * Starts listening to the nervous bus for transient signals that
     * arrive between ticks (e.g. sound classification, screen changes).
     */
    fun startListening(scope: CoroutineScope) {
        scope.launch {
            bus.events.collect { event ->
                when (event.type) {
                    TYPE_SOUND_CLASSIFICATION -> {
                        synchronized(recentSounds) {
                            recentSounds.add(event.payloadJson)
                            if (recentSounds.size > MAX_TRANSIENT_BUFFER) recentSounds.removeAt(0)
                        }
                    }
                    TYPE_SCREEN_CHANGED -> {
                        // QA-fix 2026-07-17 — the bus payload historically
                        // carried raw JSON / dump text; a buffer whose name
                        // says «packages» accepts only things shaped like
                        // one, or the garbage resurfaces as the operator's
                        // «активна занятість».
                        val pkg = event.payloadJson.trim().takeIf(::looksLikePackage)
                        if (pkg != null) {
                            synchronized(recentScreenPackages) {
                                recentScreenPackages.add(pkg)
                                if (recentScreenPackages.size > MAX_TRANSIENT_BUFFER) {
                                    recentScreenPackages.removeAt(0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds a [ContextFrame] from the current tick's data and publishes it
     * to the nervous bus.
     */
    fun build(
        input: ContextInput,
        snapshot: ContextSnapshot,
    ): ContextFrame {
        val sounds = synchronized(recentSounds) {
            val copy = recentSounds.toList()
            recentSounds.clear()
            copy
        }

        val mergedSounds = (input.detectedSounds + sounds).distinct()

        val lastScreen = synchronized(recentScreenPackages) {
            recentScreenPackages.lastOrNull()
        }

        val hasPeopleNearby = mergedSounds.any { sound ->
            listOf("speech", "laughter", "screaming", "whispering", "singing", "conversation", "babble", "chatter")
                .any { keyword -> sound.lowercase().contains(keyword) }
        }

        val frame = ContextFrame(
            timestampMs = input.nowMs,
            state = snapshot.state.name,
            who = WhoContext(
                profileId = profileIdProvider(),
                hasPeopleNearby = hasPeopleNearby
            ),
            where = WhereContext(
                placeLabel = inferPlaceLabel(input),
            ),
            what = WhatContext(
                // QA-fix 2026-07-17 — the field promises a package name;
                // only a package name may pass, and PHANTOM's own package
                // is a mirror, not занятість. Every consumer downstream
                // (ActiveAppNode's state vote, the Presence card, the AI
                // context line) relies on this gate.
                activeAppPackage = (lastScreen ?: input.activeAppPackage)
                    ?.trim()
                    ?.takeIf(::looksLikePackage)
                    ?.takeUnless { it.startsWith(OWN_PACKAGE_PREFIX) },
                detectedSounds = mergedSounds,
            ),
            feel = FeelContext(
                cognitiveLoad = null,
                stressLevel = null,
                emotionLabel = null,
                breathingRate = null,
            ),
            owe = OweContext(),
            device = DeviceContext(
                batteryLevel = input.batteryLevel,
                isCharging = input.isCharging,
            ),
            mesh = MeshContext(),
        )
        _latestFrame.value = frame
        publishFrameEvent(frame)
        return frame
    }

    private fun publishFrameEvent(frame: ContextFrame) {
        val json = Json.encodeToString(ContextFrame.serializer(), frame)
        bus.publish(
            PhantomEvent(
                id = UUID.randomUUID().toString(),
                timestampMs = frame.timestampMs,
                sourceOrgan = SOURCE_ORGAN,
                type = TYPE_CONTEXT_FRAME,
                payloadJson = json,
                contextFrameRef = null,
                deviceOrigin = deviceId,
                privacy = PRIVACY_TRUSTED,
            )
        )
    }

    private fun inferPlaceLabel(input: ContextInput): String {
        if (input.isCharging && input.accelMagnitude != null && input.accelMagnitude < 0.3f) {
            return PLACE_HOME
        }
        if (input.accelMagnitude != null && input.accelMagnitude > 2.0f) {
            return PLACE_MOVING
        }
        return PLACE_UNKNOWN
    }

    companion object {
        private const val MAX_TRANSIENT_BUFFER = 30
        private const val SOURCE_ORGAN = "ContextFrameBuilder"
        const val TYPE_CONTEXT_FRAME = "context_frame"
        const val TYPE_SOUND_CLASSIFICATION = "sound_classification"
        const val TYPE_SCREEN_CHANGED = "screen_changed"
        const val PRIVACY_TRUSTED = "trusted"
        const val PLACE_HOME = "Home"
        const val PLACE_MOVING = "On the Go"
        const val PLACE_UNKNOWN = "Unknown"

        /** QA-fix 2026-07-17 — self-observation is a mirror, not занятість. */
        private const val OWN_PACKAGE_PREFIX = "local.phantom.companion"

        private val PACKAGE_SHAPE =
            Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

        /**
         * True only for something shaped like an Android package name
         * (`com.android.chrome`). Raw accessibility dumps, JSON payloads
         * and «[x,y,w,h] label | …» chains all fail this — which is the
         * point: the 2026-07-17 QA drive found exactly such a dump
         * rendered on Presence as the operator's «активна занятість».
         */
        internal fun looksLikePackage(raw: String): Boolean =
            raw.length in 3..255 && PACKAGE_SHAPE.matches(raw)
    }
}
