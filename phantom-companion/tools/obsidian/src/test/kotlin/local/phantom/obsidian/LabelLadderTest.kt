package local.phantom.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три вердикти власника по підписах, записані як храповики.
 *
 * «мапа засипана підписами», «лише номери будівель», «це схема, не мапа» — усі
 * три про одне: на екрані видно не те, що головніше. Тут прибиті три величини,
 * якими це міряється, і жодна з них не смак.
 *
 * 1. ДРАБИНА ЗІТКНЕНЬ. Хвіст символьних шарів = [LABEL_LADDER]. Порядок нічим
 *    іншим не заданий: переставлений шар не падає ні збіркою, ні виразом — він
 *    просто віддає місце назви вулиці назві вершини.
 * 2. ДРАБИНА ГОЛОСІВ. Контраст кожного щабля проти ВЛАСНОГО ореолу (ореол 1,2px
 *    і є безпосередня підкладка гаплованого тексту) — не нижче 4,5:1, і порядок
 *    щаблів однаковий у всіх чотирьох варіантах. Це й означає «один продукт у
 *    різному світлі»: до 16.09 денна вода мала 2,58:1 проти нічних 9,06 — тобто
 *    два різні продукти.
 * 3. СТЕЛЯ НОМЕРІВ. Скільки номерів будинків рушій фізично здатен покласти в
 *    кадр A52. Рушій не вміє обмежити кількість підписів; єдиний важіль —
 *    розмір коробки зіткнення, тобто `text-padding`.
 *
 * Межа перевірки названа прямо: тут міряються ВИРАЗИ, не пікселі, а стеля —
 * саме стеля (щільна упаковка), а не те, що рушій покладе на реальних даних.
 */
class LabelLadderTest {

    private fun layersOf(v: Variant) =
        (StyleCompiler.compile(v).at("layers") as Json.Arr).items.map { it as Json.Obj }

    private fun id(l: Json.Obj) = (l.at("id") as Json.Str).value

    private fun layer(v: Variant, id: String) = layersOf(v).first { id(it) == id }

    /* ── 1. Драбина зіткнень ─────────────────────────────────────────────── */

    @Test
    fun `the symbol tail is the ladder, in every variant`() {
        for (v in Variant.entries) {
            val tail = layersOf(v).filter { (it.at("type") as Json.Str).value == "symbol" }.map(::id)
            assertEquals("${v.slug}: хвіст підписів розійшовся з драбиною", LABEL_LADDER, tail)
        }
    }

    /**
     * Вершина не має права коштувати назви вулиці.
     *
     * До 16.09 пік стояв над `road`, `waterway` і `water`: найтихіше чорнило
     * стилю вигравало місце в того, за чим людина йде. Тримається окремо від
     * списку вище, бо це і є та пара, через яку список переписали.
     */
    @Test
    fun `a summit never outranks the street it stands over`() {
        val rank = LABEL_LADDER.withIndex().associate { (i, id) -> id to i }
        for (louder in listOf("obsidian-label-road", "obsidian-label-waterway", "obsidian-label-water")) {
            assertTrue(
                "пік знову виграє місце в $louder",
                rank.getValue("obsidian-label-peak") < rank.getValue(louder),
            )
        }
        assertEquals("номер будинку зсунувся з дна драбини", 0, rank.getValue(ID_FIRST_SYMBOL))
    }

    /* ── 2. Драбина голосів ──────────────────────────────────────────────── */

    /** Щабель голосу → шар, що ним говорить. Один представник на голос: решта
     *  шарів беруть ті самі кольори з [CartoInk]. */
    private val voices = listOf(
        "faint" to ID_FIRST_SYMBOL,
        "muted" to "obsidian-label-suburb",
        "water" to "obsidian-label-water",
        "road" to "obsidian-label-road",
        "ink" to "obsidian-label-town",
    )

