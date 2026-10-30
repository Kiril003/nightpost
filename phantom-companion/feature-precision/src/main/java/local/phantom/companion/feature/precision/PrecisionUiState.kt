package local.phantom.companion.feature.precision

import local.phantom.companion.core.gnss.Band
import local.phantom.companion.core.gnss.Constellation
import local.phantom.companion.core.gnss.FixSource
import local.phantom.companion.core.gnss.GnssEpoch
import local.phantom.companion.core.gnss.GnssIntegrity
import local.phantom.companion.core.indoor.pdr.PedestrianSnapshot
import local.phantom.companion.core.indoor.rtt.RttAvailability
import local.phantom.companion.core.mobility.TransportMode

/**
 * Стан екрана чесної позиції.
 *
 * Уся робота нижніх модулів невидима за визначенням: вона виражається в тому,
 * що точка НЕ стрибнула і маршрут НЕ повів у поле. Користувач такого не помічає
 * — аж поки не станеться збій, і тоді в нього немає жодного способу зрозуміти,
 * чому додаток збрехав.
 *
 * Тому цей екран показує не «все добре», а те, ЩО САМЕ зараз відбувається:
 * скільки супутників реально прийнято, які виключено і за що, звідки взято
 * поточний розвʼязок і чи є ознаки придушення або підміни.
 *
 * Стан — незмінний знімок і чиста функція від даних: жодного звернення до
 * платформи, жодного Compose. Через це весь екран перевіряється на JVM, а не
 * «виїздом у поле під роботу РЕБ».
 */
