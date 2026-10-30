package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS D5 — what the Lens is asked, and what it answers.
 *
 * The question is one immutable value; the answer is one immutable value. No
 * booleans scattered across a state holder, no `Property.visibility` written
 * from wherever somebody noticed a problem. `MapUiState` keeps only what the
 * operator *intends*; whether a thing is on screen is derived here, or it is
 * not derived at all.
 */
internal enum class SceneMode {
    CALM,
    EXPLORE,
    GUIDE,

    // Reserved for ATLAS-2. They resolve today through the same table — the
    // rules simply do not single them out yet, which is the point: a mode is
    // rows, not branches.
    DRIVE,
    WALK,
    TRANSIT,
    THREAT,
    ;

    companion object {
        val ALL: Set<SceneMode> = entries.toSet()
    }
}

/** Coarse gait, from the kinematic classifier. */
internal enum class MotionClass {
    STILL,
    WALK,
    DRIVE,
    TRANSIT,
    UNKNOWN,
    ;

    companion object {
        val ALL: Set<MotionClass> = entries.toSet()
    }
}

/**
 * Яка земля зараз під мапою — три стани, бо стилів теж три.
 *
 * Це НЕ `SkyPhase`: там чотири імені й вони описують небо, а тут — те, що
 * вирішено намалювати, і форсована рейкою основа перебиває сонце цілком.
 * Золота година приходить сюди як [DAY], бо земля в ній денна.
 */
internal enum class DayPhase { DAY, DUSK, NIGHT }

/**
 * Named zoom bands so chrome and budget rules can talk in scales instead of
 * magic numbers. Tier rules still use exact zooms — 13.0 and 16.5 are real
 * thresholds somebody measured, not bands.
 */
internal enum class ZoomBand(val minZoom: Double) {
    WORLD(0.0),
    REGION(6.0),
    CITY(11.0),
    DISTRICT(13.0),
    STREET(16.0),
    BUILDING(18.0),
    ;

    companion object {
        fun of(zoom: Double): ZoomBand = entries.last { zoom >= it.minZoom }
    }
}

/**
 * A condition a rule may require or forbid. Every one is derived from
 * [LensInput] in exactly one place ([LensInput.capabilities]) — a rule can
 * never read a field the input does not carry.
 */
internal enum class Capability {
    TERRAIN_3D,
    ALERTS_ON,
    RADAR_ON,
    OWN_OBLAST_ALARM,

    /**
     * The operator's oblast is KNOWN — not that it is loud. ATLAS-0b read
     * `AlertBanner`'s own gate instead of trusting the skeleton's note: the
     * banner returns early only when there is neither a known oblast nor a
     * single alert in the country. A quiet, named oblast still gets a pill
     * that says so, and that is the state it spends most days in.
     */
    OWN_OBLAST_KNOWN,

    /** Somewhere in the country is loud, which the pill reports as a count. */
    ALARM_ANYWHERE,

    /**
     * Небо не підтверджене: стрічку ще НІ РАЗУ не читали (холодний старт),
     * знімок протермінований, або тривога стоїть над каналом, що замовк —
     * усе це `SkyVerdict.feedSilent` одним словом.
     *
     * Це «не знаю», а не «тихо», і `AirAlertWatch.kt` має на це власний закон:
     * мовчання мережі ніколи не читається як безпека. Тож пігулка мусить бути
     * на склі саме тут — і натискною, бо за нею живе маршрут до укриття, а
     * холодний старт — рівно та мить, коли він найпотрібніший (аудит
     * map-honesty-05sep, пункт 1).
     */
    SKY_UNKNOWN,

    /**
     * Стрічки тривог для цієї країни не існує (`SkyVerdict.NO_COUNTRY_FEED`).
     *
     * Заперечення, а не здатність: воно ЗАБОРОНЯЄ пігулку, і саме тому стоїть
     * окремо від [SKY_UNKNOWN]. Обидва — «нічого не сказано про небо», але
     * перший просить слова («ще не знаю, ось двері до укриття»), а цей просить
     * тиші, бо казати нема про що.
     */
    SKY_ABSENT,

