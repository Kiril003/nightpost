package local.phantom.companion.core.net.peer

/**
 * Правила того, ХТО приймає. Одна людина — одне положення, і жодного сліду.
 *
 * Історія тут неможлива не за домовленістю, а за типом: сховище — це
 * `Map<peerId, Standing>`, у ньому просто немає куди покласти другу точку тієї
 * самої людини. Це важливіше, ніж здається: список положень з мітками часу —
 * це маршрут чужого дня, і жоден екран, жодне «на всяк випадок» і жодна
 * майбутня хвиля не зможуть його зібрати з того, чого немає.
 *
 * Строк — межа листа, а не порада: протухле не лягає й тоді, коли прийшло
 * секунду тому, бо мить ВИМІРУ на тому кінці могла бути годину тому.
 */
object PeerLiveInbox {

    data class Standing(
        val peerId: String,
        val lat: Double,
        val lon: Double,
        val accuracyM: Int?,
        val atMs: Long,
        val expiresAtMs: Long,
        val seq: Int,
    )

    /**
     * Лист усередину. Повертає ВСЮ картину, бо саме заміна цілого — це те, що
     * робить накопичення неможливим.
     *
     * [fromId] береться з конверта, який уже перевірено підписом: покластись
     * на щось усередині тіла означало б дозволити одному контакту рухати пін
     * іншого.
     */
    fun absorb(
        standing: Map<String, Standing>,
        fromId: String,
        body: String,
        nowMs: Long,
    ): Map<String, Standing> {
        if (fromId.isBlank()) return standing
        if (PeerLive.isStop(body)) return standing - fromId
        val fix = PeerLive.decodeFix(body) ?: return standing
        if (fix.expiresAtMs <= nowMs) return standing
        val held = standing[fromId]
        // Скринька віддає листи не в тому порядку, у якому їх міряли: старіша
        // точка поверх новішої — це стрибок людини назад, тобто рух, якого не було.
        if (held != null && fix.atMs < held.atMs) return standing
        return standing + (
            fromId to Standing(
                peerId = fromId,
                lat = fix.lat,
                lon = fix.lon,
                accuracyM = fix.accuracyM,
                atMs = fix.atMs,
                expiresAtMs = fix.expiresAtMs,
                seq = fix.seq,
            )
            )
    }

    /** Такт дає споживач: власний годинник тут цокав би й при згорнутому екрані. */
    fun sweep(standing: Map<String, Standing>, nowMs: Long): Map<String, Standing> =
        standing.filterValues { it.expiresAtMs > nowMs }

    // ── надісланий маршрут ───────────────────────────────────────────────

    /**
     * Останній маршрут, який ця людина надіслала НАМ.
     *
     * Той самий закон, що й у положення, і з тієї самої причини: на людину
     * припадає рівно один маршрут, тож із прийнятого не збирається ані «де він
     * їздив», ані «куди збирався» за тиждень. Другий лист замінює перший, а не
     * лягає поруч.
     */
    data class StandingRoute(
        val peerId: String,
        val route: PeerLive.Route,
        /** Мить, коли лист приїхав. Строк живе всередині самого маршруту. */
        val arrivedAtMs: Long,
    )

    /**
     * Лист із маршрутом усередину. Протухле НЕ лягає навіть щойно прийняте:
     * маршрут складали на тому кінці, і строк відлічується від складання.
     */
    fun absorbRoute(
        routes: Map<String, StandingRoute>,
        fromId: String,
        body: String,
        nowMs: Long,
    ): Map<String, StandingRoute> {
        if (fromId.isBlank()) return routes
        val route = PeerLive.decodeRoute(body) ?: return routes
        if (route.expiresAtMs <= nowMs) return routes
        return routes + (fromId to StandingRoute(fromId, route, nowMs))
    }

    fun sweepRoutes(routes: Map<String, StandingRoute>, nowMs: Long): Map<String, StandingRoute> =
        routes.filterValues { it.route.expiresAtMs > nowMs }

    /**
     * Ідентифікатори рядків журналу, яких більше не має бути НІДЕ.
     *
     * Два приводи, і обидва обовʼязкові:
     *
     * — **строк вийшов.** Точка гасне на склі за [Standing.expiresAtMs], але
     *   лист, з якого вона зібралась, лежить у зашифрованому журналі на диску.
     *   Гасити лише малюнок означало б, що чуже положення пережило власний
     *   строк — просто там, куди людина не дивиться.
     *
     * — **обігнано.** Тік, за яким уже приїхав новіший, не варто ані везти,
     *   ані памʼятати: черга, у якій лежать усі тіки сеансу, — це слід чужого
     *   дня, зібраний саме тим кодом, що писався його не збирати. Лишається
     *   рівно один рядок на людину й напрямок.
     *
     * Обігнаний тік знімається з черги НАВІТЬ недоставленим, і це навмисне:
     * положення, що застаріло, доставляти нема сенсу.
     */
    fun spent(outbox: List<PeerMessage>, nowMs: Long): Set<String> {
        val cargo = outbox.filter { PeerLive.isCargo(it.body) }
        if (cargo.isEmpty()) return emptySet()
        val out = HashSet<String>()
        val newest = HashMap<Pair<String, String>, PeerMessage>()
        cargo.forEach { m ->
            val stop = PeerLive.decodeStop(m.body)
            if (stop != null) {
                // Відклик своє відпрацював, щойно точка, яку він гасить, однаково
                // померла б від власного строку. Довше він нічого не гасить.
                if (nowMs - stop >= PeerLiveShare.ROLLING_MS) out.add(m.id)
                return@forEach
            }
            val fix = PeerLive.decodeFix(m.body)
            if (fix == null || fix.expiresAtMs <= nowMs) {
                out.add(m.id)
                return@forEach
            }
            val lane = m.fromId to m.toId
            val standing = newest[lane]
            if (standing == null || m.sentAtMs > standing.sentAtMs) {
                newest[lane] = m
                if (standing != null) out.add(standing.id)
            } else {
                out.add(m.id)
            }
        }
        return out
    }
}
