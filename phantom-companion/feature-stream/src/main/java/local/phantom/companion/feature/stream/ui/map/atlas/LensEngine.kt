package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS D5 — the Lens.
 *
 * Five tables and one evaluator. There is no `when (scene)` anywhere in the
 * evaluation: a new mode is rows in [LensRules], and a mode nobody wrote a row
 * for still resolves — it simply inherits the defaults. That is the whole
 * design constraint, and [LensEngineTest] is what holds it.
 *
 * Три таблиці кажуть, чому МОЖНА бути на екрані. Четверта ([SummonRule]) —
 * єдина, що каже, що МУСИТЬ там бути. Пʼята ([TrustVoiceRule]) не питає «чи
 * видно», а питає «наскільки голосно» — бо хром, який говорить однаково гучно
 * завжди, оператор перестає читати.
 *
 * The initial tables reproduce what the map does on the device TODAY, gate for
 * gate, so 0b's wiring changes nothing visible. Tightening them is ATLAS-2.
 */
internal class LensEngine(private val rules: LensRules = LensRules.DEFAULT) {

    /**
     * Гучність чипа довіри для цієї миті.
     *
     * [announcing] — вікно оголошення, яким володіє сам чип (зміна КЛАСУ, а не
     * вироку). Розгорнутий напис на екрані один: сирена старша за довіру, тож
     * поки вона голосна, оголошення тримається в короткій формі. Виняток один —
     * підміна координат під час ведення, і це рядок таблиці, а не гілка тут.
     */
    fun voice(input: TrustVoiceInput, announcing: Boolean = false): TrustVolume {
        val resting = rules.voice(input)
        if (resting == TrustVolume.EXPANDED) return resting
        // Приглушений стан не має права на оголошення: чотири секунди крику
        // про те, що не міняє дії, і є тим спамом, який власник зняв.
        if (resting == TrustVolume.SILENT && input.trust != TrustClass.CLEAR) return resting
        if (announcing && !input.alarmLoud) return TrustVolume.EXPANDED
        return resting
    }

    fun frame(input: LensInput): LensFrame {
        val caps = input.capabilities()
        val tiers = MapTier.entries.filterTo(LinkedHashSet()) { tier ->
            rules.tier(tier).admits(input, caps)
        }
        return LensFrame(
            scene = input.scene,
            band = input.band,
            tiers = tiers,
            budgets = DensityClass.entries.associateWith { rules.budget(it, input) },
            chrome = ChromeSlot.entries.filterTo(LinkedHashSet()) { slot ->
                rules.chrome(slot).admits(input, caps)
            },
            detailCaps = MapTier.entries.mapNotNull { tier ->
                rules.tier(tier).detailCapZoom?.let { tier to it }
            }.toMap(),
            summons = rules.summons(input, caps),
        )
    }
}

/**
 * When a tier is allowed on screen.
 *
 * `admits` reads: the scene is one of ours, every [requires] holds, no
 * [forbids] holds, and either the zoom band holds or something in [orWhen]
 * excuses it. [orWhen] exists for exactly one shape that keeps recurring —
 * «zoomed in far enough, OR the operator asked about this place» — and it
 * excuses the zoom band only, never a capability gate.
 */
internal data class TierRule(
    val tier: MapTier,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0,
    val scenes: Set<SceneMode> = SceneMode.ALL,
    val requires: Set<Capability> = emptySet(),
    val forbids: Set<Capability> = emptySet(),
    val orWhen: Set<Capability> = emptySet(),
    /** The source simply has no tiles above this; not a visibility gate. */
    val detailCapZoom: Double? = null,
) {
    fun admits(input: LensInput, caps: Set<Capability>): Boolean {
        if (input.scene !in scenes) return false
        if (!caps.containsAll(requires)) return false
        if (forbids.any { it in caps }) return false
        val zoomOk = input.zoom >= minZoom && input.zoom <= maxZoom
        return zoomOk || orWhen.any { it in caps }
    }
}

internal data class ChromeRule(
    val slot: ChromeSlot,
    val scenes: Set<SceneMode> = SceneMode.ALL,
    val requires: Set<Capability> = emptySet(),
    /** At least one must hold. Empty means «nothing to choose between». */
    val anyOf: Set<Capability> = emptySet(),
    val forbids: Set<Capability> = emptySet(),
) {
    fun admits(input: LensInput, caps: Set<Capability>): Boolean =
        input.scene in scenes &&
            caps.containsAll(requires) &&
            (anyOf.isEmpty() || anyOf.any { it in caps }) &&
            forbids.none { it in caps }
}