data class PrecisionUiState(
    val satellites: List<SatelliteRow> = emptyList(),
    val integrity: GnssIntegrity? = null,
    val fixSource: FixSource = FixSource.NONE,
    val accuracyM: Double? = null,
    val mode: TransportMode = TransportMode.UNKNOWN,
    /** Розбіжність між супутниковим і мережевим розвʼязками, км; `null` — немає з чим порівняти. */
    val networkDivergenceKm: Double? = null,
    /** Фазовий звʼязок безперервний: чергування робочих циклів приймача не рве TDCP. */
    val phaseContinuous: Boolean = true,
    val indoorLevel: Double? = null,
    /**
     * Автономний пішохідний хід — те, що лишається, коли неба немає взагалі.
     *
     * `null` означає «джерело не підписане» (наприклад, у тесті чи в
     * початковому знімку), а не «датчиків немає»: про відсутність заліза
     * говорить сам знімок через `sensors`. Плутати ці два випадки не можна —
     * перший тимчасовий, другий назавжди.
     */
    val pedestrian: PedestrianSnapshot? = null,
    /** Чи вміє цей пристрій Wi-Fi RTT. Суто читальна ознака, без вимірювань. */
    val rttAvailability: RttAvailability? = null,
) {

    /** Супутники, згруповані за системою, — у сталому порядку для стабільного UI. */
    val byConstellation: Map<Constellation, List<SatelliteRow>>
        get() = satellites.groupBy { it.constellation }
            .toSortedMap(compareBy { it.ordinal })

    /** Скільки супутників реально пішло в розвʼязок. */
    val usedCount: Int get() = satellites.count { it.used }

    /** Скільки виключено з причиною. */
    val excludedCount: Int get() = satellites.count { it.exclusionReason != null }

    /** Чи приймач бачить другу частоту — саме вона знімає іоносферну затримку. */
    val dualFrequency: Boolean get() = satellites.any { it.band == Band.L5 }

    /**
     * Загальний вердикт одним рядком — те єдине, що людина прочитає за кермом.
     *
     * Порядок перевірок відбиває пріоритет загрози, а не зручність коду:
     * підміна найнебезпечніша (позиція бреше, виглядаючи здоровою), далі
     * придушення (позиції немає, і це видно), далі деградації.
     */
    val verdict: PrecisionVerdict
        get() {
            val i = integrity
            return when {
                i != null && i.spoofingScore >= SPOOF_ALARM -> PrecisionVerdict.SPOOFED
                networkDivergenceKm != null && networkDivergenceKm >= DIVERGENCE_ALARM_KM -> PrecisionVerdict.SPOOFED
                i != null && i.jammingScore >= JAM_ALARM -> PrecisionVerdict.JAMMED
                fixSource == FixSource.DEAD_RECKONING -> PrecisionVerdict.DEAD_RECKONING
                fixSource == FixSource.WIFI_RTT -> PrecisionVerdict.INDOOR
                fixSource == FixSource.NONE || usedCount == 0 -> PrecisionVerdict.NO_FIX
                usedCount < MIN_HEALTHY_SATS -> PrecisionVerdict.DEGRADED
                else -> PrecisionVerdict.HEALTHY
            }
        }

    /**
     * Чи можна привʼязати до цього розвʼязку автономне числення шляху.
     *
     * Це найдорожче рішення на екрані, і воно навмисно суворіше за «позиція
     * виглядає нормально». Привʼязка ПЕРЕЖИВАЄ саму атаку: якщо привʼязати
     * числення до підміненої координати, брехня не зникне разом із глушінням —
     * вона поїде далі як «власне числення пристрою», уже без жодних ознак
     * підміни, за які її можна було б упіймати. Тому потрібні одночасно:
     * бездоганний вердикт (а він уже враховує підміну, глушіння, розбіжність із
     * мережею і кількість супутників) і мала заявлена похибка.
     *
     * Наслідок для передачі «супутник → числення»: привʼязуватись треба ПОКИ
     * супутник ще здоровий, а не в мить, коли він зник. Саме тому це властивість
     * звичайного, спокійного стану, а не аварійного.
     */
    val trustedForAnchor: Boolean
        get() {
            val a = accuracyM ?: return false
            return verdict == PrecisionVerdict.HEALTHY && a.isFinite() && a <= MAX_ANCHOR_ACCURACY_M
        }

    companion object {
        /** Бал підміни, вище якого супутникам не можна вірити взагалі. */
        const val SPOOF_ALARM = 0.6

        /** Бал придушення, вище якого сигнал вважається втраченим. */
        const val JAM_ALARM = 0.6

        /**
         * Розбіжність із мережевим позиціонуванням, що сама по собі є доказом
         * підміни, км.
         *
         * Поріг свідомо низький відносно реальності: 21 липня 2026 в Києві
         * виміряна розбіжність склала 12 270 км (супутники показували Ліму,
         * мережа — Київ). Пʼятдесят кілометрів не може дати ні похибка стільникової
         * привʼязки, ні найгірша багатошляховість — лише брехня.
         */
        const val DIVERGENCE_ALARM_KM = 50.0

        /** Менше цієї кількості супутників — розвʼязок є, але довіра до нього низька. */
        const val MIN_HEALTHY_SATS = 5

        /**
         * Найбільша похибка, з якою розвʼязок ще годиться за привʼязку для
         * числення шляху, м.
         *
         * Пʼятнадцять метрів — це звичайний добрий міський розвʼязок. Брати
         * гірший немає сенсу: похибка привʼязки входить у похибку числення як
         * початкова умова і тягнеться потім крізь усю автономну ділянку.
         */
        const val MAX_ANCHOR_ACCURACY_M = 15.0

        /**
         * Зібрати стан з епохи спостережень і вердикту цілісності.
         *
         * Причини виключення беруться з [GnssIntegrity.excluded] — саме тому
         * там мапа з поясненнями, а не просто список: рядок «виключено як
         * викид проти інерціального вектора» має дійти до екрана дослівно.
         */
        fun from(
            epoch: GnssEpoch?,
            integrity: GnssIntegrity?,
            fixSource: FixSource = integrity?.source ?: FixSource.NONE,
            accuracyM: Double? = null,
            mode: TransportMode = TransportMode.UNKNOWN,
            networkDivergenceKm: Double? = null,
            phaseContinuous: Boolean = true,
            indoorLevel: Double? = null,
            pedestrian: PedestrianSnapshot? = null,
            rttAvailability: RttAvailability? = null,
        ): PrecisionUiState {
            val excluded = integrity?.excluded.orEmpty()
            val rows = epoch?.observations.orEmpty().map { o ->
                SatelliteRow(
                    key = o.key,
                    svid = o.svid,
                    constellation = o.constellation,
                    band = o.band,
                    cn0DbHz = o.cn0DbHz,
                    elevationDeg = o.elevationDeg,
                    azimuthDeg = o.azimuthDeg,
                    used = o.usedInFix && o.key !in excluded,
                    exclusionReason = excluded[o.key],
                )
            }.sortedWith(
                compareBy<SatelliteRow> { it.constellation.ordinal }
                    .thenByDescending { it.cn0DbHz },
            )
            return PrecisionUiState(
                satellites = rows,
                integrity = integrity,
                fixSource = fixSource,
                accuracyM = accuracyM,
                mode = mode,
                networkDivergenceKm = networkDivergenceKm,
                phaseContinuous = phaseContinuous,
                indoorLevel = indoorLevel,
                pedestrian = pedestrian,
                rttAvailability = rttAvailability,
            )
        }
    }
}

