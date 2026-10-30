package local.phantom.companion.core.sensor.nav

import kotlin.math.abs

/**
 * Лічильник шляху, ОБМЕЖЕНИЙ дорожнім графом — те, що лишається під РЕБ, коли
 * вільна інерціальна навігація вже розсипалась.
 *
 * ## Навіщо він і чим відрізняється від [DeadReckoner]
 *
 * Вільне подвійне інтегрування акселерометра телефона живе секунди — це
 * названо в [DeadReckoner] і не переписується тут. Але в місті людина не в
 * порожньому просторі: вона на дорозі. Гіроскоп (через [BearingFuser]) дає
 * курс, крокомір/акселерометр — пройдену відстань, а граф дає СПИСОК доріг.
 * Тоді позиція має одну ступінь свободи — «скільки метрів уздовж ЦІЄЇ дороги»,
 * — а на перехресті вибір ДИСКРЕТНИЙ: не «куди завгодно», а «одна з трьох».
 * Саме тому поперечна похибка тут не росте: її ріже коридор дороги.
 *
 * Клас чистий: ні Android, ні годинника всередині. Час приходить параметром і
 * використовується ЛИШЕ для штампа результату — на оцінку він не впливає.
 *
 * ## Що цей лічильник НЕ вміє за побудовою (межі, названі прямо)
 *
 *  1. **Не знає, що оператор зупинився, вийшов з авто чи пішов пішки.** Крок
 *     приходить ззовні готовою парою (відстань, курс); лічильник вірить їй.
 *     Хто подав 10 м — тому й буде 10 м уздовж дороги.
 *  2. **Не бачить доріг, яких немає в кеші графа.** Двір, стежка, свіжа
 *     вулиця — це або чесне «не знаю», або, гірше, тихий вибір сусідньої
 *     вулиці, яка випадково збіглася з курсом. Граф — це не карта світу, це
 *     те, що встигли завантажити.
 *  3. **На трасі без перехресть похибка вздовж дороги росте без стелі.** Граф
 *     ріже ПОПЕРЕЧНУ похибку, а не поздовжню. Через годину на трасі клас
 *     впевнено покаже не ту розвʼязку.
 *  4. **Невірний вибір на перехресті не має ціни в метрах.** Це цілий квартал
 *     убік, і жодне число в [Coast.Held.uncertaintyM] цього не описує:
 *     радіус говорить про поздовжній зсув, а не про «я поїхав іншою вулицею».
 *     Тому кількість перехресть віддається окремим полем
 *     ([Coast.Held.junctionsPassed]) — щоб її було видно, а не змішано в метри.
 *  5. **Розділена вулиця читається як неоднозначність.** Дві OSM-way, що йдуть
 *     тим самим курсом (розділена проїжджа частина, вулиця, розрізана на дві
 *     way у вузлі), дадуть AMBIGUOUS_JUNCTION, хоч фізично це одна дорога.
 *     Ми свідомо обираємо зайву відмову замість тихого вгадування.
 *  6. **Нічого не знає про час.** 10 м за секунду і 10 м за годину для нього
 *     однакові; хто подає кроки — той і відповідає за їхню осмисленість.
 *  7. **Не бачить заборон руху, крім `oneway`.** Ні заборонених поворотів, ні
 *     шлагбаумів, ні перекритих вулиць.
 */
