package local.phantom.companion.core.net.peer

/**
 * Положення й маршрут, якими діляться з ОБРАНИМ — явно й на визначений час.
 *
 * Це не шар присутності. Тут немає й не може бути «хто де зараз»: лист їде
 * одній людині, яку назвали, і несе власний строк. Строк — не порада для
 * малювання, а межа самого листа: приймач, у якого строк вийшов, викидає його,
 * навіть якщо відклик не доїхав ніколи. Саме тому [Fix.expiresAtMs] не має
 * усталеного значення — лист без строку не збирається.
 *
 * Кадри їдуть тими самими дверима, що й слово: підпис, конверт, черга, повтори
 * й квитанція вже доведені на тексті — тим самим шляхом, яким [PeerFile] повіз
 * байти. Другий канал довелося б будувати впʼяте, і саме він мовчки губив би
 * те, що людина вважає надісланим.
 *
 * Форма навмисне людиночитна, як у [PeerPayload]: телефон зі старішою збіркою
 * покаже сирий рядок замість нічого. Різниця між зіпсутою розмовою й порожньою.
 */
object PeerLive {

    internal const val FIX = "PH-LIV:"
    internal const val STOP = "PH-LVX:"
    internal const val ROUTE = "PH-RTE:"
    private const val SEP = ';'

    /**
     * Скільки точок ламаної переживає дорогу.
     *
     * Чужа геометрія — це МАЛЮНОК, а не істина: їхати нею не можна, бо
     * «Їхати цим маршрутом» будує свій план від СВОГО положення до тих самих
     * точок. Тож проріджування нічого не бреше — воно лише не тягне у 64 КіБ
     * кадру десять тисяч вершин, які ніхто не читатиме як дані.
     */
    const val MAX_SHAPE_POINTS = 600

    /** Точки, за якими план будується заново. Понад це — не маршрут, а слід. */
    const val MAX_WAYPOINTS = 12