    /**
     * A «поруч» result set is pinned. It survives any zoom today
     * (`MapPoiSearch`'s bbox auto-fetch stands down while one is pinned), so
     * the z13 place gate must excuse it or a flood found at city zoom would
     * blink out the moment 0b wired the Lens.
     */
    PINNED_PLACES,
    COUNTRY_PACK,
    DOSSIER_OPEN,
    INDOOR_PRESENT,
    EW_TRAIL,
    MOVING,
    NIGHT,
    DUSK,
}

/** Everything the Lens is allowed to know. */
internal data class LensInput(
    val scene: SceneMode = SceneMode.CALM,
    val zoom: Double = 12.0,
    val motion: MotionClass = MotionClass.UNKNOWN,
    /** Is the operator's OWN oblast under alarm — not «somewhere is loud». */
    val ownOblastAlarm: Boolean = false,
    /** Does the map know which oblast the operator stands in at all. */
    val ownOblastKnown: Boolean = false,
    /** How many oblasts are loud right now, own included. */
    val activeAlarmCount: Int = 0,
    /**
     * Вирок про небо стоїть на мовчазній стрічці — `SkyVerdict.feedSilent`,
     * знятий рівно тим самим викликом `verdict()`, що й слова пігулки. Не
     * «тихо»: «не знаю». Замовчування `false` — це «питання не ставили», а не
     * «стрічка свіжа», тож жоден старий виклик не починає раптом брехати.
     */
    val skyFeedSilent: Boolean = false,
    /**
     * Пак під фіксом оголошує стрічку неба. `false` — країна без тривог, і
     * пігулка мовчить ЦІЛКОМ, як і `AlertBanner`'s own early return.
     */
    val skyFeedDeclared: Boolean = true,
    /** A «поруч» flood is pinned; it outlives the zoom that found it. */
    val pinnedPlaces: Boolean = false,
    /** The operator's toggle, not the sky's state. */
    val alertsVisible: Boolean = true,
    val radarVisible: Boolean = false,
    val dayPhase: DayPhase = DayPhase.DAY,
    val indoorPlanPresent: Boolean = false,
    val dossierOpen: Boolean = false,
    /** A CountryPack for the ground under the viewport (D6). Absent abroad. */
    val countryPackPresent: Boolean = false,
    val terrainEntitlement: Boolean = false,
    /**
     * The EW trail. The layers are installed and the recorder still writes
     * crumbs for the journal, but the ground has been cleared of them by the
     * owner's own verdict — so the tier exists and the Lens never lights it.
     * Bringing it back is this flag, not a code change.
     */
    val ewTrailEnabled: Boolean = false,
) {
    val band: ZoomBand get() = ZoomBand.of(zoom)

    fun capabilities(): Set<Capability> = buildSet {
        if (terrainEntitlement) add(Capability.TERRAIN_3D)
        if (alertsVisible) add(Capability.ALERTS_ON)
        if (radarVisible) add(Capability.RADAR_ON)
        if (ownOblastAlarm) {
            // You cannot know your own oblast is loud without knowing which
            // oblast it is, and it is itself one of the loud ones. Deriving
            // both here keeps a rule from having to restate the obvious.
            add(Capability.OWN_OBLAST_ALARM)
            add(Capability.OWN_OBLAST_KNOWN)
            add(Capability.ALARM_ANYWHERE)
        }
        if (ownOblastKnown) add(Capability.OWN_OBLAST_KNOWN)
        if (activeAlarmCount > 0) add(Capability.ALARM_ANYWHERE)
        if (skyFeedSilent) add(Capability.SKY_UNKNOWN)
        if (!skyFeedDeclared) add(Capability.SKY_ABSENT)
        if (pinnedPlaces) add(Capability.PINNED_PLACES)
        if (countryPackPresent) add(Capability.COUNTRY_PACK)
        if (dossierOpen) add(Capability.DOSSIER_OPEN)
        if (indoorPlanPresent) add(Capability.INDOOR_PRESENT)
        if (ewTrailEnabled) add(Capability.EW_TRAIL)
        if (motion != MotionClass.STILL && motion != MotionClass.UNKNOWN) add(Capability.MOVING)
        when (dayPhase) {
            DayPhase.NIGHT -> add(Capability.NIGHT)
            DayPhase.DUSK -> add(Capability.DUSK)
            DayPhase.DAY -> Unit
        }
    }
}

