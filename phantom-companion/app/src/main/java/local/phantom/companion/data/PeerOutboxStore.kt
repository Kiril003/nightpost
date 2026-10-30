package local.phantom.companion.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.data.msgstore.MsgBase
import local.phantom.companion.data.msgstore.MsgMigration

/** Дві дії над зашифрованим файлом — рівно те, чого сховищу треба від андроїда. */
internal interface PeerLogSlot {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * The conversation, on disk.
 *
 * It lived in a StateFlow and nowhere else, so everything anyone said vanished
 * when the process died — including messages still owed, which is the exact
 * opposite of the promise the outbox makes. A queue that forgets under memory
 * pressure is not a queue.
 *
 * Encrypted at rest for the obvious reason: this is what people said to each
 * other, and the phone may be lost.
 *
 * Розбір ніколи не повертає порожнечу замість помилки. Раніше повертав: один
 * невідомий стан або один обрізаний хвіст давали нуль листів, а наступний
 * [save] клав «[]» поверх того, що ще лежало на диску — без сліду й без копії.
 * Дорога назад із новішої збірки в старішу коштувала всієї переписки.
 *
 * З базою ([openBase]) слот — лише джерело переїзду й запасні двері на запуск,
 * коли база не піднялась. У базі слова не обрізаються: межа [PeerOutbox.MAX_PER_PEER]
 * забирає звідти лише відпрацьовані шматки файлів і профілі.
 */
class PeerOutboxStore internal constructor(
    private val slot: PeerLogSlot,
    // «Збережене» не має межі: нотатка собі не буває «доставленою й зайвою».
    private val bounded: Boolean = true,
    private val openBase: (() -> MsgBase?)? = null,
) {

    constructor(context: Context) : this(
        EncryptedSlot(context),
        openBase = { context.applicationContext.let(MsgBase::open) },
    )

    private val lock = Any()
    private var base: MsgBase? = null

    /** Що лежить у базі — від нього рахується різниця кожного запису. */
    private var onDisk: Map<String, PeerMessage> = emptyMap()

    internal val history: MsgBase? get() = synchronized(lock) { base }

    private val json = Json {
        ignoreUnknownKeys = true
        // Рятує від невідомого ЗНАЧЕННЯ enum, чого ignoreUnknownKeys не вміє:
        // стан, якого ця збірка не знає, стає типовим для поля, а не валить
        // розбір усього списку. Тому [PeerMessage.state] і .sealing мусять
        // мати типові значення — без них коерція не має на що впасти.
        coerceInputValues = true
    }

    /** Чи весь диск прочитано. Хибне — писати порожнечу поверх заборонено. */
    @Volatile
    private var whole = true

    fun load(): List<PeerMessage> {
        synchronized(lock) { return loadLocked() }
    }

    private fun loadLocked(): List<PeerMessage> {
        val opened = openBase?.let { open -> runCatching { open() }.onFailure { Log.e(TAG, "база розмов", it) }.getOrNull() }
        if (opened != null) {
            val fromBase = runCatching { loadFrom(opened) }
                .onFailure { Log.e(TAG, "переїзд не вдався, лишаюсь на старих дверях", it) }
                .getOrNull()
            if (fromBase != null) {
                base = opened
                return fromBase
            }
        }
        base = null
        return loadSlot()
    }

    private fun loadFrom(db: MsgBase): List<PeerMessage> {
        MsgMigration.run(db, slot.read(KEY), ::parseSlot) { slot.write(KEY, null) }
        val all = db.lines.all()
        val kept = shed(all)
        if (kept.size != all.size) {
            val keep = kept.mapTo(HashSet()) { it.id }
            db.sql.tx { db.lines.delete(all.filterNot { it.id in keep }.map { it.id }) }
        }
        onDisk = kept.associateBy { it.id }
        whole = true
        return kept
    }

    /** Забирає з бази лише те, що обрізала б стара межа і що не є словами. */
    private fun shed(all: List<PeerMessage>): List<PeerMessage> {
        var pruned = all
        all.asSequence().flatMap { sequenceOf(it.toId, it.fromId) }.distinct().forEach { peer ->
            pruned = PeerOutbox.prune(pruned, peer)
        }
        if (pruned.size == all.size) return all
        val left = pruned.mapTo(HashSet()) { it.id }
        return all.filter { it.id in left || !(PeerFile.isChunk(it.body) || PeerProfile.isProfile(it.body) || local.phantom.companion.core.net.peer.PeerBanner.isBanner(it.body) || local.phantom.companion.core.net.peer.PeerBanner.isPull(it.body)) }
    }

    private fun parseSlot(raw: String): List<PeerMessage> =
        runCatching { json.decodeFromString<List<PeerMessage>>(raw) }.getOrNull() ?: run {
            slot.write(DAMAGED_KEY, raw)
            salvage(raw)
        }

    private fun loadSlot(): List<PeerMessage> {
        val raw = slot.read(KEY)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val parsed = runCatching { json.decodeFromString<List<PeerMessage>>(raw) }.getOrNull()
        if (parsed != null) {
            whole = true
            return parsed
        }
        // Єдина мить, коли зіпсований текст ще в руках: далі його перекриє
        // перший же запис. Тож копія лягає осторонь саме тут.
        slot.write(DAMAGED_KEY, raw)
        whole = false
        return salvage(raw)
    }

    /** У базу лягає лише різниця з тим, що вже там лежить. */
    fun save(messages: List<PeerMessage>) {
        synchronized(lock) { saveLocked(messages) }
    }

    private fun saveLocked(messages: List<PeerMessage>) {
        val db = base ?: return saveSlot(messages)
        val next = LinkedHashMap<String, PeerMessage>(messages.size)
        messages.forEach { if (!PeerWhere.isWhere(it.body)) next[it.id] = it }
        val changed = next.values.filter { m -> onDisk[m.id].let { it !== m && it != m } }
        val gone = onDisk.keys.filter { it !in next }
        if (changed.isEmpty() && gone.isEmpty()) return
        db.sql.tx {
            db.lines.upsert(changed)
            db.lines.delete(gone)
        }
        onDisk = next
    }

    /**
     * Writes the log back, pruned per peer so a long friendship cannot grow
     * without bound. Pruning only ever forgets DELIVERED messages.
     */
    private fun saveSlot(messages: List<PeerMessage>) {
        // Порожнеча поверх нерозібраного — це і є те мовчазне стирання: у
        // памʼяті нічого немає лише тому, що впав розбір, а не тому, що людина
        // щось стерла. Перший непорожній запис знімає заборону.
        if (messages.isEmpty() && !whole) return
        // Положення людей на диск не лягає ніколи, в жоден бік: навіть стертий рядок лишив би шифротекст у вільних блоках флешу.
        var kept = messages.filterNot { PeerWhere.isWhere(it.body) }
        if (bounded) messages.map { it.toId }.plus(messages.map { it.fromId }).distinct().forEach { peer ->
            kept = PeerOutbox.prune(kept, peer)
        }
        slot.write(KEY, json.encodeToString(kept))
        whole = true
    }

    fun clear() {
        synchronized(lock) {
            base?.let { db -> db.sql.tx { db.lines.deleteAll() } }
            onDisk = emptyMap()
            slot.write(KEY, null)
            slot.write(DAMAGED_KEY, null)
            whole = true
        }
    }

    /**
     * Дістає кожен обʼєкт верхнього рівня окремо. Обрізаний хвіст і зіпсована
     * середина коштують своїх листів — не всієї розмови.
     */
    private fun salvage(raw: String): List<PeerMessage> {
        val out = ArrayList<PeerMessage>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        raw.forEachIndexed { i, c ->
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                c == '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        val body = raw.substring(start, i + 1)
                        runCatching { json.decodeFromString<PeerMessage>(body) }
                            .onSuccess { out += it }
                        start = -1
                    }
                }
            }
        }
        return out
    }

    internal companion object {
        const val TAG = "PeerOutboxStore"
        const val FILE_NAME = "phantom_outbox_v1"
        const val SAVED_FILE_NAME = "phantom_saved_notes_v1"

        fun savedNotes(context: Context): PeerOutboxStore =
            PeerOutboxStore(EncryptedSlot(context, SAVED_FILE_NAME, durable = true), bounded = false)
        const val KEY = "messages"

        /** Те, що не розібралось, дослівно. Живе доти, доки не зіпсується ще раз. */
        const val DAMAGED_KEY = "messages_damaged"
    }
}

internal class EncryptedSlot(
    context: Context,
    name: String = PeerOutboxStore.FILE_NAME,
    private val durable: Boolean = false,
) : PeerLogSlot {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        val edit = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }
        if (durable) edit.commit() else edit.apply()
    }
}
