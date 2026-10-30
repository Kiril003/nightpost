package local.phantom.companion.feature.stream.ui.map.atlas

/**
 * ATLAS-2 «Лінза» — звідки береться [SceneMode].
 *
 * До цієї хвилі режимів було три, і сцену вирішувало те, що ВІДКРИТО на екрані
 * (лист, пошук, панель). Чотири оголошені режими стояли мертвими, бо ніхто не
 * питав головного: що людина зараз РОБИТЬ. Тут це питання і живе.
 *
 * Дві дисципліни, без яких таблиця нижче була б шкідливою:
 *
 *  1. **Витримка.** Класифікатор ходи вже має власний dwell (8 с, 25 с на вихід
 *     з машини), і поверх нього стоїть ще одне вікно [SUSTAINED_MS]: режим —
 *     дорога річ, і купувати його за одну секунду свідчень не можна. Вихід із
 *     режиму коштує рівно стільки ж, тож світлофор не робить водія пішоходом.
 *  2. **Жодного циклу кадрів.** Згортка каже, КОЛИ наступного разу вердикт
 *     може змінитися ([SceneMemory.dueAtMs]), і викликач спить рівно до тієї
 *     миті. Таймер на секунду тут був би термальною регресією на кожному
 *     екрані мапи, назавжди.
 */
internal object SceneDeriver {

    /** Скільки хода має протриматися, перш ніж вона стане режимом. */
    const val SUSTAINED_MS = 20_000L

    /** Скільки мапи не торкались, щоб екран мав право стишитись. */
    const val IDLE_MS = 30_000L

    /** «Наступного разу — ніколи»: жодного пробудження не заплановано. */
    const val NO_DEADLINE = Long.MAX_VALUE

    /**
     * Один крок згортки. Чистий: увесь час приходить параметром, стан —
     * значення, і те саме [now] двічі дає той самий результат.
     */
    fun fold(memory: SceneMemory, signals: SceneSignals, nowMs: Long): SceneMemory {
        val seen = if (signals.motionTrusted) signals.motion else MotionClass.UNKNOWN
        val gaitSince = if (!memory.seeded || seen != memory.gait) nowMs else memory.gaitSinceMs
        // Поки лист відкрито, «востаннє активний» тримається зараз: закриття
        // листа — це теж дія, і відлік спокою починається з неї, а не з
        // останнього дотику до самої землі, який міг бути хвилину тому.
        val active = when {
            !memory.seeded || signals.browsing -> nowMs
            else -> maxOf(memory.lastActiveMs, signals.interactionAtMs)
        }
        val held = memory.heldGait

        fun at(instant: Long) = resolve(signals, gaitAt(seen, gaitSince, held, instant), active, instant)

        val mode = at(nowMs)
        val due = listOf(gaitSince + SUSTAINED_MS, active + IDLE_MS)
            .filter { it > nowMs && at(it) != mode }
            .minOrNull() ?: NO_DEADLINE

        return SceneMemory(
            mode = mode,
            gait = seen,
            gaitSinceMs = gaitSince,
            heldGait = gaitAt(seen, gaitSince, held, nowMs),
            lastActiveMs = active,
            dueAtMs = due,
            seeded = true,
        )
    }

    /** Витримана хода: нова заступає на місце старої лише відстоявши вікно. */
    private fun gaitAt(seen: MotionClass, since: Long, held: MotionClass, at: Long): MotionClass =
        if (at - since >= SUSTAINED_MS) seen else held

    /**
     * Таблиця режимів, згори вниз. Перший рядок, що збігся, і є відповіддю.
     *
     * Порядок — це і є рішення:
     *
     *  - **Ведення** старше за все: маршрут на екрані означає, що людина його
     *    вже слухає.
     *  - **Тривога у ВЛАСНІЙ області** — далі. Не «десь у країні гучно»: під
     *    сиреною в Сумах киянин мапу не перебудовує.
     *  - **Транзит** іде ПЕРЕД ходою свідомо, усупереч першому накиду. Автобус
     *    для класифікатора нерозрізненний від авта, тож обраний маршрут разом
     *    із поїздкою в ньому дав би DRIVE — режим, який ховає транспорт, тобто
     *    рівно те, заради чого людина його відкрила.
     *  - **Хода** — лише витримана, і лише коли ніхто вище не озвався.
     *  - **Огляд** — стан за замовчуванням: мапу щойно відкрили, або на ній
     *    щось відкрито й читається.
     *  - **Спокій** — остання: тридцять секунд без жодного дотику.
     */
    private fun resolve(
        signals: SceneSignals,
        gait: MotionClass,
        lastActiveMs: Long,
        at: Long,
    ): SceneMode = when {
        signals.guiding -> SceneMode.GUIDE
        signals.ownOblastAlarm -> SceneMode.THREAT
        signals.transitContext -> SceneMode.TRANSIT
        gait == MotionClass.TRANSIT -> SceneMode.TRANSIT
        gait == MotionClass.DRIVE -> SceneMode.DRIVE
        gait == MotionClass.WALK -> SceneMode.WALK
        signals.browsing -> SceneMode.EXPLORE
        at - lastActiveMs >= IDLE_MS -> SceneMode.CALM
        else -> SceneMode.EXPLORE
    }
}

/** Усе, що деривація має право знати. Жодного посилання на екран. */
internal data class SceneSignals(
    /** Маршрут веде просто зараз (звичайний або евакуаційний). */
    val guiding: Boolean = false,
    /** Сирена саме над оператором, а не десь у країні. */
    val ownOblastAlarm: Boolean = false,
    /** Транспортний хаб відкрито або обрано транзитний маршрут. */
    val transitContext: Boolean = false,
    val motion: MotionClass = MotionClass.UNKNOWN,
    /** Класифікатор певний настільки, щоб його вердикт міняв екран. */
    val motionTrusted: Boolean = false,
    /** На екрані щось відкрито й читається: лист, пошук, «поруч», панель. */
    val browsing: Boolean = false,
    /** Час останнього дотику до самої землі, годинником викликача. */
    val interactionAtMs: Long = 0L,
)

/**
 * Памʼять згортки. [dueAtMs] — контракт із викликачем: до цієї миті вердикт не
 * зміниться, якщо не зміняться сигнали, тож будити мапу раніше немає за чим.
 */
internal data class SceneMemory(
    val mode: SceneMode = SceneMode.EXPLORE,
    /** Що бачить класифікатор просто зараз. */
    val gait: MotionClass = MotionClass.UNKNOWN,
    val gaitSinceMs: Long = 0L,
    /** Що з цього вже витримало вікно — саме воно і вирішує режим. */
    val heldGait: MotionClass = MotionClass.UNKNOWN,
    val lastActiveMs: Long = 0L,
    val dueAtMs: Long = SceneDeriver.NO_DEADLINE,
    val seeded: Boolean = false,
)