    @Test
    fun `every label clears its own halo by 4_5 to 1, in every light`() {
        for (v in Variant.entries) {
            for (l in layersOf(v)) {
                val paint = l.at("paint") as? Json.Obj ?: continue
                val ink = (paint.at("text-color") as? Json.Str)?.value ?: continue
                val halo = (paint.at("text-halo-color") as Json.Str).value
                val got = contrast(ink, halo)
                assertTrue(
                    "${v.slug} → ${id(l)}: $ink на ореолі $halo дає $got:1 — дрібний текст під підлогою 4,5",
                    got >= 4.5,
                )
            }
        }
    }

    @Test
    fun `the four variants order their voices identically`() {
        val orders = Variant.entries.associate { v ->
            v.slug to voices.map { (name, layerId) ->
                val paint = layer(v, layerId).at("paint") as Json.Obj
                name to contrast(
                    (paint.at("text-color") as Json.Str).value,
                    (paint.at("text-halo-color") as Json.Str).value,
                )
            }
        }
        for ((slug, ladder) in orders) {
            for ((quiet, loud) in ladder.zipWithNext()) {
                assertTrue(
                    "$slug: ${quiet.first} (${quiet.second}:1) не тихіший за ${loud.first} (${loud.second}:1) — " +
                        "номер будинку переважає назву місця",
                    quiet.second < loud.second,
                )
            }
        }
    }

    /* ── 3. Стеля номерів будинків ───────────────────────────────────────── */

    // Кадр A52 у незалежних пікселях — тих самих, у яких рушій міряє коробку
    // зіткнення: 1080×2400 при щільності 2.625.
    private val frameArea = (1080 / 2.625) * (2400 / 2.625)

    /**
     * Стеля підписів шару в кадрі: площа кадру, поділена на коробку зіткнення.
     *
     * Коробка = габарит тексту, роздутий на `text-padding` з кожного боку
     * (style-spec: «size of the additional area around the text bounding box
     * used for detecting symbol collisions»). Ширина гліфа взята як 0.556 em —
     * цифрова ширина Noto Sans; висота рядка 1.2 × кегель.
     *
     * Це ВЕРХНЯ межа при щільній упаковці, а не прогноз: на реальному тайлі
     * номерів менше й лежать вони не сіткою. Але саме ця межа й зрушила з
     * місця, коли padding перестав бути сталою двійкою.
     */
    private fun ceiling(v: Variant, layerId: String, z: Double, chars: Int): Int {
        val layout = layer(v, layerId).at("layout") as Json.Obj
        val size = at(layout.at("text-size")!!, z)
        val pad = at(layout.at("text-padding")!!, z)
        val box = (chars * 0.556 * size + 2 * pad) * (1.2 * size + 2 * pad)
        return (frameArea / box).toInt()
    }

    @Test
    fun `housenumbers thin out at the zoom that asks where am I`() {
        // Три знаки — українська адреса середньої довжини («12», «125», «12а»).
        // Зі сталим padding=2 ці стелі були 1053 і 796: на z17 кадр міг узяти
        // тисячу номерів, і брав.
        val wide = ceiling(Variant.DAY, ID_FIRST_SYMBOL, 17.0, chars = 3)
        val deep = ceiling(Variant.DAY, ID_FIRST_SYMBOL, 19.0, chars = 3)
        assertTrue("на z17 у кадр знову лізе $wide номерів — мапа засипана ними", wide <= 260)
        assertTrue(
            "номери порідшали й на z19 ($deep) — на глибині питання саме «який це будинок»",
            deep >= 350,
        )
        assertTrue(
            "номери на z17 ($wide) не вдвічі рідші за z19 ($deep) — рампа сплющилась",
            wide * 2 <= deep,
        )
    }

    @Test
    fun `the housenumber still reaches the zoom that asks which house`() {
        val house = layer(Variant.DAY, ID_FIRST_SYMBOL)
        assertTrue("шар номерів обрізано зверху", house.at("maxzoom") == null)
        assertTrue(
            "номери починаються глибше z17 — рідшання не має права стати зникненням",
            (house.at("minzoom") as Json.Num).value <= 17.0,
        )
    }

