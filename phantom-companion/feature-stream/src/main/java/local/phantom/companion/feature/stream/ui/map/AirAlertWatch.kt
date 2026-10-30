package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix

/**
 * СИРЕНА-3 — небо, що переживає згортання застосунку.
 *
 * Опитування тривог жило всередині `MapViewModel`: щойно оператор виходив з
 * мапи — у чат, у кишеню — тривога для його області не доходила ніяк. Це
 * власник стану, а не екран: один на процес, на скоупі [AppContainer], з
 * власним темпом і власною чесністю щодо застарілих даних.
 *
 * Мапа тепер СПОЖИВАЧ: [MapLiveSession] дзеркалить ці потоки, а служба у
 * `:app` перетворює [notices] на сповіщення.
 */

/** Чи можна вірити знімку неба. UNKNOWN ≠ «тихо» — це «не знаю». */
enum class AlertFreshness { UNKNOWN, FRESH, STALE }

/**
 * Знімок неба. Несе ФАКТ — коли стрічку востаннє прочитали, — і не несе вироку
 * про власну свіжість.
 *
 * Поля `freshness` тут більше немає, і це не косметика. Воно рахувалось у мить
 * ПУБЛІКАЦІЇ і далі не старіло: у Doze `delay()` циклу опитування не тікає
 * (монотонний годинник спить разом із процесом), а стінний — тікає. Знімок,
 * записаний свіжим, лишався «свіжим» скільки завгодно довго, і відкат подвоєнь
 * розтягував наступний тік до 600 с — тобто до десяти хвилин слова «діє» над
 * мертвим каналом. Стан, який старіє від плину часу, не можна порахувати один
 * раз: його судить той, хто перемальовується.
 */
data class AlertSky(
    val oblasts: Map<String, AlertState> = emptyMap(),
    val ownOblast: String? = null,
    /** Момент ОСТАННЬОГО УСПІШНОГО читання стрічки; `null` — не читали жодного разу. */
    val fetchedAtMs: Long? = null,
    val detailLive: Boolean = false,
    /**
     * Чи оголошує пак під фіксом стрічку неба ВЗАГАЛІ.
     *
     * «Стрічки тут не існує» і «стрічка мовчить» — дві різні відсутності, і до
     * цього поля вони зливались в один `NOFEED`: власник у Чехії читав над
     * мапою «про тривоги не знаю» про країну, де питання не стоїть. Перше —
     * властивість місця, і мовчати про нього чесно; друге — властивість
     * каналу, і мовчати про нього не можна.
     *
     * Замовчування `true` — «питання не ставили»: жоден старий виклик
     * [AlertSky] не починає раптом ховати пігулку.
     */
    val feedDeclared: Boolean = true,
) {
    internal fun freshnessAt(nowMs: Long): AlertFreshness = skyFreshness(fetchedAtMs, nowMs)

    /**
     * Тривога в області оператора на момент погляду: `null` = невідомо.
     *
     * Мовчання мережі ніколи не читається як безпека. Протермінований «спокій»
     * повертається як `null`, а протермінована «тривога» лишається `true` —
     * помилка в бік обережності коштує зайвого погляду на екран, помилка в
     * інший бік коштує людини.
     *
     * `internal` навмисно: поза модулем це питання ставиться лише через
     * [verdict]. Прочитати саму лише тривогу й не спитати про стрічку — це і є
     * той дефект, через який домашній підсумок казав «тривога · Київська
     * область» над каналом, що мовчав сім годин.
     */
    internal fun ownActiveAt(nowMs: Long): Boolean? {
        val own = ownOblast ?: return null
        val fresh = freshnessAt(nowMs)
        if (fresh == AlertFreshness.UNKNOWN) return null
        val active = oblasts[own]?.active ?: return null
        return if (!active && fresh == AlertFreshness.STALE) null else active
    }
}

