package local.phantom.companion.data.backup

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class WebBackupStash(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), String.serializer())
    private val file = File(dir, "web_stash.json")
    private val pending = File(dir, "web_restore.json")
    private var cache: Map<String, String>? = null

    @Synchronized
    fun put(key: String, raw: String) {
        if (!allowed(key)) return
        val now = load()
        if (now[key] == raw) return
        save(now + (key to raw))
    }

    @Synchronized
    fun replace(raw: String) {
        val next = parse(raw) ?: return
        if (next != load()) save(next)
    }

    @Synchronized
    fun all(): Map<String, String> = load()

    @Synchronized
    fun restore(entries: Map<String, String>) {
        val clean = entries.filterKeys(::allowed)
        save(clean)
        write(pending, json.encodeToString(ser, clean))
    }

    @Synchronized
    fun take(): String {
        if (!pending.isFile) return ""
        val out = runCatching { pending.readText() }.getOrDefault("")
        pending.delete()
        return out
    }

    private fun load(): Map<String, String> =
        cache ?: (runCatching { if (file.isFile) parse(file.readText()) else null }.getOrNull() ?: emptyMap()).also { cache = it }

    private fun save(m: Map<String, String>) {
        write(file, json.encodeToString(ser, m))
        cache = m
    }

    private fun write(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun parse(raw: String): Map<String, String>? =
        runCatching { json.decodeFromString(ser, raw) }.getOrNull()?.filterKeys(::allowed)

    companion object {
        // Same list as isBackedUpKey in phantom-os/src/frontend/src/services/backupStash.ts.
        val KEYS: Set<String> = setOf(
            "np.profiles.v1",
            "np.chatprofile.v1",
            "phantom.listFolders.v1",
            "phantom_archived_chats",
            "np.drafts.v1",
            "np.pins1to1.v1",
            "np.pollVotes.v1",
            "phantom_pinned_chats",
            "phantom_unread_marks",
            "phantom_hushed_chats",
            "phantom_hushed_until",
            "phantom_hush_rules.v1",
            "np.chatKeep.v1",
            "np.selfAdded.v1",
            "nightpost.moments.archive",
            "nightpost.moments.audienceChosen",
            "nightpost.moments.kept",
            "nightpost.moments.known",
            "np.momentseen.v1",
            "np.momentprofile.v1",
            "np.callPrefs.v1",
            "np.gifts.opened",
            "np.stickers.v1",
            "phantom_recent_reactions",
            "np.support.tickets.v1",
            "np.support.reports.v1",
            "np.bizinbox.v1",
            "np.circleRights.v1",
            "np.ringPresets.v1",
            "phantom_language",
            "ui_language",
            "phantom_theme",
            "phantom.themeMode",
            "phantom_invite_name",
            "phantom_invite_address",
            "phantom_user_profile",
            "phantom_pay_notes",
            "phantom_points_payment_claims",
            "phantom_meetup_reminders",
            "np.voiceText.v1",
            "nightpost.builder.pages.v1",
            "np.tempended.v1",
        )
        val PREFIXES: List<String> = listOf("np.spaces.v1.", "np.work.v1.")

        fun allowed(key: String): Boolean = key in KEYS || PREFIXES.any(key::startsWith)
    }
}