class GraphCoasting(
    private val graph: RoadGraph,
    /** Радіус привʼязки довіреного фікса до графа. */
    private val snapRadiusM: Double = SNAP_RADIUS_M,
    /** Радіус пошуку продовження на перехресті. */
    private val junctionRadiusM: Double = JUNCTION_RADIUS_M,
) {

    /** Чому лічильник каже «не знаю». Кожна причина — окреме СЛОВО, не мовчання. */
    enum class Reason(val sentenceUa: String) {
        /** Не було жодного довіреного фікса — вести відлік нема від чого. */
        NOT_SEEDED("Немає довіреної точки, від якої вести відлік"),

        /** Графа поруч немає, або жодна дорога поруч не йде цим курсом. */
        NO_ROAD_NEARBY("Поруч немає дороги, що йде цим курсом"),

        /** Два продовження однаково схожі на курс — вибір був би вгадуванням. */
        AMBIGUOUS_JUNCTION("Розгалуження: два продовження однаково схожі на курс"),

        /** Дорога скінчилася, і жодне продовження не збігається з курсом. */
        PATH_EXHAUSTED("Дорога скінчилася, продовження цим курсом немає"),
    }

    sealed interface Coast {

        /** Позиція, яку лічильник готовий назвати, разом з ціною цієї готовності. */
        data class Held(
            val lat: Double,
            val lon: Double,
            /** Курс руху, з яким приймався останній крок, градуси компаса. */
            val courseDeg: Double,
            /** Дорога, вздовж якої зараз ведеться відлік. */
            val way: RoadWay,
            /** Метри від першої вершини [way] до поточної точки. */
            val alongM: Double,
            /** Радіус сумніву — див. [ODOMETRY_DRIFT_FRACTION] і межу №4. */
            val uncertaintyM: Double,
            /** Скільки метрів пройдено від останнього довіреного фікса. */
            val distanceSinceFixM: Double,
            /** Скільки дискретних виборів на перехрестях зроблено від фікса. */
            val junctionsPassed: Int,
            /** Мілісекунди від останнього довіреного фікса — лише для показу. */
            val sinceFixMs: Long,
            /** false — це сам фікс; true — це числений шлях, не вимір. */
            val coasted: Boolean,
            val atMs: Long,
        ) : Coast

        /**
         * «Не знаю» — з причиною. [lostAtLat]/[lostAtLon] — це НЕ поточна
         * позиція: це місце, де лічильник здався. Показувати його як позицію
         * оператора заборонено; воно тут лише щоб намалювати, де обірвався слід.
         */
        data class Lost(
            val reason: Reason,
            val lostAtLat: Double?,
            val lostAtLon: Double?,
            val atMs: Long,
        ) : Coast {
            val sentenceUa: String get() = reason.sentenceUa
        }
    }

    private var way: RoadWay? = null
    private var alongM = 0.0
    private var dir = 1
    private var lat = 0.0
    private var lon = 0.0
    private var courseDeg = 0.0

    private var fixAccuracyM = 0.0
    private var distanceSinceFixM = 0.0
    private var junctionsPassed = 0
    private var fixAtMs = 0L

    private var lost: Reason? = Reason.NOT_SEEDED
    private var lostAtLat: Double? = null
    private var lostAtLon: Double? = null

    /** Кеш довжин уздовж way; ключ — сам обʼєкт, бо id у різних графах збігаються. */
    private val prefixCache = java.util.IdentityHashMap<RoadWay, DoubleArray>()

    /**
     * Довірений фікс: єдине, що скидає накопичений сумнів. Привʼязує позицію до
     * графа й починає відлік наново.
     *
     * @param accuracyM заявлена похибка самого фікса, метри — від неї росте все далі.
     */
    fun seed(
        lat: Double,
        lon: Double,
        courseDeg: Double,
        accuracyM: Double,
        atMs: Long,
    ): Coast {
        require(lat.isFinite() && lon.isFinite()) { "фікс із нечисловими координатами" }
        require(courseDeg.isFinite()) { "фікс без курсу" }
        require(accuracyM.isFinite() && accuracyM >= 0.0) { "відʼємна похибка фікса" }

        val course = norm360(courseDeg)
        val best = graph.near(lat, lon, snapRadiusM, limit = MAX_JUNCTION_CANDIDATES).firstOrNull()
            ?: return lose(Reason.NO_ROAD_NEARBY, lat, lon, atMs)

        this.way = best.way
        this.alongM = best.alongM
        this.dir = if (angDiffDeg(course, best.headingDeg.toDouble()) <= 90.0) 1 else -1
        this.lat = best.lat
        this.lon = best.lon
        this.courseDeg = course
        this.fixAccuracyM = accuracyM
        this.distanceSinceFixM = 0.0
        this.junctionsPassed = 0
        this.fixAtMs = atMs
        this.lost = null
        this.lostAtLat = null
        this.lostAtLon = null
        return held(atMs, coasted = false)
    }

    /**
     * Один такт руху: [distanceM] метрів пройденого шляху за курсом [courseDeg].
     *
     * Курс — АБСОЛЮТНИЙ (компасні градуси), а не приріст: інтегрування гіроскопа
     * в курс уже зроблено в [BearingFuser], і дублювати його тут значило б мати
     * два різні уявлення про північ.
     *
     * Після будь-якого «не знаю» лічильник НЕ оживає сам: кожен наступний крок
     * повертає ту саму відмову, поки не прийде [seed].
     */
    fun step(distanceM: Double, courseDeg: Double, atMs: Long): Coast {
        require(distanceM.isFinite() && distanceM >= 0.0) { "крок із відʼємною відстанню" }
        require(courseDeg.isFinite()) { "крок без курсу" }

        lost?.let { return Coast.Lost(it, lostAtLat, lostAtLon, atMs) }
        val start = way ?: return lose(Reason.NOT_SEEDED, null, null, atMs)

        this.courseDeg = norm360(courseDeg)
        if (distanceM <= EPS_M) return held(atMs, coasted = true)

        var current = start
        var remaining = distanceM
        var guard = 0

        while (remaining > EPS_M) {
            if (++guard > MAX_ITERATIONS) {
                // Захист від зациклення на патологічній геометрії (нульові
                // сегменти, кільце з нуля метрів). Мовчки крутитись — гірше.
                return lose(Reason.PATH_EXHAUSTED, lat, lon, atMs)
            }
            val prefix = prefixOf(current)
            if (prefix.size < 2) return lose(Reason.NO_ROAD_NEARBY, lat, lon, atMs)

            // Напрямок руху вздовж дороги вибирає КУРС, а не памʼять: розворот
            // на місці має вести назад тією ж дорогою.
            var seg = segmentIndex(prefix, alongM, dir)
            var segHead = segmentHeading(current, seg)
            val flipped = if (angDiffDeg(this.courseDeg, segHead) <= 90.0) 1 else -1
            if (flipped != dir) {
                dir = flipped
                seg = segmentIndex(prefix, alongM, dir)
                segHead = segmentHeading(current, seg)
            }
            val travelHead = if (dir > 0) segHead else norm360(segHead + 180.0)

            if (angDiffDeg(this.courseDeg, travelHead) > TURN_ACCEPT_DEG) {
                // Курс зійшов з цієї дороги — шукаємо поворот ТУТ же, викидаючи
                // саму дорогу з розгляду (інакше вибір міг би повернути її знову).
                val turn = chooseContinuation(lat, lon, exclude = current, atEnd = false)
                    ?: return lose(lastRefusal, lat, lon, atMs)
                current = turn.way
                alongM = turn.alongM
                dir = turn.dir
                junctionsPassed++
                syncPosition(current)
                continue
            }

            val room = if (dir > 0) prefix[seg + 1] - alongM else alongM - prefix[seg]
            if (remaining <= room + EPS_M) {
                alongM += dir * remaining
                distanceSinceFixM += remaining
                remaining = 0.0
                syncPosition(current)
                break
            }

            alongM = if (dir > 0) prefix[seg + 1] else prefix[seg]
            distanceSinceFixM += room
            remaining -= room
            syncPosition(current)

            val atWayEnd = if (dir > 0) seg == prefix.size - 2 else seg == 0
            if (atWayEnd) {
                // Дорога скінчилась, а метри лишились — це перехрестя.
                val turn = chooseContinuation(lat, lon, exclude = null, atEnd = true)
                    ?: return lose(lastRefusal, lat, lon, atMs)
                current = turn.way
                alongM = turn.alongM
                dir = turn.dir
                junctionsPassed++
                syncPosition(current)
            }
        }

        way = current
        return held(atMs, coasted = true)
    }

    /** Поточна оцінка без нового кроку — той самий стан, той самий словник. */
    fun current(atMs: Long): Coast =
        lost?.let { Coast.Lost(it, lostAtLat, lostAtLon, atMs) } ?: held(atMs, coasted = distanceSinceFixM > 0.0)

    private data class Turn(val way: RoadWay, val alongM: Double, val dir: Int)

    private var lastRefusal: Reason = Reason.NO_ROAD_NEARBY

    /**
     * Дискретний вибір на перехресті: серед доріг у [junctionRadiusM] беремо ту,
     * чий напрямок руху найближчий до курсу. Повертає null і виставляє
     * [lastRefusal], коли чесна відповідь — «не знаю».
     */
    private fun chooseContinuation(
        atLat: Double,
        atLon: Double,
        exclude: RoadWay?,
        atEnd: Boolean,
    ): Turn? {
        val near = graph.near(atLat, atLon, junctionRadiusM, limit = MAX_JUNCTION_CANDIDATES)
        if (near.isEmpty()) {
            lastRefusal = Reason.NO_ROAD_NEARBY
            return null
        }

        val options = ArrayList<Pair<Turn, Double>>()
        for (c in near) {
            if (exclude != null && c.way === exclude) continue
            val prefix = prefixOf(c.way)
            if (prefix.size < 2) continue
            val total = prefix.last()
            val head = c.headingDeg.toDouble()
            // Уперед по геометрії way — завжди можна, якщо попереду ще є дорога.
            if (total - c.alongM > MIN_ROOM_M) {
                options.add(Turn(c.way, c.alongM, 1) to angDiffDeg(courseDeg, head))
            }
            // Назад — лише якщо way не односторонній.
            if (c.alongM > MIN_ROOM_M && !c.way.oneway) {
                options.add(Turn(c.way, c.alongM, -1) to angDiffDeg(courseDeg, norm360(head + 180.0)))
            }
        }

        val viable = options.filter { it.second <= TURN_ACCEPT_DEG }.sortedBy { it.second }
        if (viable.isEmpty()) {
            lastRefusal = if (atEnd) Reason.PATH_EXHAUSTED else Reason.NO_ROAD_NEARBY
            return null
        }
        if (viable.size >= 2 && viable[1].second - viable[0].second < AMBIGUITY_MARGIN_DEG) {
            lastRefusal = Reason.AMBIGUOUS_JUNCTION
            return null
        }
        return viable[0].first
    }

    private fun syncPosition(w: RoadWay) {
        val p = pointAt(w, prefixOf(w), alongM)
        lat = p.first
        lon = p.second
    }

    private fun held(atMs: Long, coasted: Boolean): Coast {
        val w = way ?: return Coast.Lost(Reason.NOT_SEEDED, null, null, atMs)
        return Coast.Held(
            lat = lat,
            lon = lon,
            courseDeg = courseDeg,
            way = w,
            alongM = alongM,
            uncertaintyM = fixAccuracyM + ODOMETRY_DRIFT_FRACTION * distanceSinceFixM,
            distanceSinceFixM = distanceSinceFixM,
            junctionsPassed = junctionsPassed,
            sinceFixMs = atMs - fixAtMs,
            coasted = coasted,
            atMs = atMs,
        )
    }

    private fun lose(reason: Reason, atLat: Double?, atLon: Double?, atMs: Long): Coast.Lost {
        lost = reason
        lostAtLat = atLat
        lostAtLon = atLon
        way = null
        return Coast.Lost(reason, atLat, atLon, atMs)
    }

    /** Накопичені метри від першої вершини way до кожної з наступних. */
    private fun prefixOf(w: RoadWay): DoubleArray = prefixCache.getOrPut(w) {
        val pts = w.points
        DoubleArray(pts.size).also { acc ->
            for (i in 1 until pts.size) {
                acc[i] = acc[i - 1] + haversineM(
                    pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second,
                )
            }
        }
    }

    /** Сегмент, У ЯКИЙ ми рухаємось із [alongM] у напрямку [dir]. */
    private fun segmentIndex(prefix: DoubleArray, alongM: Double, dir: Int): Int {
        val last = prefix.size - 2
        if (last <= 0) return 0
        return if (dir > 0) {
            var i = 0
            while (i < last && prefix[i + 1] <= alongM) i++
            i
        } else {
            var i = last
            while (i > 0 && prefix[i] >= alongM) i--
            i
        }
    }

    private fun segmentHeading(w: RoadWay, seg: Int): Double {
        val (aLat, aLon) = w.points[seg]
        val (bLat, bLon) = w.points[seg + 1]
        return bearingOf(lonToMeters(bLon - aLon, aLat), latToMeters(bLat - aLat)).toDouble()
    }

    /** Лінійна інтерполяція в градусах: у межах одного сегмента похибка — сантиметри. */
    private fun pointAt(w: RoadWay, prefix: DoubleArray, s: Double): Pair<Double, Double> {
        if (s <= 0.0) return w.points.first()
        val total = prefix.last()
        if (s >= total) return w.points.last()
        var i = 0
        while (i < prefix.size - 2 && prefix[i + 1] < s) i++
        val segLen = prefix[i + 1] - prefix[i]
        val t = if (segLen <= 0.0) 0.0 else (s - prefix[i]) / segLen
        val (aLat, aLon) = w.points[i]
        val (bLat, bLon) = w.points[i + 1]
        return (aLat + (bLat - aLat) * t) to (aLon + (bLon - aLon) * t)
    }

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    private fun angDiffDeg(a: Double, b: Double): Double {
        val d = abs(norm360(a) - norm360(b))
        return if (d > 180.0) 360.0 - d else d
    }

    companion object {
        /**
         * Скільки метрів сумніву додає кожен метр численого шляху: 0.18.
         *
         * Звідки: [DeadReckoner] називає ВИМІРЯНИЙ зсув акселерометра ≈ 0.05 м/с²
         * (документовано там же як 3 м/с помилки швидкості за хвилину). Зсув,
         * проінтегрований двічі, дає похибку ½·b·t² = ½·0.05·60² ≈ 90 м за
         * хвилину. За ту саму хвилину міський рух на 8.3 м/с (30 км/год) долає
         * 500 м. 90/500 = 0.18.
         *
         * Це ПІДЛОГА, а не стеля: справжня похибка квадратична за часом, тож за
         * межами хвилини числення цей лінійний дріб недооцінює сумнів. Він
         * описує лише ПОЗДОВЖНІЙ зсув — поперечний ріже коридор дороги, а ціну
         * невірного повороту не описує взагалі (межа №4 в докстрінгу класу).
         */
        const val ODOMETRY_DRIFT_FRACTION = 0.18

        /** Радіус привʼязки фікса — той самий поріг, що й `MIN_SEARCH_RADIUS_M` у [RoadMatcher]. */
        const val SNAP_RADIUS_M = 35.0

        /**
         * Радіус пошуку продовження на перехресті — свідомо ТІСНІШЕ за привʼязку
         * фікса: перехрестя двох двосмугових вулиць із радіусами повороту займає
         * близько 25 м, а типовий міський квартал не коротший за 60 м, тож
         * вулиця з сусіднього кварталу в цей вибір не потрапляє.
         */
        const val JUNCTION_RADIUS_M = 25.0

        /**
         * Наскільки курс може розходитись із дорогою, і це все ще «я на ній».
         * 45° поглинає вигин вулиці й похибку курсу ([BearingFuser] тримає
         * одиниці-десятки градусів); далі схожість на дорогу — вже монетка.
         */
        const val TURN_ACCEPT_DEG = 45.0

        /**
         * Якщо два найкращі продовження ближчі одне до одного за цей кут — це
         * не вибір, це вгадування, і клас каже AMBIGUOUS_JUNCTION. 20° — те, що
         * курс під РЕБ і так може переплутати.
         */
        const val AMBIGUITY_MARGIN_DEG = 20.0

        /** Менше метра дороги в цей бік — це той самий вузол, а не продовження. */
        private const val MIN_ROOM_M = 1.0

        /** Один такт не має права обійти більше стількох сегментів/перехресть. */
        private const val MAX_ITERATIONS = 512

        private const val MAX_JUNCTION_CANDIDATES = 8

        private const val EPS_M = 0.01
    }
}