/**
 * Що правда дозволяє СКАЗАТИ про небо.
 *
 * Площа й слово розходяться навмисно. Піднята тривога не знімається мовчанням
 * каналу — пелена лишається (див. [AlertSky.ownActiveAt]). А от слово «діє» —
 * твердження про теперішній час, і стрічка, яка мовчить, його не підтверджує:
 * замість нього говориться, що стрічка мовчить.
 *
 * Один вирок на всіх, хто говорить: рядок шторки в `:app` і пігулка на мапі
 * беруть різні ресурси, але не мають права дійти різних висновків — саме так
 * шторка казала «стрічка мовчить», поки мапа поруч казала «почалась 7 год тому».
 */
enum class SkyVerdict {
    /** Тривога, стрічка свіжа. */
    ALERT,

    /** Тривога лишається піднятою, але стрічка мовчить. */
    ALERT_NOFEED,

    /** Тихо, стрічка свіжа. */
    QUIET,

    /** Знімок протермінований: «тихо» більше не свідчення. */
    STALE,

    /** Стрічку ще не читали. Вона тут Є — вона ще не озвалась. */
    NOFEED,

    /** Стрічка свіжа, але області оператора не знаємо. */
    NO_OBLAST,

    /**
     * Стрічки тривог для цієї країни не існує — і не зʼявиться.
     *
     * Не стан каналу, а властивість місця: пак під фіксом не оголошує неба
     * (`CountryAlarms.skyAvailable()`). Питання «чи є тривога» тут не має
     * джерела, тож воно й не ставиться — усі поверхні мовчать ЦІЛКОМ, а не
     * кажуть «не знаю». Правило власника з Чехії: «немає по цілій країні —
     * плашку не показувати взагалі».
     *
     * Тому [feedSilent] тут `false`: мовчазна стрічка — це те, чого чекають, а
     * тут чекати нема чого.
     */
    NO_COUNTRY_FEED,
    ;

    /**
     * Чи вирок стоїть на мовчазній стрічці. Питання оператора однакове на всіх
     * поверхнях, тож і відповідь одна — перелічувати гілки в композиції означало б
     * завести другий вирок під виглядом зручності.
     */
    val feedSilent: Boolean get() = this == ALERT_NOFEED || this == STALE || this == NOFEED
}

/**
 * Єдине рішення про слова, і єдиний вхід до знімка ззовні модуля.
 *
 * [nowMs] — це мить ПОГЛЯДУ, а не мить запису: слово «діє» стосується
 * теперішнього часу читача. Поріг живе в [SKY_STALE_AFTER_MS] і більше ніде:
 * другий підрахунок віку — це другий вирок, а два вироки на одному склі
 * оператор читає як несправність.
 */
fun AlertSky.verdict(nowMs: Long): SkyVerdict {
    // Перед будь-яким питанням про вік: у стрічки, якої не існує, віку немає.
    if (!feedDeclared) return SkyVerdict.NO_COUNTRY_FEED
    val fresh = freshnessAt(nowMs)
    return when (ownActiveAt(nowMs)) {
        true -> if (fresh == AlertFreshness.FRESH) SkyVerdict.ALERT else SkyVerdict.ALERT_NOFEED
        false -> when (fresh) {
            AlertFreshness.FRESH -> SkyVerdict.QUIET
            AlertFreshness.STALE -> SkyVerdict.STALE
            AlertFreshness.UNKNOWN -> SkyVerdict.NOFEED
        }
        // Невідома тривога зі СВІЖОЮ стрічкою — це незнання ДЕ ми, а не незнання
        // неба: сказати «дані застарілі» тут означало б звинуватити справний канал.
        null -> when (fresh) {
            AlertFreshness.FRESH -> SkyVerdict.NO_OBLAST
            AlertFreshness.STALE -> SkyVerdict.STALE
            AlertFreshness.UNKNOWN -> SkyVerdict.NOFEED
        }
    }
}

/**
 * Чи тримати свідчення про світло, воду, звʼязок і дороги в [oblast]: доки
 * небо не певне, що там тихо (область невідома, стрічка мовчить, тривога хоч у
 * районі), могла йти тривога — див. `Embargo` у `:core-truth`.
 */
