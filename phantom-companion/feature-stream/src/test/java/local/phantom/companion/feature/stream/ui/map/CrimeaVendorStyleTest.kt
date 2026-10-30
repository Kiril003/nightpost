package local.phantom.companion.feature.stream.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.style.expressions.Expression

/**
 * Крим — Україна і в чужих стилях (Liberty, MapTiler, Stadia). Фільтри — з
 * `tiles.openfreemap.org/styles/liberty`, ознаки — з живих тайлів над
 * півостровом (20261018).
 */
class CrimeaVendorStyleTest {

    private val libertyDisputed = """["all",["!=",["get","maritime"],1],["==",["get","disputed"],1]]"""
    private val libertyState = """["==",["get","class"],"state"]"""
    private val libertyBorder =
        """["all",["==",["get","admin_level"],2],["!=",["get","maritime"],1],["!=",["get","disputed"],1],["!",["has","claimed_by"]]]"""

    private val ruIsthmus = mapOf("admin_level" to 2, "disputed" to 1, "maritime" to 0, "claimed_by" to "RU")
    private val naturalEarthIsthmus = mapOf("admin_level" to 2, "disputed" to 1, "maritime" to 0)
    private val uaRuLand = mapOf("admin_level" to 2, "disputed" to 0, "maritime" to 0)

    @Test
    fun `the occupation line is not drawn by the vendor's disputed layer`() {
        val f = narrowed(Expression.raw(libertyDisputed), RECOGNISED_BORDER)
        assertFalse(matches(f, ruIsthmus))
        assertFalse(matches(f, naturalEarthIsthmus))
    }

    @Test
    fun `the recognised border stays`() {
        assertTrue(matches(narrowed(Expression.raw(libertyBorder), RECOGNISED_BORDER), uaRuLand))
        assertTrue(matches(narrowed(null, RECOGNISED_BORDER), uaRuLand))
    }

    @Test
    fun `the occupation region label is gone and the autonomous republic stays`() {
        val f = narrowed(Expression.raw(libertyState), NOT_OCCUPATION_REGION)
        assertFalse(
            matches(f, mapOf("class" to "state", "name" to "Республика Крым", "name:uk" to "Республіка Крим", "name:en" to "Republic of Crimea")),
        )
        assertTrue(
            matches(f, mapOf("class" to "state", "name" to "Автономна Республіка Крим", "name:en" to "Autonomous Republic of Crimea")),
        )
    }

    private fun matches(e: Expression, p: Map<String, Any>): Boolean = eval(Json.parseToJsonElement(e.toString()), p) == true

    private fun same(a: Any?, b: Any?) = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    private fun eval(e: JsonElement, p: Map<String, Any>): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
        is JsonArray -> {
            val a = e.drop(1)
            when (val op = e.first().jsonPrimitive.content) {
                "get" -> p[a[0].jsonPrimitive.content]
                "has" -> p.containsKey(a[0].jsonPrimitive.content)
                "literal" -> eval(a[0], p)
                "all" -> a.all { eval(it, p) == true }
                "any" -> a.any { eval(it, p) == true }
                "!" -> eval(a[0], p) != true
                "==" -> same(eval(a[0], p), eval(a[1], p))
                "!=" -> !same(eval(a[0], p), eval(a[1], p))
                else -> error("оператор $op, якого тест не знає")
            }
        }
        else -> error("об'єкт у фільтрі")
    }
}