/**
 * Коли момент САМ кличе світовий шар на землю.
 *
 * Вирок гонтлету, блокер: «укриттів на мапі немає, хоча застосунок сам показує
 * тривогу». Плашка кричала, а шар укриттів чекав, поки жінка з дитиною на руках
 * знайде капсулу інструментів і натисне чип. Тому це рядок таблиці, а не гілка:
 * стан → шар, той самий механізм, що вирішує решту видимості.
 *
 * [kind] — id зі списку швидких шарів; шлях виклику прямий, без жодної LLM.
 */
internal data class SummonRule(
    val kind: String,
    val scenes: Set<SceneMode> = SceneMode.ALL,
    val requires: Set<Capability> = emptySet(),
    val forbids: Set<Capability> = emptySet(),
) {
    fun admits(input: LensInput, caps: Set<Capability>): Boolean =
        input.scene in scenes &&
            caps.containsAll(requires) &&
            forbids.none { it in caps }
}

/**
 * Наскільки голосно чип довіри говорить у цю мить. Перший збіг виграє.
 *
 * [afterMs] — єдиний час у правилі: рядок вмикається лише коли клас простояв
 * незмінним стільки. Так «пошепки» стає властивістю НУДЬГИ, а не таймера
 * десь у композабелі.
 */
internal data class TrustVoiceRule(
    val volume: TrustVolume,
    val classes: Set<TrustClass> = TrustClass.ALL,
    val scenes: Set<SceneMode> = SceneMode.ALL,
    val motions: Set<MotionClass> = MotionClass.ALL,
    val afterMs: Long = 0L,
) {
    fun admits(input: TrustVoiceInput): Boolean =
        input.trust in classes &&
            input.scene in scenes &&
            input.motion in motions &&
            input.heldMs >= afterMs
}

/** First match wins; no match means [LensFrame.UNLIMITED]. */
internal data class BudgetRule(
    val density: DensityClass,
    val limit: Int,
    val scenes: Set<SceneMode> = SceneMode.ALL,
    val motions: Set<MotionClass> = MotionClass.ALL,
)

