package local.phantom.companion.ui.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Що саме змінилось у журналі — замість голого «щось змінилось».
 *
 * Доти кожна зміна `mesh.outbox` кликала `__phantomMeshChanged`, і веб
 * перечитував увесь список і всю відкриту розмову; шматки одного фото давали
 * сотні таких штормів (MESSENGER_V2 §1.5). Тепер веб отримує, ЯКІ розмови й
 * ЯКІ рядки зачеплено, згладжено за [WINDOW_MS], і дочитує лише їх.
 */
object WebMessengerDeltas {

    const val WINDOW_MS = 50L

    class Touch {
        val convs = LinkedHashSet<String>()
        val lines = LinkedHashMap<String, LinkedHashSet<String>>()

        fun isEmpty() = convs.isEmpty()

        fun add(conv: String, lineId: String) {
            convs += conv
            lines.getOrPut(conv) { LinkedHashSet() } += lineId
        }

        fun addAll(other: Touch) {
            other.lines.forEach { (c, ids) -> ids.forEach { add(c, it) } }
            convs += other.convs
        }

        fun json(): String = buildJsonObject {
            put("convs", JsonArray(convs.map { JsonPrimitive(it) }))
            put("lines", buildJsonObject { lines.forEach { (c, ids) -> put(c, JsonArray(ids.map { JsonPrimitive(it) })) } })
        }.toString()
    }

    /** Розмова рядка: група — з конверта, людина — той, хто не я. */
    fun convOf(m: PeerMessage, me: String): String =
        PeerGroup.decode(m.body)?.group?.id ?: if (m.fromId == me) m.toId else m.fromId

    /**
     * Рядки, яких не було (за тотожністю обʼєкта — незмінений рядок журналу
     * лишається тим самим екземпляром), і рядки, що зникли.
     */
    fun touched(prev: List<PeerMessage>, next: List<PeerMessage>, me: String): Touch {
        val t = Touch()
        val before = Collections.newSetFromMap(IdentityHashMap<PeerMessage, Boolean>(prev.size * 2))
        before.addAll(prev)
        val nextIds = HashSet<String>(next.size * 2)
        next.forEach { m ->
            nextIds += m.id
            if (m !in before) t.add(convOf(m, me), m.id)
        }
        prev.forEach { m -> if (m.id !in nextIds) t.add(convOf(m, me), m.id) }
        cardsOfMarks(prev, next, me, before, nextIds, t)
        return t
    }

    /**
     * Позначка стану картки (`PH-CST:`) їде кадром 1:1 від автора, а картка
     * живе в ГРУПІ: без цього дельта називала б чат автора, веб не перечитував би
     * відкриту групу, і чужий «іду» зʼявлявся б лише після виходу з розмови й
     * повернення. Тому зміна такої позначки зараховується ще й картці, яку вона
     * стосується, у її розмові — під кожним id копії (на цьому апараті
     * рядок групи може мати id будь-якої з них).
     */
    private fun cardsOfMarks(
        prev: List<PeerMessage>,
        next: List<PeerMessage>,
        me: String,
        before: Set<PeerMessage>,
        nextIds: Set<String>,
        t: Touch,
    ) {
        val targets = LinkedHashSet<String>()
        next.forEach { m -> if (m !in before && PeerMark.isCardState(m.body)) PeerMark.decode(m.body)?.let { targets += it.targetId } }
        prev.forEach { m -> if (m.id !in nextIds && PeerMark.isCardState(m.body)) PeerMark.decode(m.body)?.let { targets += it.targetId } }
        if (targets.isEmpty()) return
        val byTarget = HashMap<String, MutableList<PeerMessage>>()
        (if (next.isEmpty()) prev else next).forEach { m ->
            if (!PeerMark.isMark(m.body)) byTarget.getOrPut(WebMessengerCardMarks.targetOf(m)) { mutableListOf() } += m
        }
        targets.forEach { target ->
            byTarget[target]?.forEach { card -> t.add(convOf(card, me), card.id) }
        }
    }

    /**
     * Перша зміна відчиняє вікно [WINDOW_MS]; усе, що прийде в ньому, їде
     * одним листом. Кликати з одного потоку (збирач потоку журналу).
     */
    class Smoother(
        private val scope: CoroutineScope,
        private val windowMs: Long = WINDOW_MS,
        private val emit: (Touch) -> Unit,
    ) {
        private var pending = Touch()
        private var timer: Job? = null
        private val lock = Any()

        fun offer(t: Touch) {
            if (t.isEmpty()) return
            synchronized(lock) {
                pending.addAll(t)
                if (timer?.isActive == true) return
                timer = scope.launch {
                    delay(windowMs)
                    val out = synchronized(lock) { pending.also { pending = Touch() } }
                    if (!out.isEmpty()) emit(out)
                }
            }
        }
    }

    /**
     * Що з рядків розмови веб уже має — щоб на дельту віддати лише різницю.
     * Опорою стає кожна повна видача; розмов тримається небагато.
     */
    class RowCache(private val keep: Int = 8) {
        class Diff(val upsert: List<String>, val remove: List<String>)

        private val served = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > keep
        }

        @Synchronized
        fun baseline(conv: String, rows: List<Pair<String, String>>) {
            served[conv] = rows.toMap()
        }

        /** null — опори немає, веб мусить узяти розмову цілком. */
        @Synchronized
        fun diff(conv: String, rows: List<Pair<String, String>>): Diff? {
            val was = served[conv] ?: return null
            val now = rows.toMap()
            served[conv] = now
            return Diff(
                upsert = rows.filter { (id, json) -> was[id] != json }.map { it.second },
                remove = was.keys.filter { it !in now },
            )
        }
    }
}
