package local.phantom.companion.core.net.peer

/**
 * Приймач: на людину — одне положення, і воно живе лише до свого строку.
 *
 * Сховище — `Map<peerId, Standing>`: другої точки тієї самої людини покласти
 * нікуди, тож слід чужого дня не збирається навіть випадково. Тримається в
 * памʼяті процесу; на диск лягає хіба рядок журналу, і [spent] знімає його.
 */
object PeerWhereInbox {

    /** Стеля строку, хоч би що обіцяв відправник: чужий «назавжди» тут не діє. */
    const val MAX_TTL_MS = 30 * 60_000L

    /** Мить виміру з майбутнього понад це — не годинник, а підробка. */
    const val CLOCK_SKEW_MS = 2 * 60_000L

    data class Standing(
        val peerId: String,
        val position: PeerWhere.Position,
        val expiresAtMs: Long,
    )

    data class Inbox(
        val standing: Map<String, Standing> = emptyMap(),
        /** Мить останнього «стерти» від людини: старший кадр після неї не ляже. */
        val erasedAt: Map<String, Long> = emptyMap(),
    )

    /**
     * [allowed] — чи ця людина досі в реєстрі з ключем, під яким лист відкрився;
     * вирішує виклик, бо реєстр живе вище. [fromId] — з перевіреного конверта.
     */
    fun absorb(inbox: Inbox, fromId: String, body: String, nowMs: Long, allowed: Boolean): Inbox {
        if (fromId.isBlank()) return inbox
        val frame = PeerWhere.decode(body) ?: return inbox
        if (frame is PeerWhere.Erase) {
            val mark = maxOf(inbox.erasedAt[fromId] ?: 0L, frame.atMs)
            return Inbox(inbox.standing - fromId, inbox.erasedAt + (fromId to mark))
        }
        if (!allowed || frame !is PeerWhere.Position) return inbox
        if (frame.atMs > nowMs + CLOCK_SKEW_MS) return inbox
        val expires = minOf(frame.expiresAtMs, nowMs + MAX_TTL_MS)
        if (expires <= nowMs) return inbox
        if (frame.atMs <= (inbox.erasedAt[fromId] ?: 0L)) return inbox
        val held = inbox.standing[fromId]
        if (held != null && frame.atMs < held.position.atMs) return inbox
        return inbox.copy(standing = inbox.standing + (fromId to Standing(fromId, frame, expires)))
    }

    fun sweep(inbox: Inbox, nowMs: Long): Inbox = Inbox(
        standing = inbox.standing.filterValues { it.expiresAtMs > nowMs },
        erasedAt = inbox.erasedAt.filterValues { nowMs - it < MAX_TTL_MS + CLOCK_SKEW_MS },
    )

    /** Протухле не віддається навіть до наступного [sweep]. */
    fun standingFor(inbox: Inbox, peerId: String, nowMs: Long): Standing? =
        inbox.standing[peerId]?.takeIf { it.expiresAtMs > nowMs }

    fun live(inbox: Inbox, nowMs: Long): List<Standing> =
        inbox.standing.values.filter { it.expiresAtMs > nowMs }

    /**
     * Наскільки людина близько — на рівні того, що вона дала. Точку порівнюємо на
     * сітці кварталу, район — на сітці району. Метрів назовні немає.
     */
    fun nearness(myLat: Double, myLon: Double, standing: Standing): PeerWhereGrid.Nearness {
        val theirs = when (val p = standing.position) {
            is PeerWhere.Area -> p.cell
            is PeerWhere.Point -> PeerWhereGrid.cellOf(p.lat, p.lon, PeerWhereGrid.Level.QUARTER)
        }
        if (PeerWhereGrid.cellOf(myLat, myLon, theirs.level) == theirs) return PeerWhereGrid.Nearness.SAME
        // «Поруч чи далеко» — завжди на сітці району: за мірою кварталу 900 м уже «далеко».
        val (cLat, cLon) = PeerWhereGrid.centreOf(theirs)
        val district = PeerWhereGrid.Level.DISTRICT
        val far = PeerWhereGrid.nearness(
            PeerWhereGrid.cellOf(myLat, myLon, district),
            PeerWhereGrid.cellOf(cLat, cLon, district),
        ) == PeerWhereGrid.Nearness.FAR
        return if (far) PeerWhereGrid.Nearness.FAR else PeerWhereGrid.Nearness.NEAR
    }

    fun isAsk(body: String): Boolean = PeerWhere.decode(body) is PeerWhere.Ask

    /**
     * Рядки журналу, яких не має бути НІДЕ.
     *
     * Доставлене й прийняте — одразу: одержувач тримає точку в памʼяті, а копія
     * в журналі була б історією. Недоставлене лишається лише найсвіжіше в
     * кожному напрямку і лише доки воно ще щось означає.
     */
    fun spent(journal: List<PeerMessage>, nowMs: Long): Set<String> {
        val out = HashSet<String>()
        val newest = HashMap<Triple<String, String, Boolean>, PeerMessage>()
        journal.filter { PeerWhere.isWhere(it.body) }.forEach { m ->
            val frame = PeerWhere.decode(m.body)
            val dead = frame == null ||
                m.state == PeerMessage.State.DELIVERED ||
                m.state == PeerMessage.State.FAILED ||
                (frame is PeerWhere.Position && frame.expiresAtMs <= nowMs) ||
                (frame !is PeerWhere.Position && nowMs - frame.atMs >= MAX_TTL_MS)
            if (dead) {
                out.add(m.id)
                return@forEach
            }
            // «Де ти?» у своїй смузі: новіше питання не сміє зняти з черги «стерти».
            val lane = Triple(m.fromId, m.toId, frame is PeerWhere.Ask)
            val kept = newest[lane]
            if (kept == null || m.sentAtMs > kept.sentAtMs) {
                newest[lane] = m
                if (kept != null) out.add(kept.id)
            } else {
                out.add(m.id)
            }
        }
        return out
    }
}