fun AlertSky.holdsReportsAt(oblast: String?, nowMs: Long): Boolean {
    if (!feedDeclared) return false
    val state = oblast?.let { oblasts[it] }
    return freshnessAt(nowMs) != AlertFreshness.FRESH || state == null || state.active || state.districts.isNotEmpty()
}

/** Що саме сталося в області оператора. */
enum class AirAlertNoticeKind { RAISED, CLEARED }

/** Готова до показу подія — фраза вже зібрана [alarmPhraseUk] всередині модуля. */
data class AirAlertNotice(
    val oblast: String,
    val kind: AirAlertNoticeKind,
    val phrase: String,
    val atEpochMs: Long,
)

// ── Чисті рішення (тестуються без мережі, годинника і Android) ───────────────

/** Темп опитування, мс. */
internal fun skyPollDelayMs(
    ownAlertLive: Boolean,
    screenOn: Boolean,
    online: Boolean,
    consecutiveFailures: Int,
): Long {
    val base = when {
        ownAlertLive -> SKY_POLL_HOT_MS
        screenOn -> SKY_POLL_WARM_MS
        else -> SKY_POLL_COLD_MS
    }
    if (online && consecutiveFailures == 0) return base
    // Кожна невдача подвоює паузу: мовчазна мережа коштує радіо й відсотків
    // батареї, а інформації не приносить. Підлога для офлайну окремо — навіть
    // під живою тривогою немає сенсу довбати мертвий канал раз на 30 с.
    val backed = base shl consecutiveFailures.coerceIn(0, 4)
    val floor = if (online) base else SKY_POLL_OFFLINE_FLOOR_MS
    return backed.coerceAtLeast(floor).coerceAtMost(SKY_POLL_MAX_MS)
}

/** Вік останнього успішного запиту → довіра до знімка. */
internal fun skyFreshness(fetchedAtMs: Long?, nowMs: Long): AlertFreshness = when {
    fetchedAtMs == null -> AlertFreshness.UNKNOWN
    nowMs - fetchedAtMs <= SKY_STALE_AFTER_MS -> AlertFreshness.FRESH
    else -> AlertFreshness.STALE
}

/**
 * Злиття трьох стрічок в один знімок — перенесено з `MapLiveSession.fetchAlerts`
 * без змін логіки: ubilling лишається авторитетом щодо того, ХТО під тривогою,
 * siren.pp.ua додає типи/громади безкоштовно, а токенна стрічка уточнює текст.
 */
internal fun mergeSky(
    base: Map<String, AlertState>,
    siren: Map<String, SirenOblast>?,
    detailed: Map<String, AlertState>?,
): Map<String, AlertState> {
    var merged = base
    if (siren != null) {
        val out = merged.toMutableMap()
        siren.forEach { (oblast, s) ->
            val prev = out[oblast]
            if (prev != null) {
                out[oblast] = prev.copy(
                    threatType = if (prev.threatType == ThreatType.UNKNOWN) s.stateThreat else prev.threatType,
                    changedEpochMs = prev.changedEpochMs ?: s.startedEpochMs,
                    districts = s.districts,
                )
            } else if (s.stateActive || s.districts.isNotEmpty()) {
                // Прогалина в одній стрічці не має стирати правду іншої (АР Крим).
                out[oblast] = AlertState(
                    active = s.stateActive,
                    changedEpochMs = s.startedEpochMs,
                    threatType = s.stateThreat,
                    districts = s.districts,
                )
            }
        }
        merged = out
    }
    if (detailed != null) {
        merged = merged.mapValues { (oblast, state) ->
            val detail = detailed[oblast] ?: return@mapValues state
            state.copy(
                threatType = if (detail.threatType != ThreatType.UNKNOWN) detail.threatType else state.threatType,
                notes = detail.notes ?: state.notes,
                changedEpochMs = state.changedEpochMs ?: detail.changedEpochMs,
                districts = detail.districts.ifEmpty { state.districts },
            )
        }
    }
    return merged
}

