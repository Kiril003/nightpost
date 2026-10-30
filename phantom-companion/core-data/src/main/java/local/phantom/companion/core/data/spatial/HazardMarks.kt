package local.phantom.companion.core.data.spatial

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NAV-5 — the ONE code path for manual hazard marks (патруль / ДТП /
 * камера…): the operator's finger on the long-press sheet and
 * PHANTOM's `world.report` verb both land here (architecture rule 2 —
 * one capability, two hands). Marks live on a single self-expiring
 * «позначки на мапі» layer; a repeat report within ~20 m replaces rather
 * than stacks.
 *
 * ATLAS-4 adds a THIRD hand — the live plane, syncing marks between
 * operators. It writes through the same door (batch form below) and its
 * marks carry [HAZARD_SOURCE_COMMUNITY]: a synced mark is still exactly
 * what someone reported, never a verified fact (Law 1).
 */

/** The default lifetime of a manual mark — honesty fades. */
const val HAZARD_MARK_DEFAULT_TTL_MIN = 120

/** Layer `kind` shared by every writer + the map's ThreatWatch reader. */
const val HAZARD_LAYER_KIND = "hazard"

/** Layer title — one constant, because three writers now push this layer. */
const val HAZARD_LAYER_TITLE = "позначки на мапі"

/** Provenance of a mark from another operator. A local self-report carries no
 *  source at all — absence IS «це моє». */
const val HAZARD_SOURCE_COMMUNITY = "спільнота"

/** Merge radius: a repeat report this close is the same hazard, not a second one. */
const val HAZARD_MERGE_RADIUS_M = 20.0

data class HazardMarkResult(val layerId: String, val markCount: Int)

// Три руки (палець, world.report, жива площина) читають-зливають-переписують
// ОДИН шар. Без спільного замка паралельний запис губить чужу мітку —
// realtime підняв частоту записів з ~1/30с до 1/insert і розкрив вікно.
private val hazardLayerGate = Mutex()

/** One incoming report, whoever's hand it came from. */
data class HazardMarkInput(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val note: String? = null,
    val source: String? = null,
)

/**
 * Що ніхто не може поставити на мапу — ні пальцем, ні голосом, ні з чужого
 * пристрою: блокпости, патрулі, позиції, влучання. Мітки з цього шару їдуть іншим людям,
 * тож такий вид — це збір того, що видає людей ворогу.
 */
fun isRefusedMarkKind(kind: String): Boolean {
    val k = kind.lowercase()
    if (hazardMarkClass(k) == MARK_CLASS_CHECKPOINT) return true
    // «лежачий поліцейський» is a speed bump, not police.
    val bump = "лежач" in k
    return REFUSED_MARK_STEMS.any { it in k && !(bump && it == "поліц") }
}

private val REFUSED_MARK_STEMS = listOf(
    "блокпост", "блокпіст", "кпп", "checkpoint", "патрул", "patrol", "поліц", "екіпаж", "військ", "позиці", "окоп", "бліндаж",
    "ппо", "military", "troop", "приліт", "прильот", "влучан", "вибух", "обстріл", "shelling", "strike",
)

class RefusedMarkKind(kind: String) : IllegalArgumentException("вид «$kind» на мапу не ставиться")

suspend fun SpatialLayerSink.pushHazardMark(
    kind: String,
    lat: Double,
    lon: Double,
    note: String? = null,
    ttlMin: Int = HAZARD_MARK_DEFAULT_TTL_MIN,
    threadId: String? = null,
): HazardMarkResult = if (isRefusedMarkKind(kind)) throw RefusedMarkKind(kind) else checkNotNull(
    // keepExisting = false with one input — this branch always writes something.
    pushHazardMarks(
        listOf(HazardMarkInput(kind = kind, lat = lat, lon = lon, note = note)),
        ttlMin = ttlMin,
        threadId = threadId,
    ),
)

