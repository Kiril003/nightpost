package local.phantom.companion.core.solana

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PaySignal(
    private val scope: CoroutineScope,
    private val http: () -> HttpCall,
    private val base: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val beforeExit: () -> Unit = {},
    private val onSeen: () -> Unit,
) {
    private class Entry(val cluster: Cluster, val bornMs: Long, var registered: Boolean = false)

    private val lock = Any()
    private val keys = LinkedHashMap<String, Entry>()
    private var job: Job? = null
    private var offUntil: Long = 0L
    private var lastRewatchMs: Long = 0L

    fun watch(cluster: Cluster, key: SolanaKey) {
        if (base().isBlank()) return
        synchronized(lock) {
            if (keys.containsKey(key.base58)) return
            val t = now()
            if (keys.isEmpty()) lastRewatchMs = t
            keys[key.base58] = Entry(cluster, t)
            if (job?.isActive == true) return
            job = scope.launch { loop() }
        }
    }

    private suspend fun loop() {
        while (true) {
            val baseUrl = base().trimEnd('/')
            if (baseUrl.isBlank()) {
                synchronized(lock) {
                    keys.clear()
                    job = null
                }
                beforeExit()
                return
            }
            val t = now()
            val hasLive = synchronized(lock) {
                keys.entries.removeIf { t - it.value.bornMs >= KEY_TTL_MS }
                keys.isNotEmpty().also { if (!it) job = null }
            }
            if (!hasLive) {
                beforeExit()
                return
            }

            val waitOff = offUntil - now()
            if (waitOff > 0) {
                delay(waitOff)
                continue
            }

            val toRegister: Pair<Cluster, List<String>>? = synchronized(lock) {
                val nowRewatch = now()
                if (nowRewatch - lastRewatchMs >= REWATCH_MS) {
                    for (e in keys.values) e.registered = false
                    lastRewatchMs = nowRewatch
                }
                val first = keys.entries.firstOrNull { !it.value.registered }
                if (first == null) {
                    null
                } else {
                    val c = first.value.cluster
                    val batch = keys.entries
                        .filter { !it.value.registered && it.value.cluster == c }
                        .take(MAX_KEYS)
                        .map { it.key }
                    c to batch
                }
            }

            if (toRegister != null) {
                val (cluster, batch) = toRegister
                val body = buildJsonObject {
                    put("cluster", cluster.wire)
                    put("keys", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } })
                }.toString()
                val res = runCatching { http().request("$baseUrl/api/solana/watch", body) }.getOrNull()
                when (res?.first) {
                    200 -> synchronized(lock) {
                        for (k in batch) keys[k]?.registered = true
                    }
                    503 -> {
                        offUntil = now() + OFF_MS
                        continue
                    }
                    else -> {
                        delay(RETRY_MS)
                        continue
                    }
                }
                val moreUnregistered = synchronized(lock) { keys.values.any { !it.registered } }
                if (moreUnregistered) continue
            }

            val pollKeys = synchronized(lock) {
                val nowPoll = now()
                keys.entries.removeIf { nowPoll - it.value.bornMs >= KEY_TTL_MS }
                keys.entries
                    .sortedByDescending { it.value.bornMs }
                    .take(MAX_KEYS)
                    .map { it.key }
                    .also { if (it.isEmpty()) job = null }
            }
            if (pollKeys.isEmpty()) {
                beforeExit()
                return
            }

            val roundStart = now()
            val url = "$baseUrl/api/solana/seen?keys=${pollKeys.joinToString(",")}&wait=$WAIT_S"
            val seenRes = runCatching { http().request(url, null) }.getOrNull()
            if (seenRes == null) {
                delay(RETRY_MS)
                continue
            }
            val (code, text) = seenRes
            when (code) {
                200 -> {
                    val seenKeys = runCatching {
                        val arr = SolanaRpc.JSON.parseToJsonElement(text).jsonObject["seen"]?.jsonArray
                            ?: return@runCatching null
                        arr.mapNotNull { it.jsonObject["key"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
                    }.getOrNull()
                    if (seenKeys == null) {
                        delay(RETRY_MS)
                    } else if (seenKeys.isNotEmpty()) {
                        synchronized(lock) {
                            for (k in seenKeys) keys.remove(k)
                        }
                        onSeen()
                    } else {
                        val elapsed = now() - roundStart
                        if (elapsed < MIN_ROUND_MS) delay(MIN_ROUND_MS - elapsed)
                    }
                }
                503 -> offUntil = now() + OFF_MS
                else -> delay(RETRY_MS)
            }
        }
    }

    companion object {
        const val KEY_TTL_MS = 15 * 60_000L
        const val OFF_MS = 10 * 60_000L
        const val RETRY_MS = 60_000L
        const val REWATCH_MS = 5 * 60_000L
        const val MIN_ROUND_MS = 5_000L
        const val WAIT_S = 25
        const val MAX_KEYS = 16
    }
}