/**
 * Воротар сповіщень: одна подія на один перехід і тільки для області оператора.
 *
 * Памʼятає ОСТАННІЙ просигналений стан, а не переходи: повторна «тривога» без
 * «відбою» між ними — це дрижання стрічки, а не друга тривога.
 */
internal class OwnOblastGate {
    private var signalled: Pair<String, AirAlertNoticeKind>? = null

    fun evaluate(
        ownOblast: String?,
        transitions: List<AlertTransition>,
        freshness: AlertFreshness,
    ): AirAlertNotice? {
        // Без фіксу область невідома — будити людину здогадкою не можна.
        if (ownOblast == null) return null
        if (freshness != AlertFreshness.FRESH) return null
        val t = transitions.lastOrNull { it.oblast == ownOblast } ?: return null
        val kind = when (t.kind) {
            AlertTransitionKind.STARTED -> AirAlertNoticeKind.RAISED
            AlertTransitionKind.ENDED -> AirAlertNoticeKind.CLEARED
        }
        val key = ownOblast to kind
        if (key == signalled) return null
        signalled = key
        return AirAlertNotice(
            oblast = t.oblast,
            kind = kind,
            phrase = alarmPhraseUk(t),
            atEpochMs = t.atEpochMs,
        )
    }
}

// ── Тримач ──────────────────────────────────────────────────────────────────

