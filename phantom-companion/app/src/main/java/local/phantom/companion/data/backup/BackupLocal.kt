package local.phantom.companion.data.backup

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.data.places.DrawnLineEntity
import local.phantom.companion.core.data.places.SavedPlaceEntity
import local.phantom.companion.core.data.reminders.ReminderEntity
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.EncryptedSlot

internal object BackupLocal {
    val SLOTS: Map<String, Set<String>> = mapOf(
        "phantom_moments" to setOf("seen", "book", "prefs", "muted"),
        "phantom_scheduled" to setOf("letters"),
        "phantom_contact_requests" to setOf("rows"),
    )

    const val REMINDERS = "reminders"
    const val PLACES = "saved_places"
    const val LINES = "drawn_lines"
    val TABLES = listOf(REMINDERS, PLACES, LINES)

    fun allowedSlot(slot: String, key: String): Boolean = SLOTS[slot]?.contains(key) == true

    @Serializable
    data class Reminder(val id: String, val text: String, val at: Long, val lead: Int, val rec: String? = null, val fired: Boolean, val created: Long) {
        fun entity() = ReminderEntity(id, text, at, lead, rec, fired, created)
        companion object {
            fun of(e: ReminderEntity) = Reminder(e.id, e.text, e.triggerAtMs, e.leadMinutes, e.recurrence, e.fired, e.createdAt)
        }
    }

    @Serializable
    data class Place(val id: String, val label: String, val cat: String, val lat: Double, val lon: Double, val address: String? = null, val note: String? = null, val created: Long, val used: Long) {
        fun entity() = SavedPlaceEntity(id, label, cat, lat, lon, address, note, created, used)
        companion object {
            fun of(e: SavedPlaceEntity) = Place(e.id, e.label, e.category, e.lat, e.lon, e.address, e.note, e.createdAt, e.lastUsedMs)
        }
    }

    @Serializable
    data class Line(val id: String, val label: String, val vertices: String, val created: Long, val updated: Long) {
        fun entity() = DrawnLineEntity(id, label, vertices, created, updated)
        companion object {
            fun of(e: DrawnLineEntity) = Line(e.id, e.label, e.vertices, e.createdAt, e.updatedAt)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun <T> encode(ser: KSerializer<T>, items: List<T>): List<String> = items.map { json.encodeToString(ser, it) }

    // null: one row did not parse, so the whole copy stays untouched.
    fun <T> decode(ser: KSerializer<T>, rows: List<String>): List<T>? =
        rows.map { runCatching { json.decodeFromString(ser, it) }.getOrNull() ?: return null }

    fun mediaIn(blobRoot: File): List<BackupManifest.MediaLeft> =
        blobRoot.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("_") }.flatMap { conv ->
            conv.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".bad") }
                .map { BackupManifest.MediaLeft(kind = "chat", name = it.name, bytes = it.length()) }
        }

    private const val LEFT_FILE = "backup_media_left.json"

    fun keepLeft(filesDir: File, media: List<BackupManifest.MediaLeft>) {
        val f = File(filesDir, LEFT_FILE)
        if (media.isEmpty()) f.delete() else f.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BackupManifest.MediaLeft.serializer()), media))
    }

    fun left(filesDir: File): Int =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BackupManifest.MediaLeft.serializer()), File(filesDir, LEFT_FILE).readText()).size }
            .getOrDefault(0)
}

internal fun AppContainer.collectLocalSlots(): Map<String, Map<String, String>> =
    BackupLocal.SLOTS.mapValues { (name, keys) ->
        runCatching {
            val slot = EncryptedSlot(applicationContext, name, durable = true)
            keys.mapNotNull { k -> slot.read(k)?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }.filterValues { it.isNotEmpty() }

internal fun AppContainer.collectLocalRows(): Map<String, List<String>> = runCatching {
    runBlocking {
        mapOf(
            BackupLocal.REMINDERS to BackupLocal.encode(BackupLocal.Reminder.serializer(), database.reminderDao().getAll().map(BackupLocal.Reminder::of)),
            BackupLocal.PLACES to BackupLocal.encode(BackupLocal.Place.serializer(), database.savedPlaceDao().getAll().map(BackupLocal.Place::of)),
            BackupLocal.LINES to BackupLocal.encode(BackupLocal.Line.serializer(), database.drawnLineDao().getAll().map(BackupLocal.Line::of)),
        ).filterValues { it.isNotEmpty() }
    }
}.getOrDefault(emptyMap())

internal class LocalPlan(
    val slots: Map<String, Map<String, String>>,
    val reminders: List<BackupLocal.Reminder>,
    val places: List<BackupLocal.Place>,
    val lines: List<BackupLocal.Line>,
    val media: List<BackupManifest.MediaLeft>,
) {
    companion object {
        fun of(s: BackupManifest.LocalSection): LocalPlan? {
            val reminders = BackupLocal.decode(BackupLocal.Reminder.serializer(), s.rows(BackupLocal.REMINDERS)) ?: return null
            val places = BackupLocal.decode(BackupLocal.Place.serializer(), s.rows(BackupLocal.PLACES)) ?: return null
            val lines = BackupLocal.decode(BackupLocal.Line.serializer(), s.rows(BackupLocal.LINES)) ?: return null
            return LocalPlan(slots = s.slots(), reminders = reminders, places = places, lines = lines, media = s.media)
        }
    }
}

internal fun AppContainer.restoreLocal(plan: LocalPlan) {
    plan.slots.forEach { (name, values) ->
        val slot = EncryptedSlot(applicationContext, name, durable = true)
        values.forEach { (k, v) -> slot.write(k, v) }
    }
    runBlocking {
        plan.reminders.forEach { database.reminderDao().insert(it.entity()) }
        plan.places.forEach { database.savedPlaceDao().insert(it.entity()) }
        plan.lines.forEach { database.drawnLineDao().upsert(it.entity()) }
    }
    BackupLocal.keepLeft(applicationContext.filesDir, plan.media)
    if (plan.slots.containsKey("phantom_scheduled")) {
        runCatching { local.phantom.companion.service.ScheduledLetterAlarms.rearm(applicationContext, scheduledLetters) }
    }
}
