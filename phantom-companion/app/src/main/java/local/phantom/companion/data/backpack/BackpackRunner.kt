package local.phantom.companion.data.backpack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.BuildConfig
import local.phantom.companion.core.net.backpack.Backpack
import local.phantom.companion.core.net.siren.SirenKeys
import local.phantom.companion.core.net.siren.SirenSnapshot
import local.phantom.companion.core.net.siren.SirenStore
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.EncryptedSlot
import local.phantom.companion.feature.stream.ui.map.OfflineBounds
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * «Рюкзак перед підвалом» (розд. 07 §7) — виконавець.
 *
 * Після тривоги в моїй області, за ~20 с і ≤ 2 МБ:
 *  1. свіжий підписаний знімок тривог (Сирена-маяк) — у [siren];
 *  2. забір сховку: відповіді мого ПК, листи, «де я» людей;
 *  3. мапа навколо мене — тим самим завантажувачем, що й «зберегти вид»
 *     ([local.phantom.companion.feature.stream.ui.map.OfflineRegionsManager]),
 *     під план [Backpack.plan].
 * Уночі на Wi-Fi і зарядці — повний пакет (лише Wi-Fi без ліміту).
 *
 * Стан — лише правда: що справді вдалося, що ні і чому. Скільки мапи
 * лягло — питаємо в завантажувача, а не в плану.
 */
class BackpackRunner(private val container: AppContainer) {

    private val slot = EncryptedSlot(container.applicationContext, FILE_NAME, durable = true)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val busy = AtomicBoolean(false)

    /** Останній перевірений знімок «Сирени-маяка», хоч би звідки він приїхав. */
    val siren = SirenStore(object : SirenStore.Slot {
        override fun read(): String? = slot.read(KEY_SIREN)
        override fun write(value: String) = slot.write(KEY_SIREN, value)
    })

    fun saved(): Backpack.State =
        slot.read(KEY_STATE)?.let { runCatching { json.decodeFromString<Backpack.State>(it) }.getOrNull() } ?: Backpack.State()

    private fun save(state: Backpack.State) = slot.write(KEY_STATE, json.encodeToString(state))

    /** Зібрати рюкзак. Друге прохання, поки збирається перше, — не друга збірка. */
    suspend fun pack(trigger: Backpack.Trigger, nowMs: () -> Long = System::currentTimeMillis): Backpack.State {
        if (!busy.compareAndSet(false, true)) return saved()
        try {
            val before = saved()
            var state = before.copy(
                trigger = trigger.name, startedAtMs = nowMs(), finishedAtMs = 0,
                siren = Backpack.Item("running"), relay = Backpack.Item("running"), tiles = Backpack.Item("running"),
            )
            save(state)
            val (sirenItem, relayItem) = coroutineScope {
                val s = async { withTimeoutOrNull(Backpack.QUICK_MS) { fetchSiren() } ?: Backpack.Item("failed", "не встигло за 20 с") }
                val r = async { pullRelay() }
                s.await() to r.await()
            }
            val (tilesItem, plan, region) = packTiles(trigger, before)
            state = state.copy(
                siren = sirenItem,
                relay = relayItem,
                tiles = tilesItem,
                plan = plan ?: before.plan,
                regionName = region ?: before.regionName,
                finishedAtMs = nowMs(),
                lastNightMs = if (trigger == Backpack.Trigger.NIGHT) nowMs() else before.lastNightMs,
            )
            save(state)
            return state
        } finally {
            busy.set(false)
        }
    }

    private suspend fun fetchSiren(): Backpack.Item = withContext(Dispatchers.IO) {
        val raw = runCatching { getSnapshot() }.getOrNull()
            ?: return@withContext Backpack.Item("failed", "сайт не віддав знімок тривог")
        when {
            siren.offer(raw) -> Backpack.Item("ok", "перевірено підписом", raw.size.toLong())
            SirenSnapshot.verify(raw, SirenKeys.PINNED) == null ->
                Backpack.Item("failed", if (SirenKeys.PINNED.isEmpty()) "підпис не перевірено — ключ тривог ще не закріплено" else "підпис не зійшовся — знімок відкинуто")
            else -> Backpack.Item("ok", "знімок уже є, не новіший", raw.size.toLong())
        }
    }