class AirAlertWatch(
    private val scope: CoroutineScope,
    /** Стрічки як лямбди, а не клієнти: тест підміняє мережу, не HTTP. */
    private val fetchBase: suspend () -> Map<String, AlertState>?,
    private val fetchSiren: suspend () -> Map<String, SirenOblast>? = { null },
    private val fetchDetail: suspend () -> Map<String, AlertState>? = { null },
    private val oblastAt: suspend (Double, Double) -> String? = { _, _ -> null },
    /**
     * Фікс → країна, і чи оголошує її пак небо взагалі.
     *
     * Активація пака й питання «чи є що читати» — той самий фікс, тож і шов
     * один: рознесені, вони колись спитали б до того, як активувати, і
     * оператор, що перетнув кордон, лишився б із чужим небом назавжди.
     */
    private val skyDeclared: (LocationFix?) -> Boolean = { true },
    private val locationSource: () -> LocationFix? = { null },
    private val online: () -> Boolean = { true },
    private val screenOn: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _sky = MutableStateFlow(AlertSky())
    val sky: StateFlow<AlertSky> = _sky.asStateFlow()

    private val _notices = MutableSharedFlow<AirAlertNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<AirAlertNotice> = _notices.asSharedFlow()

    private val _journal = MutableStateFlow<List<AlertTransition>>(emptyList())
    internal val journal: StateFlow<List<AlertTransition>> = _journal.asStateFlow()

    private val gate = OwnOblastGate()
    private var loop: Job? = null
    private var failures = 0

    /** Ідемпотентно: і служба, і мапа кличуть це, а крутиться один цикл. */
    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val ok = runCatching { pollOnce() }.getOrDefault(false)
                failures = if (ok) 0 else (failures + 1).coerceAtMost(FAILURE_CAP)
                delay(
                    skyPollDelayMs(
                        ownAlertLive = _sky.value.ownActiveAt(nowMs()) == true,
                        screenOn = runCatching { screenOn() }.getOrDefault(true),
                        online = runCatching { online() }.getOrDefault(true),
                        consecutiveFailures = failures,
                    ),
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Один цикл. `true` — тік відпрацював без збою каналу (звідси й темп). */
    internal suspend fun pollOnce(): Boolean {
        val fix = runCatching { locationSource() }.getOrNull()
        // За кордоном застарілий український знімок — не інформація про це небо.
        // Скидаємо його ЦІЛКОМ: порожній СВІЖИЙ знімок читався б як «тиша»,
        // а порожній UNKNOWN — як «не знаю», і пігулка мовчить.
        //
        // `true`, бо відсутність пака — не збій каналу: інакше відкат подвоєнь
        // розтягнув би тік до 10 хвилин, і водій, що вʼїхав з Польщі, стільки
        // ж лишався б без тривоги над собою.
        if (!runCatching { skyDeclared(fix) }.getOrDefault(true)) {
            _sky.value = AlertSky(feedDeclared = false)
            return true
        }
        val own = resolveOwnOblast(fix)
        // Офлайн і невдалий запит однакові: знімок лишається, але СТАРІЄ.
        // Обнулити тривоги через мовчання каналу — саме та відмова, що вбиває.
        if (!runCatching { online() }.getOrDefault(true)) return republish(own)
        val fresh = runCatching { fetchBase() }.getOrNull() ?: return republish(own)

        val siren = runCatching { fetchSiren() }.getOrNull()
        val detailed = runCatching { fetchDetail() }.getOrNull()
        val merged = mergeSky(fresh, siren, detailed)

        val now = nowMs()
        val transitions = diffAlerts(_sky.value.oblasts, merged, now)
        _sky.value = AlertSky(
            oblasts = merged,
            ownOblast = own,
            fetchedAtMs = now,
            detailLive = detailed != null,
        )
        if (transitions.isNotEmpty()) {
            _journal.value = (transitions + _journal.value).take(JOURNAL_MAX)
            gate.evaluate(own, transitions, _sky.value.freshnessAt(now))?.let { _notices.tryEmit(it) }
        }
        return true
    }

    // Знімок лишається, а старіє він сам: свіжість більше не поле, тож
    // перепублікація потрібна лише щоб оновити область оператора.
    //
    // `feedDeclared = true` тут обовʼязкове, а не косметичне: сюди доходить
    // лише той, хто щойно пройшов ворота `skyDeclared`, а копіюється знімок,
    // який міг бути закордонним. Без цього рядка водій, що вʼїхав з Польщі в
    // офлайні, лишився б із «стрічки тут не існує» над Волинню.
    private fun republish(own: String?): Boolean {
        _sky.value = _sky.value.copy(ownOblast = own, feedDeclared = true)
        return false
    }

    /**
     * Останній відомий фікс визначає область. Якщо фікс зник — тримаємо
     * попередню область: оператор не телепортувався, зник лише датчик.
     *
     * Живий фікс ПОЗА всіма областями — це відповідь, а не збій: область
     * чиститься. Тримати стару означало б «небо тихе · Київщина» як «своє»
     * небо в Кишиневі — рамка пака ширша за полігони областей.
     */
    private suspend fun resolveOwnOblast(fix: LocationFix?): String? {
        if (fix == null) return _sky.value.ownOblast
        return runCatching { oblastAt(fix.lat, fix.lon) }.getOrElse { return _sky.value.ownOblast }
    }

    private companion object {
        const val JOURNAL_MAX = 80
        const val FAILURE_CAP = 8
    }
}

/** Тривога в області оператора — відбій і уточнення загрози варті швидкості. */
internal const val SKY_POLL_HOT_MS = 30_000L

/** Екран увімкнено: людина дивиться, але небо над нею тихе. */
internal const val SKY_POLL_WARM_MS = 60_000L

/** Кишеня, тиша: телефон має пережити блекаут, а не виграти секунду. */
internal const val SKY_POLL_COLD_MS = 180_000L

/** Підлога для мертвого каналу. */
internal const val SKY_POLL_OFFLINE_FLOOR_MS = 120_000L

internal const val SKY_POLL_MAX_MS = 600_000L

/**
 * Після цього віку знімок більше не свідчить про безпеку.
 *
 * ЄДИНИЙ поріг давності стрічки в застосунку. Читачі («шторка», пігулка мапи,
 * оцінка ризику) не бачать його взагалі — вони беруть [AlertSky.verdict],
 * і завести другий такий самий сталий тут нікому: `SkyOneThresholdTest`
 * червоніє на дублікаті.
 */
internal const val SKY_STALE_AFTER_MS = 300_000L