    /* ── 4. Пріоритет назви дороги ───────────────────────────────────────── */

    /**
     * Класи доріг у порядку ЗРОСТАННЯ важливості, прочитані з геометрії стилю:
     * `obsidian-road-<клас>` тим пізніше в списку шарів, чим дорога головніша,
     * бо пізніший малюється поверх.
     */
    private val ROAD_CLASSES =
        setOf("path", "service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway")

    private fun geometryLadder(v: Variant): List<String> =
        layersOf(v).map(::id)
            .map { it.removePrefix("obsidian-road-") }
            .filter { it in ROAD_CLASSES }

    /** `match` по класу → ключ кожного класу. Останнє значення — замовчування. */
    private fun sortKeyByClass(node: Json): (String) -> Double {
        val items = (node as Json.Arr).items
        assertEquals("очікувався match по класу", jstr("match"), items.first())
        assertEquals(get("class"), items[1])
        val body = items.drop(2)
        val default = (body.last() as Json.Num).value
        val table = body.dropLast(1).chunked(2).flatMap { (cls, k) ->
            (cls as Json.Arr).items.map { (it as Json.Str).value to (k as Json.Num).value }
        }.toMap()
        return { cls -> table[cls] ?: default }
    }

    /**
     * Назва дороги важить рівно стільки, скільки важить її лінія.
     *
     * До 17.09 `obsidian-label-road` був ЄДИНИМ іменованим шаром стилю без
     * `symbol-sort-key`. У кадр A52 на z17 влазить 23 назви доріг, а тайл міста
     * віддає їх сотні — отже, які саме 23 побачить людина, вирішував порядок у
     * тайлі, а не важливість дороги. Товщина лінії весь цей час знала правильну
     * відповідь.
     *
     * Драбина береться з САМОГО стилю, а не з літерала тут: інакше два списки
     * важливості доріг розійшлися б мовчки.
     */
    @Test
    fun `the road label ranks by the same ladder its width does`() {
        for (v in Variant.entries) {
            val layout = layer(v, "obsidian-label-road").at("layout") as Json.Obj
            val node = layout.at("symbol-sort-key")
            assertTrue("${v.slug}: назва дороги знову без пріоритету — виграє порядок у тайлі", node != null)
            val keyOf = sortKeyByClass(node!!)

            // Геометрія росте знизу вгору, ключ падає: менший ключ виграє.
            val byImportance = geometryLadder(v).reversed()
            assertEquals(
                "${v.slug}: геометрія доріг більше не називає всі вісім щаблів",
                ROAD_CLASSES.size,
                byImportance.size,
            )
            for ((louder, quieter) in byImportance.zipWithNext()) {
                assertTrue(
                    "${v.slug}: $louder (ключ ${keyOf(louder)}) не виграє в $quieter " +
                        "(${keyOf(quieter)}) — назва проїзду важить як назва магістралі",
                    keyOf(louder) < keyOf(quieter),
                )
            }
        }
    }

    /* ── Читання ─────────────────────────────────────────────────────────── */

    private fun contrast(a: String, b: String): Double {
        val x = luma(a) + 0.05
        val y = luma(b) + 0.05
        return Math.round((if (x > y) x / y else y / x) * 100.0) / 100.0
    }

    /** Значення сталої або `interpolate` по зуму в точці. */
    private fun at(node: Json, z: Double): Double {
        if (node is Json.Num) return node.value
        val items = (node as Json.Arr).items
        assertEquals("очікувався interpolate по зуму", jstr("interpolate"), items.first())
        assertEquals(zoom(), items[2])
        val stops = items.drop(3).chunked(2).map { (s, v) -> (s as Json.Num).value to (v as Json.Num).value }
        if (z <= stops.first().first) return stops.first().second
        if (z >= stops.last().first) return stops.last().second
        val hi = stops.indexOfFirst { it.first >= z }
        val (z0, v0) = stops[hi - 1]
        val (z1, v1) = stops[hi]
        return v0 + (v1 - v0) * (z - z0) / (z1 - z0)
    }
}