    /**
     * Скільки живе надісланий маршрут.
     *
     * Строк тут не про приватність, а про правду: дороги перекривають, і план,
     * складений учора, веде туди, де сьогодні не проїхати. Півдоби — рівно
     * стільки, скільки маршрут ще описує ту саму землю.
     */
    const val ROUTE_LIFE_MS = 12 * 60 * 60_000L

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Int?,
        /** Мить ВИМІРУ на телефоні відправника, не доставки. */
        val atMs: Long,
        val expiresAtMs: Long,
        /** Тік сеансу. Діра в лічильнику — це розрив сліду, а не рух. */
        val seq: Int,
    )

    data class Route(
        val expiresAtMs: Long,
        val distanceM: Double,
        val durationS: Long,
        /** Чим саме його прокладали. Порожній — не сказали. */
        val profile: String,
        val waypoints: List<Pair<Double, Double>>,
        val shape: List<Pair<Double, Double>>,
    )

    // ── живе положення ───────────────────────────────────────────────────

    /** Null — коли строку немає або координата не координата: пів-точки не буває. */
    fun encodeFix(fix: Fix): String? {
        if (fix.expiresAtMs <= 0L || fix.atMs <= 0L || fix.seq < 0) return null
        if (fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0) return null
        val coords = "%.5f,%.5f".format(java.util.Locale.US, fix.lat, fix.lon)
        val acc = fix.accuracyM?.takeIf { it > 0 }?.toString().orEmpty()
        return FIX + coords + SEP + acc + SEP + fix.atMs + SEP + fix.expiresAtMs + SEP + fix.seq
    }

    fun isFix(body: String): Boolean = body.startsWith(FIX)

    fun decodeFix(body: String): Fix? {
        if (!isFix(body)) return null
        val f = body.removePrefix(FIX).split(SEP)
        if (f.size != 5) return null
        val coords = f[0].split(',')
        if (coords.size != 2) return null
        val lat = coords[0].trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = coords[1].trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
        val atMs = f[2].trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val until = f[3].trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val seq = f[4].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Fix(
            lat = lat,
            lon = lon,
            accuracyM = f[1].trim().toIntOrNull()?.takeIf { it > 0 },
            atMs = atMs,
            expiresAtMs = until,
            seq = seq,
        )
    }

    // ── відклик ──────────────────────────────────────────────────────────

    /**
     * «Я більше не ділюсь». Приймач стирає точку одразу — але ніколи на цей
     * лист не покладається: строк у кожному кадрі гасить точку й тоді, коли
     * відклик не доїхав.
     */
    fun encodeStop(atMs: Long): String = STOP + atMs

    fun isStop(body: String): Boolean = body.startsWith(STOP)

    fun decodeStop(body: String): Long? =
        if (isStop(body)) body.removePrefix(STOP).trim().toLongOrNull()?.takeIf { it > 0L } else null

    /**
     * Службовий вантаж живого положення: у розмові його немає й у черзі він
     * не рахується за річ, яку людина надіслала. Маршрут сюди НЕ входить —
     * його надсилають рукою, і він мусить лишити по собі рядок.
     */
    fun isCargo(body: String): Boolean = isFix(body) || isStop(body)

    // ── маршрут ──────────────────────────────────────────────────────────

    fun encodeRoute(route: Route): String? {
        if (route.expiresAtMs <= 0L) return null
        if (route.waypoints.size !in 2..MAX_WAYPOINTS) return null
        if (route.shape.size < 2) return null
        if (route.waypoints.any { !inRange(it) } || route.shape.any { !inRange(it) }) return null
        if (route.distanceM < 0.0 || route.durationS < 0L) return null
        val profile = route.profile.filter { it.isLetter() || it == '_' }.take(16)
        return ROUTE + route.expiresAtMs + SEP +
            "%.0f".format(java.util.Locale.US, route.distanceM) + SEP +
            route.durationS + SEP +
            profile + SEP +
            encodePath(route.waypoints) + SEP +
            encodePath(thin(route.shape))
    }

    fun isRoute(body: String): Boolean = body.startsWith(ROUTE)

    fun decodeRoute(body: String): Route? {
        if (!isRoute(body)) return null
        val f = body.removePrefix(ROUTE).split(SEP)
        if (f.size != 6) return null
        val until = f[0].trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val distance = f[1].trim().toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null
        val duration = f[2].trim().toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val waypoints = decodePath(f[4]).takeIf { it.size in 2..MAX_WAYPOINTS } ?: return null
        val shape = decodePath(f[5]).takeIf { it.size >= 2 } ?: return null
        return Route(
            expiresAtMs = until,
            distanceM = distance,
            durationS = duration,
            profile = f[3].trim(),
            waypoints = waypoints,
            shape = shape,
        )
    }

    /**
     * Рівномірне проріджування, у якому ПЕРША й ОСТАННЯ точки недоторканні:
     * маршрут, що починається не там, де починався, — це вже інший маршрут.
     */
    fun thin(points: List<Pair<Double, Double>>, max: Int = MAX_SHAPE_POINTS): List<Pair<Double, Double>> {
        if (max < 2 || points.size <= max) return points
        val last = points.lastIndex
        val out = ArrayList<Pair<Double, Double>>(max)
        for (i in 0 until max - 1) {
            out.add(points[(i.toLong() * last / (max - 1)).toInt()])
        }
        out.add(points[last])
        return out
    }

    // ── ламана ───────────────────────────────────────────────────────────
    //
    // Кодування Google, точність 5 (~1,1 м) — не з любові до чужого формату, а
    // тому що його абетка це символи 63..126, серед яких немає ані ';', ані
    // ',': роздільники кадру не можуть опинитись усередині чисел.

    fun encodePath(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        points.forEach { (lat, lon) ->
            val eLat = Math.round(lat * 1e5)
            val eLon = Math.round(lon * 1e5)
            writeSigned(out, eLat - lastLat)
            writeSigned(out, eLon - lastLon)
            lastLat = eLat
            lastLon = eLon
        }
        return out.toString()
    }

    fun decodePath(text: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < text.length) {
            val dLat = readSigned(text, i) ?: return emptyList()
            i = dLat.second
            val dLon = readSigned(text, i) ?: return emptyList()
            i = dLon.second
            lat += dLat.first
            lon += dLon.first
            val point = lat / 1e5 to lon / 1e5
            if (!inRange(point)) return emptyList()
            out.add(point)
        }
        return out
    }

    private fun writeSigned(out: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    /** Значення й індекс ПІСЛЯ нього; null — обірваний або чужий символ. */
    private fun readSigned(text: String, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = from
        while (true) {
            if (i >= text.length || shift > 30) return null
            val c = text[i].code - 63
            if (c < 0 || c > 0x3f) return null
            i++
            result = result or ((c and 0x1f).toLong() shl shift)
            shift += 5
            if (c < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return value to i
    }

    private fun inRange(point: Pair<Double, Double>): Boolean =
        point.first in -90.0..90.0 && point.second in -180.0..180.0
}