/**
 * The batch form. Exists for the live plane: one pull brings back a dozen
 * marks, and pushing them one by one would rewrite the whole layer a dozen
 * times over.
 *
 * [keepExisting] flips who wins inside the merge radius. A fresh self-report
 * replaces the stale mark it lands on (default); a synced mark must NOT —
 * otherwise the operator's own report is silently relabelled as someone
 * else's. Returns null when nothing new landed.
 */
suspend fun SpatialLayerSink.pushHazardMarks(
    inputs: List<HazardMarkInput>,
    ttlMin: Int = HAZARD_MARK_DEFAULT_TTL_MIN,
    threadId: String? = null,
    keepExisting: Boolean = false,
): HazardMarkResult? {
    val allowed = inputs.filterNot { isRefusedMarkKind(it.kind) }
    if (allowed.isEmpty()) return null
    return mutateHazardLayer(threadId) { current ->
        val kept = current.toMutableList()
        val added = mutableListOf<SpatialMarker>()
        for (input in allowed) {
            val near = { m: SpatialMarker -> hazardDistanceM(m.lat, m.lon, input.lat, input.lon) <= HAZARD_MERGE_RADIUS_M }
            if (keepExisting && (kept.any(near) || added.any(near))) continue
            kept.removeAll(near)
            added.removeAll(near)
            added += hazardMarkerOf(input)
        }
        if (added.isEmpty()) null else (kept + added) to ttlMin
    }
}

/**
 * Атомарний read-modify-write шару міток: замок тримається через ОБИДВІ фази.
 * Читання поза замком + запис під ним — це та сама гонка, лише повільніша:
 * мітка з пальця, що впала між ними, гине. [edit] отримує поточні маркери і
 * повертає (нові маркери, ttlMin) або null = нічого не міняти.
 */
suspend fun SpatialLayerSink.mutateHazardLayer(
    threadId: String? = null,
    edit: (List<SpatialMarker>) -> Pair<List<SpatialMarker>, Int>?,
): HazardMarkResult? = hazardLayerGate.withLock {
    val current = hazardLayer(threadId)?.spec?.markers.orEmpty()
    val result = edit(current) ?: return@withLock null
    rewriteHazardLayerUnlocked(result.first, result.second, threadId)
}

/** The current hazard layer, if the operator has one. */
suspend fun SpatialLayerSink.hazardLayer(threadId: String? = null): SpatialLayer? =
    runCatching { list(threadId) }.getOrDefault(emptyList())
        .firstOrNull { it.spec.origin == LayerOrigin.WORLD && it.spec.kind == HAZARD_LAYER_KIND }

/**
 * Replaces the hazard layer with exactly [markers]. The one place that knows
 * the layer's shape — a second writer inventing its own title/colour would
 * split the layer in two and the map would draw both.
 *
 * An empty list clears the layer instead of pushing (the store rejects empty
 * specs by design).
 */
suspend fun SpatialLayerSink.rewriteHazardLayer(
    markers: List<SpatialMarker>,
    ttlMin: Int = HAZARD_MARK_DEFAULT_TTL_MIN,
    threadId: String? = null,
): HazardMarkResult? = hazardLayerGate.withLock { rewriteHazardLayerUnlocked(markers, ttlMin, threadId) }

private suspend fun SpatialLayerSink.rewriteHazardLayerUnlocked(
    markers: List<SpatialMarker>,
    ttlMin: Int,
    threadId: String?,
): HazardMarkResult? {
    val existing = hazardLayer(threadId)
    existing?.let { runCatching { clear(it.id) } }
    if (markers.isEmpty()) return null
    val layerId = push(
        SpatialLayerSpec(
            title = HAZARD_LAYER_TITLE,
            origin = LayerOrigin.WORLD,
            kind = HAZARD_LAYER_KIND,
            // Нитка успадковується від шару, який переписуємо: інакше писар без
            // нитки (жива площина) мовчки знімав би шар із його розмови.
            ownerThreadId = threadId ?: existing?.spec?.ownerThreadId,
            markers = markers,
            color = "#F97316",
            glyph = "позначки",
            ttlMs = ttlMin.coerceIn(5, 24 * 60) * 60_000L,
        ),
    )
    return HazardMarkResult(layerId, markers.size)
}