/**
 * Голос чипа довіри — не «видно / не видно», а НАСКІЛЬКИ голосно.
 *
 * Власник дивився на дві рядкові плашки вгорі мапи, поки телефон лежав у
 * приміщенні: «вони гігантські і постійно ними спамить, особливо коли нічого
 * не відбувається». Чесність лишається, гучність падає: кільце сумніву на
 * землі вже несе просторову правду, і словам не треба кричати весь час.
 */
internal enum class TrustVolume {
    /** Не композиться нічого. Чисте небо — це тиша. */
    SILENT,

    /** Лише значок: «щось є» видно, але пошепки. */
    ICON,

    /** Значок і одне-два слова — форма для того, хто за кермом. */
    WORDED,

    /** Повне речення. Таких написів на екрані не буває двох. */
    EXPANDED,
}

/**
 * Клас — це ідентичність РЕЧЕННЯ, а не вирок движка.
 *
 * Розгортання завʼязане саме на клас, бо вирок дихає: у знятому епізоді
 * TRUSTED↔DEGRADED мигало кожні ~6 с на телефоні, який лежав нерухомо. Клас
 * міняється рідко — отже, і речення теж.
 */
internal enum class TrustClass {
    CLEAR,
    DEGRADED,
    JAMMED,
    SPOOFED,
    COASTING,
    REJOINING,
    BLIND,
    ;

    companion object {
        val ALL: Set<TrustClass> = entries.toSet()

        /**
         * Класи, після яких повернення неба варте окремого слова. [REJOINING]
         * сюди не входить: «сигнал повернувся» вже сказано ним самим, і друге
         * речення підряд — це той самий спам, тільки з добрими новинами.
         */
        val HARD: Set<TrustClass> = setOf(JAMMED, SPOOFED, COASTING, BLIND)
    }
}

/** Усе, що таблиця голосу має право знати. */
internal data class TrustVoiceInput(
    val trust: TrustClass,
    val scene: SceneMode = SceneMode.CALM,
    val motion: MotionClass = MotionClass.UNKNOWN,
    /** Скільки клас не мінявся — єдиний час, який бачить таблиця. */
    val heldMs: Long = 0L,
    /** Сирена вже тримає розгорнутий слот угорі. */
    val alarmLoud: Boolean = false,
)

/**
 * The Lens's whole answer for one moment. Every visibility question on the map
 * is answered by reading this — «порожній екран — легітимний стан» is just an
 * empty [tiers].
 */
internal data class LensFrame(
    val scene: SceneMode,
    val band: ZoomBand,
    val tiers: Set<MapTier>,
    val budgets: Map<DensityClass, Int>,
    val chrome: Set<ChromeSlot>,
    /** Tiers whose source has no data above the given zoom (radar's ~z10). */
    val detailCaps: Map<MapTier, Double>,
    /**
     * Світові шари, яких цей момент ВИМАГАЄ на землі — не «дозволяє», а саме
     * вимагає, тож рука тут не оператора. Порожньо в спокійний день; поки
     * вимога стоїть, шар тримають живим, а щойно вона зникла — його забирає
     * власний TTL, без окремого «прибрати».
     */
    val summons: Set<String> = emptySet(),
) {
    fun lit(tier: MapTier): Boolean = tier in tiers

    fun budget(density: DensityClass): Int = budgets[density] ?: UNLIMITED

    fun allows(slot: ChromeSlot): Boolean = slot in chrome

    fun detailCap(tier: MapTier): Double? = detailCaps[tier]

    companion object {
        const val UNLIMITED = Int.MAX_VALUE

        /** The honest empty frame — nothing lit, nothing allowed. */
        val DARK = LensFrame(
            scene = SceneMode.CALM,
            band = ZoomBand.WORLD,
            tiers = emptySet(),
            budgets = emptyMap(),
            chrome = emptySet(),
            detailCaps = emptyMap(),
            summons = emptySet(),
        )
    }
}