/** Один рядок таблиці супутників. */
data class SatelliteRow(
    val key: String,
    val svid: Int,
    val constellation: Constellation,
    val band: Band,
    val cn0DbHz: Double,
    val elevationDeg: Double?,
    val azimuthDeg: Double?,
    val used: Boolean,
    /** Чому супутник не пішов у розвʼязок; `null` — пішов. */
    val exclusionReason: String? = null,
) {
    /**
     * Сила сигналу як частка 0..1 для стовпчика в UI.
     *
     * Шкала обрана за фізикою, а не «щоб гарно виглядало»: нижче 20 dB-Hz
     * супутник практично не відстежується, вище 45 різниця вже не впливає на
     * якість розвʼязку.
     */
    val strength: Float
        get() = ((cn0DbHz - WEAK_CN0) / (STRONG_CN0 - WEAK_CN0)).coerceIn(0.0, 1.0).toFloat()

    private companion object {
        const val WEAK_CN0 = 20.0
        const val STRONG_CN0 = 45.0
    }
}

/**
 * Підсумковий стан позиціонування.
 *
 * @param title короткий заголовок для екрана
 * @param explanation те, що людина має зрозуміти без жодних термінів
 */
enum class PrecisionVerdict(val title: String, val explanation: String) {
    HEALTHY(
        "Позиція надійна",
        "Супутники узгоджені між собою й з рухом пристрою.",
    ),
    DEGRADED(
        "Позиція послаблена",
        "Супутників мало — точність нижча за звичайну, стрибки можливі.",
    ),
    JAMMED(
        "Сигнал придушено",
        "Супутниковий сигнал заглушено. Веду за численням шляху від датчиків руху.",
    ),
    SPOOFED(
        "Виявлено підміну",
        "Супутники передають хибні координати. Вони відкинуті — позиція тримається " +
            "за мережею та датчиками руху.",
    ),
    DEAD_RECKONING(
        "Числення шляху",
        "Супутників немає. Позиція рахується від датчиків руху й поступово втрачає точність.",
    ),
    INDOOR(
        "У приміщенні",
        "Супутника немає. Позиція будується за Wi-Fi та кроками.",
    ),
    NO_FIX(
        "Позиції немає",
        "Жодного придатного джерела. Останнє відоме положення застаріває.",
    ),
    ;

    /** Стан вимагає уваги людини, а не просто повідомлення. */
    val alarming: Boolean get() = this == SPOOFED || this == JAMMED || this == NO_FIX
}
