package local.phantom.companion.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

enum class BrainProfile(val wire: String) {
    CHAT("chat"),
    ACT("act"),
    VOICE("voice"),
    SUMMARY("summary");

    companion object {
        fun fromWire(raw: String?): BrainProfile? = entries.firstOrNull { it.wire == raw }
    }
}

val AIIntent.effectiveProfile: BrainProfile
    get() = when (this) {
        is AIIntent.Text -> profile ?: if (tools.isNotEmpty()) BrainProfile.ACT else BrainProfile.CHAT
    }

object BrainProfiles {
    val DEFAULT: Map<BrainProfile, String> = mapOf(
        BrainProfile.CHAT to "gemini-3.1-flash-lite",
        BrainProfile.ACT to "gemini-3.8-flash",
        BrainProfile.VOICE to "gemini-3.1-flash-lite",
        BrainProfile.SUMMARY to "gemini-flash-lite-latest",
    )

    @Volatile
    var current: Map<BrainProfile, String> = DEFAULT
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private val MODEL_ID = Regex("[A-Za-z0-9._-]{1,64}")

    private fun modelId(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { MODEL_ID.matches(it) }

    fun modelFor(p: BrainProfile): String = current[p] ?: DEFAULT.getValue(p)

    fun accept(json: JsonObject?) {
        val profilesObj = json?.get("profiles") as? JsonObject ?: return
        val updates = LinkedHashMap<BrainProfile, String>()
        for (profile in BrainProfile.entries) {
            val raw = modelId(profilesObj[profile.wire])
            if (raw != null) {
                updates[profile] = raw
            }
        }
        if (updates.isNotEmpty()) {
            current = current + updates
        }
    }

    fun restore(saved: String?) {
        if (saved.isNullOrBlank()) {
            current = DEFAULT
            return
        }
        val parsed = runCatching { json.parseToJsonElement(saved).jsonObject }.getOrNull()
        if (parsed == null) {
            current = DEFAULT
            return
        }
        val next = LinkedHashMap(DEFAULT)
        for (profile in BrainProfile.entries) {
            val raw = modelId(parsed[profile.wire])
            if (raw != null) {
                next[profile] = raw
            }
        }
        current = next
    }

    fun saved(): String {
        val snapshot = current
        return buildJsonObject {
            for (profile in BrainProfile.entries) {
                put(profile.wire, JsonPrimitive(snapshot[profile] ?: DEFAULT.getValue(profile)))
            }
        }.toString()
    }
}
