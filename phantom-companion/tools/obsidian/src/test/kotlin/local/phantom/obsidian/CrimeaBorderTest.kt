package local.phantom.obsidian

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Крим — Україна (ГА ООН 68/262). Ознаки — з живих тайлів OpenFreeMap
 * 20261018 над півостровом, z3–z10.
 */
class CrimeaBorderTest {

    private val ruIsthmus = mapOf("admin_level" to 2, "disputed" to 1, "maritime" to 0, "claimed_by" to "RU", "disputed_name" to "РеспубликаКрым")
    private val ruSea = mapOf("admin_level" to 2, "disputed" to 1, "maritime" to 1, "claimed_by" to "RU", "disputed_name" to "РеспубликаКрым")
    private val ruSevastopol = mapOf("admin_level" to 4, "disputed" to 1, "maritime" to 0, "claimed_by" to "RU", "disputed_name" to "Севастополь")
    private val naturalEarthIsthmus = mapOf("admin_level" to 2, "disputed" to 1, "maritime" to 0)
    private val uaIsthmus = mapOf("admin_level" to 2, "disputed" to 1, "maritime" to 0, "claimed_by" to "UA", "disputed_name" to "Перекопськийрайон")
    private val uaSevastopol = mapOf("admin_level" to 4, "disputed" to 1, "maritime" to 0, "claimed_by" to "UA", "disputed_name" to "Севастополь")
    private val uaRuLand = mapOf("admin_level" to 2, "disputed" to 0, "maritime" to 0)
    private val kerchStrait = mapOf("admin_level" to 2, "disputed" to 0, "maritime" to 1, "adm0_r" to "RUS")
    private val oblast = mapOf("admin_level" to 4, "disputed" to 0, "maritime" to 0)

    private fun layers(v: Variant) = (StyleCompiler.compile(v).at("layers") as Json.Arr).items.map { it as Json.Obj }

    private fun drawnBy(v: Variant, sourceLayer: String, props: Map<String, Any>): List<String> =
        layers(v).filter { (it.at("source-layer") as? Json.Str)?.value == sourceLayer }
            .filter { f -> f.at("filter")?.let { truthy(eval(it, props)) } ?: true }
            .map { (it.at("id") as Json.Str).value }

    @Test
    fun `no claimed line is drawn as a state or sea border`() {
        for (v in Variant.entries) {
            for (line in listOf(ruIsthmus, ruSea, ruSevastopol, naturalEarthIsthmus)) {
                assertEquals("${v.slug}: $line", emptyList<String>(), drawnBy(v, SL_BOUNDARY, line))
            }
        }
    }

    @Test
    fun `ukraine's own borders stay, crimea's limits at region weight`() {
        for (v in Variant.entries) {
            assertEquals(listOf("obsidian-boundary-country"), drawnBy(v, SL_BOUNDARY, uaRuLand))
            assertEquals(listOf("obsidian-boundary-maritime"), drawnBy(v, SL_BOUNDARY, kerchStrait))
            assertEquals(listOf("obsidian-boundary-region"), drawnBy(v, SL_BOUNDARY, oblast))
            assertEquals(listOf("obsidian-boundary-region"), drawnBy(v, SL_BOUNDARY, uaIsthmus))
            assertEquals(listOf("obsidian-boundary-region"), drawnBy(v, SL_BOUNDARY, uaSevastopol))
        }
    }

    @Test
    fun `crimean places speak ukrainian and the occupation region is not named`() {
        val simferopol = mapOf("class" to "city", "name" to "Симферополь", "name:uk" to "Сімферополь", "name:en" to "Simferopol")
        val kerch = mapOf("class" to "city", "name" to "Керчь", "name:uk" to "Керч")
        val ruRegion = mapOf("class" to "state", "name" to "Республика Крым", "name:uk" to "Республіка Крим", "name:en" to "Republic of Crimea")
        for (v in Variant.entries) {
            for ((place, uk) in listOf(simferopol to "Сімферополь", kerch to "Керч")) {
                val ids = drawnBy(v, SL_PLACE, place)
                assertEquals("${v.slug}: ${place["name"]}", listOf("obsidian-label-city"), ids)
                val field = layers(v).first { (it.at("id") as Json.Str).value == ids.single() }.at("layout")!!.let { (it as Json.Obj).at("text-field")!! }
                assertEquals(uk, eval(field, place))
            }
            assertEquals("${v.slug}: «Республика Крым»", emptyList<String>(), drawnBy(v, SL_PLACE, ruRegion))
        }
    }

    private fun truthy(x: Any?) = x == true

    private fun num(x: Any?): Double? = (x as? Number)?.toDouble()

    private fun same(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    private fun eval(e: Json, p: Map<String, Any>): Any? = when (e) {
        is Json.Str -> e.value
        is Json.Num -> e.value
        is Json.Bool -> e.value
        is Json.Arr -> {
            val op = (e.items.first() as? Json.Str)?.value
            val a = e.items.drop(1)
            when (op) {
                "get" -> p[(a[0] as Json.Str).value]
                "has" -> p.containsKey((a[0] as Json.Str).value)
                "all" -> a.all { truthy(eval(it, p)) }
                "any" -> a.any { truthy(eval(it, p)) }
                "!" -> !truthy(eval(a[0], p))
                "==" -> same(eval(a[0], p), eval(a[1], p))
                "!=" -> !same(eval(a[0], p), eval(a[1], p))
                "<=" -> num(eval(a[0], p))?.let { l -> num(eval(a[1], p))?.let { l <= it } } ?: false
                ">=" -> num(eval(a[0], p))?.let { l -> num(eval(a[1], p))?.let { l >= it } } ?: false
                "coalesce" -> a.firstNotNullOfOrNull { eval(it, p) }
                "match" -> {
                    val input = eval(a[0], p)
                    val arms = a.drop(1)
                    var out: Any? = eval(arms.last(), p)
                    for (i in 0 until arms.size - 1 step 2) {
                        val labels = arms[i].let { if (it is Json.Arr) it.items else listOf(it) }.map { eval(it, p) }
                        if (labels.any { same(it, input) }) { out = eval(arms[i + 1], p); break }
                    }
                    out
                }
                else -> error("оператор $op у фільтрі, якого тест не знає")
            }
        }
        else -> null
    }
}
