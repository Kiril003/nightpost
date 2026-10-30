package local.phantom.companion.feature.stream.ui.map.world

import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.net.map.OpeningHours

/**
 * Світ обʼєктів мапи — одна таксономія на всі фігурки.
 *
 * Ранг читається зліва направо: безпека завжди перша, далі те, куди людина
 * йде, далі її люди, далі потреби, далі решта. Ранг — не смак: у тісноті
 * саме за ним вирішується, хто лишається на склі.
 */
internal enum class WorldTier { SAFETY, TARGET, PEOPLE, NEED, REST }

/**
 * Стани, які справді є в даних. Нічого поза цим списком фігурка не показує:
 * «ворота відчинені» ніхто не пише — тож і стану такого в воріт немає.
 */
internal enum class WorldState {
    /** `opening_hours` каже «працює зараз». */
    OPEN,

    /** `opening_hours` каже «зачинено зараз». */
    CLOSED,

    /** Свідчення або положення людини свіже. */
    FRESH,

    /** Свідчення старіє — ще правда, але вже не новина. */
    STALE,

    /** Тривога над цією областю — укриття стає головним на мапі. */
    ALERT,
}

/**
 * Пʼятнадцять видів. [minZoom] — з якого масштабу вид узагалі заслуговує на
 * місце; [states] — які стани в нього бувають у даних; [animates] — чи має
 * вид рух, що показує стан (лише укриття під тривогою).
 */
internal enum class WorldKind(
    val id: String,
    val tier: WorldTier,
    val minZoom: Double,
    val states: Set<WorldState>,
    val animates: Boolean = false,
) {
    DANGER("danger", WorldTier.SAFETY, 12.0, setOf(WorldState.FRESH, WorldState.STALE)),
    CRASH("crash", WorldTier.SAFETY, 13.0, setOf(WorldState.FRESH, WorldState.STALE)),
    CHECKPOINT("checkpoint", WorldTier.SAFETY, 12.0, setOf(WorldState.FRESH, WorldState.STALE)),
    SHELTER("shelter", WorldTier.SAFETY, 14.0, setOf(WorldState.OPEN, WorldState.CLOSED, WorldState.ALERT), animates = true),
    PERSON("person", WorldTier.PEOPLE, 0.0, setOf(WorldState.FRESH, WorldState.STALE)),
    HOSPITAL("hospital", WorldTier.NEED, 15.0, setOf(WorldState.OPEN, WorldState.CLOSED)),
    PHARMACY("pharmacy", WorldTier.NEED, 15.0, setOf(WorldState.OPEN, WorldState.CLOSED)),
    RESILIENCE("resilience", WorldTier.NEED, 15.0, setOf(WorldState.OPEN, WorldState.CLOSED)),
    FUEL("fuel", WorldTier.NEED, 15.0, setOf(WorldState.OPEN, WorldState.CLOSED)),
    ATM("atm", WorldTier.NEED, 16.0, setOf(WorldState.OPEN, WorldState.CLOSED)),
    STOP("stop", WorldTier.NEED, 15.5, emptySet()),
    POST("post", WorldTier.NEED, 16.0, setOf(WorldState.OPEN, WorldState.CLOSED)),
    LIFT_GATE("gate", WorldTier.REST, 16.5, emptySet()),
    GATE("swing_gate", WorldTier.REST, 16.5, emptySet()),
    CAMERA("camera", WorldTier.REST, 14.0, emptySet()),
    ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun of(id: String?): WorldKind? = BY_ID[id]

        /**
         * Вид місця з будь-якого джерела: Overpass (повні теги), пакет країни
         * (лише `cat`/`sub`), газетир, сховища. Свідчення — і пара
         * категорія/підкатегорія, і теги. `amenity=shelter` без заяви про
         * захист — навіс, не укриття.
         */
        fun ofPoi(poi: PoiEntity): WorldKind? {
            val sub = poi.subcategory?.lowercase().orEmpty()
            val tags = poi.tags
            fun has(key: String, vararg values: String) =
                tags[key] in values || (poi.category == key && sub in values)
            return when {
                tags["emergency"] == "shelter" || sub == "bomb_shelter" || tags["shelter_type"] == "bomb_shelter" ||
                    (sub == "shelter" && ShelterTruth.isProtective(tags)) -> SHELTER
                sub == "hospital" || sub == "clinic" || has("healthcare", "hospital", "clinic") -> HOSPITAL
                sub == "pharmacy" || has("healthcare", "pharmacy") -> PHARMACY
                sub == "fuel" -> FUEL
                sub == "atm" || (has("amenity", "bank") && tags["atm"] == "yes") -> ATM
                sub == "post_office" || sub == "parcel_locker" || has("amenity", "parcel_locker") ||
                    (has("amenity", "vending_machine") && "parcel" in tags["vending"].orEmpty()) -> POST
                sub in STOP_KEYS || has("highway", "bus_stop") || has("railway", "tram_stop") ||
                    has("public_transport", "platform", "stop_position") -> STOP
                tags["point:resilience"] != null || sub == "resilience_point" -> RESILIENCE
                sub == "lift_gate" -> LIFT_GATE
                sub == "gate" || sub == "swing_gate" -> GATE
                else -> null
            }
        }

        private val STOP_KEYS = setOf("bus_stop", "tram_stop", "stop_position", "platform")
    }
}

/**
 * Стан місця зі справжніх даних. [alertOverhead] — сирена над областю людини
 * зараз. Немає тега годин — немає і стану: «невідомо» чесніше за «відчинено».
 */
internal fun worldState(
    kind: WorldKind,
    tags: Map<String, String>,
    alertOverhead: Boolean,
    now: java.util.Calendar = java.util.Calendar.getInstance(),
): WorldState? {
    if (kind == WorldKind.SHELTER && alertOverhead) return WorldState.ALERT
    if (WorldState.OPEN !in kind.states) return null
    return when (OpeningHours.statusNow(tags["opening_hours"], now)) {
        is OpeningHours.Status.Open -> WorldState.OPEN
        is OpeningHours.Status.Closed -> WorldState.CLOSED
        else -> null
    }
}

/** Свідчення старіє за половину свого віку життя. */
internal fun freshness(ageMs: Long, lifeMs: Long): WorldState =
    if (ageMs <= lifeMs / 2) WorldState.FRESH else WorldState.STALE
