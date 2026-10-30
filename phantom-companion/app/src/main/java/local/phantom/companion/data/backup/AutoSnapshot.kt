package local.phantom.companion.data.backup

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object AutoSnapshot {
    const val DIR = "autobackup"
    const val FILE = "snapshot.json"
    private const val SEEN = "autobackup_seen"
    private const val HELD = "autobackup_held"
    private const val SEALED_V = 2
    const val KEY_BYTES = 32
    private const val MNEMONIC_RUN = 12
    private const val MAX_DEPTH = 6

    private val KEYSTORE_MARKERS = listOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__",
        "type.googleapis.com/google.crypto.tink",
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Snapshot(
        val v: Int = 1,
        @SerialName("at_ms") val atMs: Long,
        val wallet: String? = null,
        val manifest: String,
        @SerialName("wallet_key") val walletKey: List<String>? = null,
    )

    @Serializable
    private data class Sealed(val v: Int, val kid: String, val iv: String, val ct: String)

    enum class Written { OK, REFUSED, FAILED, SKIPPED, NO_KEY, OFF }

    sealed interface Opened {
        data class Ok(val snapshot: Snapshot) : Opened
        data object Absent : Opened
        data object NoKey : Opened
        data object Unreadable : Opened
    }

    @Volatile var frozen: Boolean = false

    fun file(filesDir: File): File = File(File(filesDir, DIR), FILE)

    // Знімок без позначки в noBackupFilesDir могла покласти лише ОС при встановленні.
    fun pending(filesDir: File, noBackupDir: File): Boolean =
        !File(noBackupDir, SEEN).exists() && file(filesDir).isFile

    fun settle(noBackupDir: File) {
        noBackupDir.mkdirs()
        File(noBackupDir, SEEN).createNewFile()
    }

    // Копія, яку не вдалося покласти назад, лежить недоторкана, доки людина сама не почне нову.
    fun held(noBackupDir: File): Boolean = File(noBackupDir, HELD).exists()

    fun release(noBackupDir: File) {
        File(noBackupDir, HELD).delete()
    }

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    fun kid(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).take(8).joinToString("") { "%02x".format(it) }

    fun restore(filesDir: File, noBackupDir: File, key: ByteArray?, apply: (Snapshot) -> RestoreResult): RestoreResult? {
        val result = when (val o = open(filesDir, key)) {
            Opened.Absent -> null
            Opened.NoKey, Opened.Unreadable -> RestoreResult.Error(RestoreResult.Reason.INVALID)
            is Opened.Ok -> runCatching { apply(o.snapshot) }.getOrElse { RestoreResult.Error(RestoreResult.Reason.WRITE) }
        }
        if (result != null && result !is RestoreResult.Success) File(noBackupDir.apply { mkdirs() }, HELD).createNewFile()
        settle(noBackupDir)
        return result
    }

    fun ageMs(filesDir: File, nowMs: Long): Long? =
        file(filesDir).takeIf { it.isFile }?.let { nowMs - it.lastModified() }

    @Synchronized
    fun write(
        filesDir: File,
        noBackupDir: File,
        snapshot: Snapshot,
        walletWords: Set<String>,
        key: ByteArray?,
        enabled: Boolean = true,
        deviceSecure: Boolean = false,
    ): Written {
        if (frozen || pending(filesDir, noBackupDir) || held(noBackupDir)) return Written.SKIPPED
        settle(noBackupDir)
        val target = file(filesDir)
        if (!enabled) {
            target.delete()
            return Written.OFF
        }
        if (key == null || key.size != KEY_BYTES) {
            target.delete()
            return Written.NO_KEY
        }
        val bare = snapshot.copy(walletKey = null)
        val text = json.encodeToString(Snapshot.serializer(), bare)
        if (walletWords.isEmpty() || leaks(text, walletWords)) {
            target.delete()
            return Written.REFUSED
        }
        val slot = snapshot.walletKey?.takeIf { deviceSecure && isMnemonic(it, walletWords) }
        val plain = if (slot == null) text else json.encodeToString(Snapshot.serializer(), bare.copy(walletKey = slot))
        val dir = target.parentFile!!
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        return runCatching {
            tmp.writeText(seal(plain, key))
            check(tmp.renameTo(target))
        }.fold({ Written.OK }, { tmp.delete(); Written.FAILED })
    }

    // Над 25 МБ ОС відкидає копію застосунку цілком: краще без фото й найстаріших листів, ніж нічого.
    const val LIMIT_CHARS = 18 * 1024 * 1024
    private const val BIG_FILE_CHARS = 64 * 1024

    fun fit(m: BackupManifest, limit: Int = LIMIT_CHARS): BackupManifest {
        if (m.encode().length <= limit) return m
        val decoder = Base64.getDecoder()
        fun nameOf(b64: String) = runCatching { String(decoder.decode(b64), Charsets.UTF_8) }.getOrDefault(b64)
        val (keptFiles, bigFiles) = m.selfFiles.filesB64.entries.partition { it.value.length <= BIG_FILE_CHARS }
        var cur = m.copy(
            selfFiles = BackupManifest.SelfFilesSection(keptFiles.associate { it.key to it.value }),
            local = m.local.copy(media = m.local.media + bigFiles.map { BackupManifest.MediaLeft("self", nameOf(it.key), it.value.length * 3L / 4) }),
        )
        var msgs = cur.chats.msgsB64.sortedBy { b -> BackupManifest.ChatsSection(msgsB64 = listOf(b)).messages().firstOrNull()?.sentAtMs ?: 0L }
        while (cur.encode().length > limit && msgs.isNotEmpty()) {
            msgs = msgs.drop(maxOf(1, msgs.size / 4))
            cur = cur.copy(chats = cur.chats.copy(msgsB64 = msgs))
        }
        if (cur.encode().length > limit) {
            val (keptWeb, bigWeb) = cur.web.itemsB64.entries.partition { it.value.length <= BIG_FILE_CHARS }
            cur = cur.copy(
                web = BackupManifest.WebSection(keptWeb.associate { it.key to it.value }),
                local = cur.local.copy(media = cur.local.media + bigWeb.map { BackupManifest.MediaLeft("web", nameOf(it.key), it.value.length * 3L / 4) }),
            )
        }
        return cur
    }

    fun read(filesDir: File, key: ByteArray?): Snapshot? = (open(filesDir, key) as? Opened.Ok)?.snapshot

    fun open(filesDir: File, key: ByteArray?): Opened {
        val raw = file(filesDir).takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() } ?: return Opened.Absent
        val sealed = runCatching { json.decodeFromString(Sealed.serializer(), raw) }.getOrNull()
            ?.takeIf { it.v == SEALED_V } ?: return Opened.Unreadable
        if (key == null || kid(key) != sealed.kid) return Opened.NoKey
        val plain = runCatching {
            val cipher = Cipher.getInstance(GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(sealed.iv)))
            cipher.updateAAD(sealed.kid.toByteArray())
            String(cipher.doFinal(Base64.getDecoder().decode(sealed.ct)), Charsets.UTF_8)
        }.getOrNull() ?: return Opened.Unreadable
        return runCatching { json.decodeFromString(Snapshot.serializer(), plain) }.getOrNull()?.let { Opened.Ok(it) } ?: Opened.Unreadable
    }

    private const val GCM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun seal(text: String, key: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val id = kid(key)
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(id.toByteArray())
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return json.encodeToString(Sealed.serializer(), Sealed(SEALED_V, id, enc.encodeToString(iv), enc.encodeToString(ct)))
    }

    fun leaks(text: String, walletWords: Set<String>): Boolean {
        val texts = ArrayList<String>()
        expand(text, 0, texts)
        return texts.any { t -> KEYSTORE_MARKERS.any { t.contains(it) } || longestRun(t, walletWords) >= MNEMONIC_RUN }
    }

    private fun expand(text: String, depth: Int, out: MutableList<String>) {
        out += text
        if (depth >= MAX_DEPTH) return
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (parsed != null && (parsed is JsonObject || parsed is JsonArray)) {
            walk(parsed, depth, out)
        } else {
            unbase64(text)?.let { expand(it, depth + 1, out) }
        }
    }

    private fun walk(e: JsonElement, depth: Int, out: MutableList<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                out += k
                unbase64(k)?.let { expand(it, depth + 1, out) }
                walk(v, depth, out)
            }
            is JsonArray -> e.forEach { walk(it, depth, out) }
            is JsonPrimitive -> if (e.isString) expand(e.content, depth + 1, out)
        }
    }

    private fun unbase64(s: String): String? {
        val t = s.trim()
        if (t.length < 4) return null
        val bytes = runCatching { Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(t) }.getOrNull()
            ?: return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private val MNEMONIC_SIZES = setOf(12, 15, 18, 21, 24)

    // The one field the guard lets through: exactly a word list from the dictionary, nothing else rides along.
    fun isMnemonic(words: List<String>, dict: Set<String>): Boolean =
        words.size in MNEMONIC_SIZES && words.all { it in dict }

    private val SPLIT = Regex("[^\\p{L}]+")

    private fun longestRun(text: String, dict: Set<String>): Int {
        var best = 0
        var run = 0
        for (w in text.lowercase().split(SPLIT)) {
            if (w.isEmpty()) continue
            run = if (w in dict) run + 1 else 0
            if (run > best) best = run
        }
        return best
    }
}
