package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.CoastBadge
import local.phantom.companion.core.nav.CoastFix
import local.phantom.companion.core.nav.CoastMode
import local.phantom.companion.core.nav.CoastState
import local.phantom.companion.core.nav.CoastedFix
import local.phantom.companion.core.nav.RouteCoastingEngine
import local.phantom.companion.core.nav.haversineM
import local.phantom.companion.core.net.map.RouteVariant

/**
 * ATLAS-2.5 §12 — the display pump's side of [RouteCoastingEngine].
 *
 * The engine is pure and blind; this is the one place that knows where the
 * frame's fix came from, which plan is live, and who has to hear about a
 * fabricated metre. [frame] is called once per display frame and answers the
 * only question the loop asks: does the engine own the puck this frame?
 *
 * The four rules of the engine's wiring contract live here and nowhere else:
 *  §1 [bind] follows the plan revision, and unbinds the moment the drive ends;
 *  §3 anything that is not a believed satellite fix is passed as `null`;
 *  §4 free roam passes `guidanceActive = false` and gets no puck back;
 *  §6 a puck is fed BACK into guidance as a navigation-grade, untrusted fix,
 *     so maneuver cues advance through the blackout while off-route, reroute
 *     and arrival stay frozen.
 */
internal class CoastPilot(
    private val guiding: () -> Boolean,
    private val planRevision: () -> Int,
    private val variant: () -> RouteVariant?,
    /** Contract §6. Called at most [FEED_INTERVAL_MS] apart, never for a live fix. */
    private val feedGuidance: (CoastedFix) -> Unit = {},
    /** verification-is-a-time-series — one line per second while guiding. */
    private val journal: ((String) -> Unit)? = null,
    private val engine: RouteCoastingEngine = RouteCoastingEngine(),
) {
    private var boundRevision = Int.MIN_VALUE
    private var boundVariant: RouteVariant? = null
    private var everBound = false
    private var lastFedMs = 0L
    private var lastJournalMs = 0L

    /** Чинний вирок про небо. `null` — ще жодного кадру не бачено. */
    private var sky: SkyBelief? = null

    /** Чи движок уже визнав свій пуск байкою — див. [SkyBelief.observe]. */
    private var coastExpired = false

    /** Намальована торік кадром точка коуста — мірило розбіжності з небом. */
    private var lastPuck: CoastedFix? = null

    fun frame(fix: LocationFix?, nowMs: Long): CoastState {
        val active = guiding()
        rebind(active)

        // ВИТРИМКА (див. [SkyBelief]). [believedCoastFix] — функція РІВНО
        // одного кадру: вона нічого не памʼятає, тож вирок «вірю / не вірю»
        // перемикався від кожного окремого зразка. У записі власника це 112
        // перемикань за 30 хв. Тепер сирий погляд неба лише ГОЛОСУЄ, а
        // володаря точки міняє тільки вистояний вирок.
        val sighted = believedCoastFix(fix)
        val steady = (sky ?: SkyBelief(sighted != null, 0L, nowMs))
            .observe(sighted != null, nowMs, coastExpired || farFromPuck(sighted))
        sky = steady
        // Контракт §3 НЕ послаблено: у движок їде або справжній зміряний фікс,
        // або нічого. Витримка вміє лише промовчати — вигадати не вміє.
        val believed = if (steady.believed) sighted else null
        val state = engine.update(believed, active, nowMs)
        coastExpired = state.mode == CoastMode.EXPIRED
        lastPuck = state.puck

        // Contract §6, and its «never twice for the same instant» half.
        //
        // The window is the PUMP's silence, not the coast's ownership, and the
        // two are not the same set. `NavigationEngine.onFix` returns early on
        // exactly one condition — a fix that is not navigation-grade — so that
        // condition, and only it, is when this path may speak. Gating on
        // [believedCoastFix] instead would double-feed across a whole state: a
        // fresh but degraded sky (grade NAVIGATION, accuracy past
        // [TRUSTED_ACCURACY_M]) is a denial to the COAST, which must not anchor
        // to it, while the pump is still handing guidance the measurement.
        val puck = state.puck
        val pumpSilent = fix == null || !fix.isNavigationGrade
        if (puck != null && pumpSilent && nowMs - lastFedMs >= FEED_INTERVAL_MS) {
            lastFedMs = nowMs
            feedGuidance(puck)
        }
        note(fix, skyWord(fix, sighted != null, steady), state, nowMs, active)
        return state
    }

    /**
     * ЖОРСТКИЙ поріг витримки — і причина, чому порогів саме два.
     *
     * Мʼякий поріг ([SKY_RETURN_HOLD_MS]) лікує смикання: розбіжність у
     * десятки метрів, з якої й складаються 112 перемикань і 43 клацання
     * запису. Але та сама витримка, прикладена до СПРАВЖНЬОЇ розбіжності,
     * зробила б гірше за хворобу: рушій в іншому записі прийняв сигнал попри
     * розбіжність 52 059 м з інерцією — тримати стару точку там означало б
     * лишити людину за 52 км від себе, і мовчати про це чотири секунди.
     *
     * Межа взята не зі стелі, а з числа, яким движок УЖЕ ділить ці два світи:
     * [RouteCoastingEngine.MERGE_MAX_GAP_M]. Вище нього движок сам відмовляє
     * сповзанню («це не злиття, а телепорт у сповільненій зйомці») і віддає
     * правду одним кадром. Пілот не має права придержувати те, що движок уже
     * визнав нездатним сповзти.
     *
     * Перевірка тим самим записом: медіана стрибка 202 м і 28 стрибків понад
     * 300 м лишаються ПІД межею — тобто в мʼякій смузі, де витримка й потрібна;
     * 6 стрибків понад 1 км і 4891-метровий максимум лишаються НАД нею. І,
     * головне, витримка не здатна перетнути власний жорсткий поріг: за
     * [SKY_RETURN_HOLD_MS] коуст встигає розійтись щонайбільше на 150 м
     * (4 с × 37,5 м/с), а це вчетверо менше за 400.
     */
    private fun farFromPuck(sighted: CoastFix?): Boolean {
        val held = lastPuck ?: return false
        if (sighted == null) return false
        return haversineM(held.lat to held.lon, sighted.lat to sighted.lon) > SKY_HARD_GAP_M
    }

    /**
     * Порожнеча — словом (і тим самим стовпчиком, яким заміряні 112).
     *
     * Журнал писав сирий погляд неба; тепер він пише ВИРОК, бо саме вирок
     * тепер вирішує, хто володіє точкою. Щоб наступний запис з телефона був
     * прямо порівнюваний зі старим, слова `trusted`/`held`/`null` лишились на
     * місці, а витримка додає до них суфікс — тож префікс і далі є вироком:
     *
     *  - `trusted`        — вірю, і це небо теж вірю;
     *  - `trusted-wobble` — вірю, але це небо вже ні: витримка сумніву спливає;
     *  - `trusted-dark`   — вірю, але цього кадру неба нема зовсім;
     *  - `held`           — не вірю, і це небо теж не вірю;
     *  - `held-proving`   — НЕ ВІРЮ, хоч це небо вже вірю: точку тримає коуст,
     *                       поки небо доводить повернення. Це і є та мить, у
     *                       якій витримка тримає стару позицію — і вона названа;
     *  - `null`           — не вірю, і неба нема.
     */
    private fun skyWord(fix: LocationFix?, sighted: Boolean, steady: SkyBelief): String = when {
        steady.believed && sighted -> "trusted"
        steady.believed && fix != null -> "trusted-wobble"
        steady.believed -> "trusted-dark"
        sighted -> "held-proving"
        fix != null -> "held"
        else -> "null"
    }

    /**
     * Контракт §1. Привʼязка НЕ безумовна: [RouteCoastingEngine.bind] скидає
     * стан, тож перепривʼязка тим самим варіантом щокадру вбивала б коустинг
     * рівно тоді, коли він потрібен. Тому — лише на зміну ревізії плану або
     * самого обʼєкта варіанта.
     */
    private fun rebind(active: Boolean) {
        val want = if (active) variant() else null
        val revision = planRevision()
        if (everBound && revision == boundRevision && want === boundVariant) return
        engine.bind(want)
        everBound = true
        boundRevision = revision
        boundVariant = want
        // [RouteCoastingEngine.bind] скидає движок; вирок про небо, що стосувався
        // вже неіснуючого коуста, мусить скинутись разом із ним, інакше новий
        // план успадкував би чужу витримку.
        sky = null
        coastExpired = false
        lastPuck = null
    }

    private fun note(
        fix: LocationFix?,
        word: String,
        state: CoastState,
        nowMs: Long,
        active: Boolean,
    ) {
        val log = journal ?: return
        if (!active || nowMs - lastJournalMs < JOURNAL_INTERVAL_MS) return
        lastJournalMs = nowMs
        val puck = state.puck
        val lat = puck?.lat ?: fix?.lat
        val lon = puck?.lon ?: fix?.lon
        val speed = puck?.speedMps ?: fix?.speedMps?.toDouble()
        log(
            buildString {
                append(System.currentTimeMillis()).append(',')
                append(word).append(',')
                append(state.mode.name).append(',')
                append(coord(lat)).append(',')
                append(coord(lon)).append(',')
                append(speed?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-")
            },
        )
    }

    private fun coord(v: Double?): String =
        v?.let { String.format(java.util.Locale.US, "%.6f", it) } ?: "-"

    companion object {
        /**
         * Контракт §3, сказаний у полях, які реально доживають до дисплея.
         *
         * `ConsistencyGate` не дотягнутий до feature-stream, але його вирок
         * доходить сюди штампами самої позиції: підміна й мережеве утримання
         * приходять COARSE+`dead`, відсутність фіксу — просто `null`, а
         * DEGRADED видає себе розпухлою похибкою. [TRUSTED_ACCURACY_M] — те
         * саме число, яким `NavFix.trusted` рахується для ведення, щоб коустинг
         * і ведення не сперечались про один і той самий метр.
         */
        internal fun believedCoastFix(fix: LocationFix?): CoastFix? {
            if (fix == null || !fix.isNavigationGrade || fix.dead) return null
            val accuracyM = fix.accuracyM ?: return null
            if (accuracyM > TRUSTED_ACCURACY_M) return null
            return CoastFix(
                lat = fix.lat,
                lon = fix.lon,
                speedMps = (fix.speedMps ?: 0f).toDouble(),
                bearingDeg = fix.bearing?.toDouble(),
            )
        }

        const val TRUSTED_ACCURACY_M = 150f

        /** NavigationEngine's own hot pump rate — guidance is not fed faster. */
        const val FEED_INTERVAL_MS = 250L

        const val JOURNAL_INTERVAL_MS = 1_000L

        val LIVE: CoastState = CoastState(CoastMode.NORMAL, null, CoastBadge.LIVE)
    }
}

/**
 * ВИТРИМКА НА ПЕРЕХІД «вірю небу ↔ не вірю небу».
 *
 * ЩО ЗЛАМАНО. [CoastPilot.believedCoastFix] дивиться рівно на один кадр і
 * нічого не памʼятає: похибка дихає навколо стелі [CoastPilot.TRUSTED_ACCURACY_M]
 * (150 м), і вирок перемикається від КОЖНОГО окремого зразка. У записі власника
 * (27.08, 1083 зразки по 1 Гц) це **112 перемикань за 30 хв — раз на 16 с**.
 * Кожне перемикання міняє володаря намальованої точки: `puck != null` — це
 * коридорна точка коуста, `puck == null` — сирий фікс воріт, а під час відмови
 * ці двоє розходяться рівно на стільки, на скільки бреше небо. Звідси й
 * «56% стрибків — рівно в мить перемикання».
 *
 * ГІРШЕ ЗА САМЕ МИГАННЯ — АСИМЕТРІЯ. Втрату довіри движок уже витримує сам:
 * [RouteCoastingEngine.ENTER_AFTER_MS] — 2,5 с, перш ніж вигадати перший метр.
 * А от ПОВЕРНЕННЯ довіри не витримане ніяк: один щасливий зразок посеред
 * відмови кличе `onMeasured`, обнуляє лічильник відмови й віддає точку назад
 * воротам. Незакрита була рівно ця половина.
 *
 * ЯК ЦЕ ЗРОБЛЕНО — діряве відро, одне число, обидва напрямки. [disputeMs]
 * накопичується, поки сире небо сперечається з чинним вироком, і ВИТРАЧАЄТЬСЯ
 * з тією ж швидкістю, поки згодне. Вирок міняється, коли незгода набрала свій
 * поріг. Симетрична витрата — не деталь, а протиотрута від застрягання:
 * якби один незгодний зразок обнуляв накопичене, небо з провалом раз на три
 * секунди не поверталося б НІКОЛИ, і точка застрягла б назавжди. Тут же
 * повертається все, що чесне БІЛЬШЕ ніж наполовину, як би не було переплетене.
 *
 * ТРИ МЕЖІ, ЗА ЯКИМИ ВИТРИМКА НЕ ДІЄ, — щоб вона ніколи не тримала правду:
 *  1. [CoastPilot.farFromPuck] — розбіжність більша за жорсткий поріг;
 *  2. `coastExpired` — движок сам визнав свій пуск байкою (бейдж WAITING);
 *  3. власні стелі движка [RouteCoastingEngine.MAX_COAST_MS] (45 с) та
 *     [RouteCoastingEngine.MAX_COAST_M] (1 км) — зовнішня огорожа, за яку
 *     ця витримка вплинути не може за побудовою.
 */
internal data class SkyBelief(
    /** Чинний вирок. Саме він, а не сирий кадр, вирішує, хто володіє точкою. */
    val believed: Boolean,
    /** Мілісекунди незгоди, накопичені проти чинного вироку. */
    val disputeMs: Long,
    /** Мить, на якій цей стан порахований: витримка йде по годиннику кадрів. */
    val atMs: Long,
) {
    fun observe(sighted: Boolean, nowMs: Long, urgent: Boolean): SkyBelief {
        val dt = (nowMs - atMs).coerceIn(0L, SKY_MAX_STEP_MS)
        if (sighted == believed) {
            return copy(disputeMs = (disputeMs - dt).coerceAtLeast(0L), atMs = nowMs)
        }
        val pending = disputeMs + dt
        val need = when {
            // Втрата довіри: див. [SKY_DOUBT_GRACE_MS].
            !sighted -> SKY_DOUBT_GRACE_MS
            // Повернення, яке не можна відкладати: жорсткий поріг або вичерпаний
            // коуст. Тримати стару точку тут — це і є «людина за 52 км від себе».
            urgent -> 0L
            else -> SKY_RETURN_HOLD_MS
        }
        return if (pending >= need) {
            SkyBelief(believed = sighted, disputeMs = 0L, atMs = nowMs)
        } else {
            copy(disputeMs = pending, atMs = nowMs)
        }
    }
}

/**
 * Скільки сумнів мусить протриматись, ПЕРШ НІЖ вирок перестане вірити небу.
 *
 * Не власне число, і навмисно: це [RouteCoastingEngine.ENTER_AFTER_MS] — та
 * сама мить, коли движок вирішує вигадати перший метр. Один орган не має права
 * мати другу думку про той самий сумнів, тож слово в журналі змінюється рівно
 * тоді, коли змінюється поведінка.
 *
 * Затримки коуста це не додає: у движок і під час витримки їде `null`
 * (контракт §3), тож його власний лічильник відмови біжить, як біг. Витримка
 * керує лише ВИРОКОМ — тим, проти чого міряється повернення.
 *
 * Перевірка записом: небо публікується раз на секунду, тож одиничний і
 * подвійний провал під витримку підпадають; а середня серія `held` у записі —
 * 6,6 с (368 зразків на ~56 епізодів), тобто справжня відмова утричі довша за
 * витримку й проходить її без запізнення.
 */
internal const val SKY_DOUBT_GRACE_MS = RouteCoastingEngine.ENTER_AFTER_MS

/**
 * МʼЯКИЙ поріг: скільки небо мусить бути чесним, перш ніж забрати точку в коуста.
 *
 * 4 000 мс — єдина тривалість, яку цей продукт уже ЗАМІРЯВ на записі власника
 * як межу читабельності (`TrustChip.ALARM_HOLD_MS`: 34 епізоди недовіри з 57
 * коротші за 4 с і всі разом важать 2,6% часу — тобто це спалахи, яких людина
 * не встигає прочитати). Той самий висновок тут: повернення неба, коротше за
 * 4 с, — не повернення.
 *
 * Дві фізичні межі, які це число мусить тримати, і тримає:
 *  — за 4 с коуст здатен розійтись із небом щонайбільше на 4 с × швидкість.
 *    150 м набігає аж на 37,5 м/с (135 км/год), а 150 м — це `FixFlux.TELEPORT_M`,
 *    поріг, за яким пружина не згладжує, а КЛАЦАЄ. Тобто витримка фізично не
 *    здатна написати те саме клацання, яке прибирає;
 *  — 150 м вчетверо менші за [SKY_HARD_GAP_M], тож мʼяка витримка не здатна
 *    сама себе перевести в жорстку смугу, а повернення завжди СПОВЗАЄ.
 */
internal const val SKY_RETURN_HOLD_MS = 4_000L

/**
 * ЖОРСТКИЙ поріг у метрах — вище нього витримки нема взагалі.
 * Виведення й перевірка записом — у [CoastPilot.farFromPuck].
 */
internal const val SKY_HARD_GAP_M = RouteCoastingEngine.MERGE_MAX_GAP_M

/**
 * Кадр, довший за це, — це втрачений драйв, а не пропущений кадр (застосунок
 * був у фоні). Записати всю паузу у витримку означало б перевернути вирок на
 * порожньому місці; те саме число й з тієї самої причини, що
 * [RouteCoastingEngine.MAX_TICK_S].
 */
internal const val SKY_MAX_STEP_MS = 5_000L

/**
 * Контракт §5 — коли двигун володіє точкою, малюється ВОНА, а не позиція
 * воріт. Похибка їде з puck'а і росте з кожним вигаданим метром, тож коло
 * сумніву дихає чесно; `dead` — бо позиція інерційна за визначенням.
 */
internal fun CoastedFix.asDisplayFix(): LocationFix = LocationFix(
    lat = lat,
    lon = lon,
    bearing = bearingDeg.toFloat(),
    accuracyM = uncertaintyM.toFloat(),
    speedMps = speedMps.toFloat(),
    grade = local.phantom.companion.core.ai.context.FixGrade.NAVIGATION,
    dead = true,
    register = local.phantom.companion.core.ai.context.FixRegister.DERIVED,
)