fun hazardMarkerOf(input: HazardMarkInput): SpatialMarker = SpatialMarker(
    lat = input.lat,
    lon = input.lon,
    label = input.kind,
    glyph = input.kind,
    color = hazardColorFor(input.kind),
    detail = input.note ?: input.source ?: "самозвіт оператора",
    source = input.source,
)

/**
 * Шість класів знаків на мапі. Це РЕНДЕР-таксономія, не схема: у шарі й далі
 * лежить вільний рядок («ДТП», «ремонт», «gate»), бо його пишуть три руки —
 * палець, world.report і жива площина, — і жодна з них не зобовʼязана знати
 * наш enum. Класифікатор лишається сумісним назад: старий рядок мусить
 * розвʼязатись у той самий клас, інакше вчорашні мітки в шарі стануть
 * безіменними.
 */
const val MARK_CLASS_GATE = "gate"
const val MARK_CLASS_CHECKPOINT = "checkpoint"
const val MARK_CLASS_CRASH = "crash"
const val MARK_CLASS_CAMERA = "camera"
const val MARK_CLASS_SHELTER = "shelter"
const val MARK_CLASS_DANGER = "danger"

val MARK_CLASSES: List<String> = listOf(
    MARK_CLASS_GATE, MARK_CLASS_CHECKPOINT, MARK_CLASS_CRASH,
    MARK_CLASS_CAMERA, MARK_CLASS_SHELTER, MARK_CLASS_DANGER,
)

/**
 * Клас знака за видом мітки з шару. Голки малими — рядок з аркуша приходить
 * і «ДТП», і «дтп»; латиниця тут тому, що world.report приймає вільний kind.
 * Невідоме — [MARK_CLASS_DANGER]: «щось не так у цій точці» це найслабше, що
 * ми маємо право сказати, і воно не бреше про клас.
 */
fun hazardMarkClass(kind: String): String {
    val k = kind.lowercase()
    return when {
        k.contains("блокпост") || k.contains("кпп") || k.contains("checkpoint") -> MARK_CLASS_CHECKPOINT
        k.contains("шлагбаум") || k.contains("ворота") || k.contains("gate") -> MARK_CLASS_GATE
        k.contains("дтп") || k.contains("аварі") || k.contains("crash") || k.contains("accident") ->
            MARK_CLASS_CRASH
        k.contains("камер") || k.contains("camera") -> MARK_CLASS_CAMERA
        k.contains("укритт") || k.contains("сховищ") || k.contains("shelter") -> MARK_CLASS_SHELTER
        else -> MARK_CLASS_DANGER
    }
}

/**
 * Клас знака за тегом `barrier=*` з OSM. Розділення тут не косметичне: правило
 * проріджування воріт (z ≥ 16.5) діє лише на [MARK_CLASS_GATE], тож пункт
 * пропуску чи оплати, що поїхав би сюди воротами, зникав би з екрана разом із дворовими.
 */
fun barrierMarkClass(osmKind: String): String = when (osmKind.lowercase()) {
    "checkpoint", "border_control", "toll_booth" -> MARK_CLASS_CHECKPOINT
    else -> MARK_CLASS_GATE
}

fun hazardColorFor(kind: String): String {
    // Порівняння йде з малих літер, бо голки нижче — малими. Аркуш мапи
    // надсилає «ДТП» великими, тож саме аварія — єдина з усіх — не збігалася
    // ні з чим і отримувала загальний помаранчевий замість червоного.
    val k = kind.lowercase()
    return when {
        k.contains("патр") -> "#3B82F6"
        k.contains("дтп") || k.contains("аварі") -> "#EF4444"
        k.contains("ремонт") || k.contains("затор") -> "#F59E0B"
        k.contains("камер") -> "#EF4444"
        else -> "#F97316"
    }
}

fun hazardDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}