internal class LensRules(
    tierRules: List<TierRule>,
    chromeRules: List<ChromeRule>,
    private val budgetRules: List<BudgetRule>,
    private val summonRules: List<SummonRule> = defaultSummonRules(),
    private val voiceRules: List<TrustVoiceRule> = defaultVoiceRules(),
) {
    private val tiers: Map<MapTier, TierRule> = tierRules.associateBy { it.tier }
    private val chrome: Map<ChromeSlot, ChromeRule> = chromeRules.associateBy { it.slot }

    init {
        val missingTier = MapTier.entries.filterNot { it in tiers }
        require(missingTier.isEmpty()) { "tiers without a rule: $missingTier" }
        val missingSlot = ChromeSlot.entries.filterNot { it in chrome }
        require(missingSlot.isEmpty()) { "chrome slots without a rule: $missingSlot" }
        // Виклик без умови — це мережевий запит на кожне відкриття мапи.
        val unconditional = summonRules.filter { it.requires.isEmpty() }.map { it.kind }
        require(unconditional.isEmpty()) { "summons with no condition: $unconditional" }
        // Стан без жодного рядка лишився б без голосу — тобто зник би з екрана
        // мовчки. Дешевше перебрати весь словник тут, ніж шукати це в полі.
        val voiceless = TrustClass.entries.flatMap { cls ->
            SceneMode.entries.flatMap { scene ->
                MotionClass.entries.map { TrustVoiceInput(cls, scene, it) }
            }
        }.filterNot { probe -> voiceRules.any { it.admits(probe) } }
        require(voiceless.isEmpty()) { "trust states with no voice rule: $voiceless" }
    }

    fun tier(tier: MapTier): TierRule = tiers.getValue(tier)

    fun chrome(slot: ChromeSlot): ChromeRule = chrome.getValue(slot)

    fun summons(input: LensInput, caps: Set<Capability>): Set<String> =
        summonRules.filter { it.admits(input, caps) }.mapTo(LinkedHashSet()) { it.kind }

    fun voice(input: TrustVoiceInput): TrustVolume =
        voiceRules.first { it.admits(input) }.volume

    fun budget(density: DensityClass, input: LensInput): Int {
        if (density in NEVER_BUDGETED) return LensFrame.UNLIMITED
        return budgetRules.firstOrNull {
            it.density == density && input.scene in it.scenes && input.motion in it.motions
        }?.limit ?: LensFrame.UNLIMITED
    }

    companion object {
        /**
         * Classes that describe the ground rather than compete on it, plus the
         * operator's own dot. Rationing these is how a map lies about where
         * you are or what the terrain does.
         */
        val NEVER_BUDGETED = setOf(
            DensityClass.BASEMAP,
            DensityClass.TERRAIN,
            DensityClass.WASH,
            DensityClass.OPERATOR,
            DensityClass.ROUTE,
            DensityClass.PEER,
            DensityClass.CHROME,
        )

        /** POI and transit vehicles below this are clutter, not information. */
        const val DETAIL_MIN_ZOOM = 13.0

        /** Indoor is a building-scale fact; below this there is no building. */
        const val INDOOR_MIN_ZOOM = 16.5

        /** RainViewer serves radar tiles to ~z10 and a placeholder above it. */
        const val RADAR_TILE_MAX_ZOOM = 10.0

        private val NOT_GUIDING = SceneMode.ALL - SceneMode.GUIDE

        /** Хода, за якої слова коштують водієві менше, ніж їх відсутність. */
        private val UNDER_WAY = MotionClass.ALL - MotionClass.STILL - MotionClass.UNKNOWN

        /**
         * Скільки незмінний стан лишається зі словом, поки телефон стоїть і
         * ніхто нікуди не веде. Далі — самий значок: «щось є» видно, читати
         * щоразу заново нічого.
         */
        const val QUIET_DIM_MS = 10_000L

        val DEFAULT: LensRules by lazy {
            LensRules(
                tierRules = defaultTierRules(),
                chromeRules = defaultChromeRules(),
                budgetRules = defaultBudgetRules(),
            )
        }

        private fun defaultTierRules(): List<TierRule> = listOf(
            TierRule(MapTier.GROUND_CARTOGRAPHY),
            TierRule(MapTier.LABEL_VOICE),
            TierRule(MapTier.GROUND_BACKFILL),
            TierRule(MapTier.GROUND_FACTS),
            TierRule(MapTier.HILLSHADE, requires = setOf(Capability.TERRAIN_3D)),
            TierRule(
                MapTier.RADAR,
                requires = setOf(Capability.RADAR_ON),
                detailCapZoom = RADAR_TILE_MAX_ZOOM,
            ),
            TierRule(MapTier.ALERT_WASH, requires = setOf(Capability.ALERTS_ON)),
            // Deliberately free of TERRAIN_3D: the hatch says «this ground is
            // not downloaded», which a licence may not be allowed to hide.
            TierRule(MapTier.COVERAGE_HATCH),
            TierRule(MapTier.EW_TOPOLOGY, requires = setOf(Capability.EW_TRAIL)),
            TierRule(MapTier.OPERATOR_PIN),
            TierRule(MapTier.SEARCH_PIN),
            TierRule(MapTier.ROUTE_LINE),
            TierRule(MapTier.ROUTE_REGISTER),
            TierRule(MapTier.MANEUVER_PINS),
            // Без зумових воріт і без режимних: людина, яка поділилась місцем,
            // не стає менш доречною від того, що камера відʼїхала, і тим паче
            // не гасне під сиреною — там вона і є питанням.
            TierRule(MapTier.PEER_FIGURES),
            // Без зумових воріт свідомо: доречність знаків подій вирішує
            // HazardGate над самими даними (ворота — зблизька або в коридорі
            // маршруту), і другі ворота тут лише ховали б те, що воротар уже
            // пропустив як варте екрана.
            TierRule(MapTier.EVENT_MARKS),
            // NOT zoom-gated, against the skeleton's own note: this tier is
            // the selected itinerary's ribbon, and a chosen route is never
            // hidden for standing too far back. The z13 rule the note meant
            // belongs to transit VEHICLES, which are still a Compose overlay.
            //
            // Гасне лише за кермом. У ЗАГРОЗІ лишається свідомо: якщо стрічка
            // на землі є, то це обраний маршрут людини, а забирати в неї план
            // під сиреною — не «розчистити екран», а стерти відповідь.
            TierRule(MapTier.TRANSIT, scenes = SceneMode.ALL - SceneMode.DRIVE),
            TierRule(MapTier.PHANTOM_LAYERS),
            TierRule(
                MapTier.POI,
                minZoom = DETAIL_MIN_ZOOM,
                orWhen = setOf(Capability.PINNED_PLACES),
            ),
            TierRule(MapTier.SAVED_PLACES),
            TierRule(
                MapTier.INDOOR_PLAN,
                minZoom = INDOOR_MIN_ZOOM,
                orWhen = setOf(Capability.DOSSIER_OPEN),
            ),
            TierRule(
                MapTier.BUILDING_EXTRUSION,
                requires = setOf(Capability.TERRAIN_3D),
                forbids = setOf(Capability.INDOOR_PRESENT),
            ),
            TierRule(MapTier.OFFLINE_PACKS),
        ) + MapTier.CHROME.map { tier ->
            // Guidance takes the screen: the tools panel is the one chrome
            // tier today's canvas already refuses to draw during a drive.
            if (tier == MapTier.CHROME_TOOLS) TierRule(tier, scenes = NOT_GUIDING)
            else TierRule(tier)
        }

        /**
         * Одне правило, і воно блокерське. Тривога в ОБЛАСТІ ОПЕРАТОРА, а не
         * «десь у країні»: киянка під сиреною має бачити укриття навколо себе,
         * а не дізнаватись, що гучно в Сумах. Поки тривога стоїть — вимога
         * стоїть; коли відбій — вимога зникає і шар доживає власний TTL сам.
         */
        internal fun defaultSummonRules(): List<SummonRule> = listOf(
            SummonRule(
                kind = local.phantom.companion.feature.stream.geo.MapGroundLayers.SHELTERS,
                requires = setOf(Capability.OWN_OBLAST_ALARM),
            ),
        )

        /**
         * Правильна річ у правильну мить, у порядку зверху вниз.
         *
         * Верхній рядок — тиша чистого неба. Нижній — замикання: невідомий
         * стан говорить словом, бо мовчати про невідоме гірше, ніж сказати
         * зайве. Між ними одна ідея: слово коштує уваги, тож його платять
         * там, де воно змінює дію — за кермом і на ходу.
         */
        internal fun defaultVoiceRules(): List<TrustVoiceRule> = listOf(
            TrustVoiceRule(TrustVolume.SILENT, classes = setOf(TrustClass.CLEAR)),
            // Власник 26.09: приблизне місце без маршруту не міняє жодної дії
            // людини — лише лякає. Слово лишається там, де воно міняє поворот.
            TrustVoiceRule(
                TrustVolume.SILENT,
                classes = setOf(TrustClass.DEGRADED, TrustClass.BLIND),
                scenes = NOT_GUIDING,
            ),
            // Підміна під час ведення — єдиний стан, що лишає гучну форму
            // назавжди: тут ціна пропущеного напису — не роздратування.
            TrustVoiceRule(
                TrustVolume.EXPANDED,
                classes = setOf(TrustClass.SPOOFED),
                scenes = setOf(SceneMode.GUIDE),
            ),
            TrustVoiceRule(TrustVolume.WORDED, scenes = setOf(SceneMode.GUIDE)),
            // За кермом слово не шепоче навіть на світлофорі: хода там уже
            // STILL, а машина нікуди не поділась, і саме там довіра до позиції
            // коштує найдорожче.
            TrustVoiceRule(TrustVolume.WORDED, scenes = setOf(SceneMode.DRIVE)),
            TrustVoiceRule(TrustVolume.WORDED, motions = UNDER_WAY),
            // Стоїмо, ведення немає, стан не мінявся десять секунд — шепіт.
            // Підміна не шепоче ніколи, навіть на паркінгу.
            TrustVoiceRule(
                TrustVolume.ICON,
                classes = TrustClass.ALL - TrustClass.SPOOFED,
                afterMs = QUIET_DIM_MS,
            ),
            TrustVoiceRule(TrustVolume.WORDED),
        )

        private fun defaultChromeRules(): List<ChromeRule> = listOf(
            // Transcribed from AlertBanner's own early return, and back in
            // step with it (map-honesty-05sep): the real gate returns early
            // only when the map knows no oblast, counts no alarm AND the sky
            // verdict is not standing on a silent feed. The third term is the
            // one that was missing — a NOFEED sky (cold start, feed never
            // read) is «не знаю», never «тихо», so the pill shows and stays
            // tappable. `LensInput.skyFeedSilent` carries the same
            // `SkyVerdict.feedSilent` the banner itself reads, so the mirror
            // cannot drift from it by transcription any more.
            //
            // `SKY_ABSENT` бʼє всі три: країна без стрічки тривог не має про що
            // мовчати, і саме це прочитав власник у Чехії як «не знаю».
            ChromeRule(
                ChromeSlot.ALERT_PILL,
                anyOf = setOf(
                    Capability.OWN_OBLAST_KNOWN,
                    Capability.ALARM_ANYWHERE,
                    Capability.SKY_UNKNOWN,
                ),
                forbids = setOf(Capability.SKY_ABSENT),
            ),
            ChromeRule(ChromeSlot.TRUST_CHIP),
            ChromeRule(ChromeSlot.MANEUVER_BANNER, scenes = setOf(SceneMode.GUIDE)),
            ChromeRule(ChromeSlot.ETA_STRIP, scenes = setOf(SceneMode.GUIDE)),
            ChromeRule(ChromeSlot.LOCATE_ORB),
            ChromeRule(ChromeSlot.COMMAND_PILL),
            ChromeRule(ChromeSlot.ZOOM_DECK, scenes = NOT_GUIDING),
            ChromeRule(ChromeSlot.BASEMAP_DECK),
            ChromeRule(ChromeSlot.LAYER_RAIL),
            ChromeRule(ChromeSlot.TOOLS_CAPSULE, scenes = NOT_GUIDING),
            ChromeRule(ChromeSlot.INDOOR_SWITCHER, requires = setOf(Capability.INDOOR_PRESENT)),
            ChromeRule(ChromeSlot.BOTTOM_SHEET),
            ChromeRule(ChromeSlot.ANSWER_BAR),
        )

        /**
         * Скільки символів кожного класу режим погоджується тримати.
         *
         * CALM з EXPLORE рядків не мають і не матимуть: вони і є «як сьогодні»,
         * а рядок-заглушка лише вдавав би, що про них ухвалили окреме рішення.
         *
         * [THREAT_PLACES] — не нуль, і це виправлення попередньої таблиці. Нуль
         * означав би, що під сиреною на мапі немає ні аптеки, ні криниці, ні
         * укриття — тобто рівно той блокер гонтлету, заради якого Лінза й
         * навчилась кликати шар укриттів. Стійкі місця лишаються; решту знімає
         * не бюджет, а те, що [local.phantom.companion.feature.stream.ui.map.PlaceLens]
         * узагалі не пускає їх у джерело.
         */
        const val DRIVE_PLACES = 12
        const val WALK_PLACES = 40
        const val TRANSIT_PLACES = 24
        const val THREAT_PLACES = 8

        private fun defaultBudgetRules(): List<BudgetRule> = listOf(
            BudgetRule(DensityClass.PLACE, THREAT_PLACES, scenes = setOf(SceneMode.THREAT)),
            BudgetRule(DensityClass.TRANSIT, 0, scenes = setOf(SceneMode.THREAT)),
            BudgetRule(DensityClass.ANNOTATION, 0, scenes = setOf(SceneMode.THREAT)),
            BudgetRule(DensityClass.PERSONAL, 6, scenes = setOf(SceneMode.THREAT)),
            BudgetRule(DensityClass.PLACE, DRIVE_PLACES, scenes = setOf(SceneMode.DRIVE)),
            BudgetRule(DensityClass.TRANSIT, 0, scenes = setOf(SceneMode.DRIVE)),
            BudgetRule(DensityClass.ANNOTATION, 6, scenes = setOf(SceneMode.DRIVE)),
            BudgetRule(DensityClass.PERSONAL, 12, scenes = setOf(SceneMode.DRIVE)),
            BudgetRule(DensityClass.PLACE, WALK_PLACES, scenes = setOf(SceneMode.WALK)),
            BudgetRule(DensityClass.STRUCTURE, 24, scenes = setOf(SceneMode.WALK)),
            BudgetRule(DensityClass.PLACE, TRANSIT_PLACES, scenes = setOf(SceneMode.TRANSIT)),
        )
    }
}