    private fun getSnapshot(): ByteArray? {
        val conn = URL("${BuildConfig.LICENSE_BASE_URL}/api/alerts/snapshot").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val b64 = JSONObject(text).optString("snapshot")
            if (b64.isEmpty()) return null
            return Base64.getDecoder().decode(b64 + "=".repeat((4 - b64.length % 4) % 4))
        } finally {
            conn.disconnect()
        }
    }

    private fun pullRelay(): Backpack.Item =
        if (runCatching { container.mesh.pollRelayNow() }.getOrDefault(false)) {
            Backpack.Item("ok", "сховок забирається: відповіді ПК, листи, «де я» людей")
        } else {
            Backpack.Item("failed", "сітка не запущена — сховок не забрано")
        }

    private suspend fun packTiles(trigger: Backpack.Trigger, before: Backpack.State): Triple<Backpack.Item, Backpack.TilePlan?, String?> {
        val fix = runCatching { container.locationProvider.current() }.getOrNull()
            ?: return Triple(Backpack.Item("skipped", "немає позиції — мапу навколо не взяти"), null, null)
        val old = before.plan
        val oldRegion = container.offlineRegions.regions.value.firstOrNull { it.name == before.regionName }
        if (trigger != Backpack.Trigger.NIGHT && old != null && old.contains(fix.lat, fix.lon) && oldRegion?.isComplete == true) {
            return Triple(Backpack.Item("ok", "мапа тут уже є", oldRegion.completedResourceSizeBytes), null, null)
        }
        val plan = Backpack.plan(fix.lat, fix.lon, trigger)
            ?: return Triple(Backpack.Item("skipped", "мапа не влазить у бюджет"), null, null)
        val bounds = OfflineBounds(plan.north, plan.east, plan.south, plan.west)
        val style = runCatching {
            local.phantom.companion.feature.stream.ui.map.offlineStyleUrlFor(
                container.mapPrefs.providerId.value, container.mapPrefs.apiKey.value, container.mapPrefs.customUrl.value,
            )
        }.getOrNull() ?: return Triple(Backpack.Item("failed", "немає стилю мапи"), plan, null)
        val name = PREFIX + local.phantom.companion.core.geo.place.PlaceNaming.forBox(
            northLat = plan.north, eastLon = plan.east, southLat = plan.south, westLon = plan.west,
        )
        val started = runCatching {
            container.offlineRegions.createRegion(
                name = name,
                bounds = bounds,
                styleUrl = style,
                minZoom = plan.minZoom.toDouble(),
                maxZoom = plan.maxZoom.toDouble(),
                // Після тривоги — будь-яка мережа: це останні секунди звʼязку.
                // Уночі — лише Wi-Fi без ліміту (і працівник запускається лише тоді).
                allowMetered = trigger != Backpack.Trigger.NIGHT,
                estimateBytes = plan.estimateBytes,
            )
        }.getOrDefault(false)
        return if (started) {
            Triple(Backpack.Item("ok", "мапа ~${plan.radiusM / 1000.0} км навколо, до z${plan.maxZoom}", 0), plan, name)
        } else {
            Triple(Backpack.Item("failed", "завантажувач мапи відмовив (місце на диску?)"), plan, null)
        }
    }

    /** Стан для показу — збережений плюс справжній прогрес мапи з завантажувача. */
    fun view(): JSONObject {
        val s = saved()
        val region = container.offlineRegions.regions.value.firstOrNull { it.name == s.regionName }
        val current = siren.current()
        val nowS = System.currentTimeMillis() / 1000
        fun item(i: Backpack.Item?) = i?.let { JSONObject().put("state", it.state).put("detail", it.detail).put("bytes", it.bytes) }
        return JSONObject()
            .put("trigger", s.trigger.ifEmpty { JSONObject.NULL })
            .put("started_at_ms", s.startedAtMs)
            .put("finished_at_ms", s.finishedAtMs)
            .put("last_night_ms", s.lastNightMs)
            .put("siren_item", item(s.siren) ?: JSONObject.NULL)
            .put("relay_item", item(s.relay) ?: JSONObject.NULL)
            .put("tiles_item", item(s.tiles) ?: JSONObject.NULL)
            .put("plan_estimate_bytes", s.plan?.estimateBytes ?: JSONObject.NULL)
            .put(
                "map",
                region?.let {
                    JSONObject()
                        .put("name", it.name.removePrefix(PREFIX))
                        .put("done", it.completedResourceCount)
                        .put("total", it.requiredResourceCount)
                        .put("bytes", it.completedResourceSizeBytes)
                        .put("complete", it.isComplete)
                        .put("active", it.active)
                } ?: JSONObject.NULL,
            )
            .put(
                "siren",
                current?.let {
                    JSONObject()
                        .put("verified", true)
                        .put("fresh", it.fresh(nowS))
                        .put("age_min", it.ageMinutes(nowS))
                        .put("oblasts", org.json.JSONArray(it.alerts.keys.toList()))
                } ?: JSONObject.NULL,
            )
    }

    companion object {
        const val FILE_NAME = "phantom_backpack"
        private const val KEY_STATE = "state"
        private const val KEY_SIREN = "siren"
        const val PREFIX = "Рюкзак · "
    }
}
