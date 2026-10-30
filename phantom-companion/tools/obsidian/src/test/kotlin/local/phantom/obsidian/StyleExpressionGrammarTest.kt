package local.phantom.obsidian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ГРАМАТИКА ВИРАЗІВ — те, чого не видно ні в diff, ні на збірці.
 *
 * MapLibre читає БУДЬ-ЯКИЙ масив в аргументі виразу як вираз і бере перший
 * елемент за ім'я оператора. Голий `[6, 4]` там дає рівно один рядок у логові
 * (`[ParseStyle]: Expression name must be a string, but found number instead`)
 * і ТИХО викидає властивість цілком. Саме так десять шарів обсидіану — стежка,
 * тунель, ґрунтівка, шпали, перекрита дорога, кордони, болото, військова зона й
 * пересихаюча річка — малювались СУЦІЛЬНИМИ замість пунктиру: `line-dasharray`
 * не доїжджав до рушія жодного разу.
 *
 * Тест ходить тим самим маршрутом, що й парсер: лише АРГУМЕНТИ. Мітки `match`
 * і входи зупинок `step`/`interpolate` — літерали, і масив у них законний
 * (`["match", ["get","class"], ["wood","forest"], true, false]`).
 */
class StyleExpressionGrammarTest {

    /** Оператори style-spec. Незнайоме ім'я — теж провал: рушій скаже «Unknown expression». */
    private val operators = setOf(
        "let", "var", "literal", "array", "at", "in", "index-of", "slice", "case",
        "match", "coalesce", "step", "interpolate", "interpolate-hcl", "interpolate-lab",
        "==", "!=", ">", "<", ">=", "<=", "all", "any", "!", "within", "distance",
        "id", "geometry-type", "properties", "feature-state", "get", "has", "length",
        "number-format", "to-boolean", "to-color", "to-number", "to-string", "typeof",
        "boolean", "collator", "format", "image", "number", "object", "string",
        "rgb", "rgba", "to-rgba", "-", "*", "/", "%", "^", "+", "abs", "acos", "asin",
        "atan", "ceil", "cos", "e", "floor", "ln", "ln2", "log10", "log2", "max", "min",
        "pi", "round", "sin", "sqrt", "tan", "zoom", "heatmap-density", "line-progress",
        "accumulated", "is-supported-script", "upcase", "downcase", "concat",
        "resolved-locale", "linear", "exponential", "cubic-bezier", "hsl", "hsla",
        "none", "!in", "!has",
    )

    /** Індекси дітей, які парсер читає як вирази. Решта — літерали. */
    private fun argumentsOf(op: String, size: Int): List<Int> = when (op) {
        "literal" -> emptyList()
        // ["match", вхід, мітка, вихід, мітка, вихід, …, замовчування]
        "match" -> (listOf(1) + (3 until size step 2) + listOf(size - 1)).distinct().sorted()
        // ["step", вхід, замовчування, зупинка, вихід, …]
        "step" -> listOf(1, 2) + (4 until size step 2)
        // ["interpolate", крива, вхід, зупинка, вихід, …]
        "interpolate", "interpolate-hcl", "interpolate-lab" -> listOf(1, 2) + (4 until size step 2)
        else -> (1 until size).toList()
    }

    private fun typeOf(node: Json): String = when (node) {
        is Json.Str -> "string"
        is Json.Num -> "number"
        is Json.Bool -> "boolean"
        is Json.Arr -> "array"
        is Json.Obj -> "object"
    }

    private fun walk(node: Json, path: String, out: MutableList<String>) {
        val items = (node as? Json.Arr)?.items ?: return
        if (items.isEmpty()) return
        val head = items.first()
        if (head !is Json.Str) {
            out += "$path — ім'я оператора має бути рядком, а там ${typeOf(head)}: ${brief(node)}"
            return
        }
        if (head.value !in operators) {
            out += "$path — невідомий оператор «${head.value}»: ${brief(node)}"
            return
        }
        for (i in argumentsOf(head.value, items.size)) {
            if (i in items.indices) walk(items[i], "$path[$i]", out)
        }
    }

    private fun brief(node: Json): String = node.render().replace(Regex("\\s+"), " ").take(120)

    private fun isExpression(node: Json?): Boolean {
        val head = (node as? Json.Arr)?.items?.firstOrNull()
        return head is Json.Str && head.value in operators
    }

    @Test
    fun `every expression in every baked variant is legal grammar`() {
        val offenders = mutableListOf<String>()
        for (variant in Variant.entries) {
            for (layer in StyleCompiler.layers(variant)) {
                val id = (layer.at("id") as? Json.Str)?.value ?: "?"
                layer.at("filter")?.takeIf { isExpression(it) }
                    ?.let { walk(it, "${variant.slug}/$id.filter", offenders) }
                for (section in listOf("paint", "layout")) {
                    val block = layer.at(section) as? Json.Obj ?: continue
                    for ((prop, value) in block.entries) {
                        if (isExpression(value)) {
                            walk(value, "${variant.slug}/$id.$section.$prop", offenders)
                        }
                    }
                }
            }
        }
        assertEquals(
            "голий масив в аргументі виразу = властивість, якої на склі немає.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `a dash ramp hands the engine literals, not bare arrays`() {
        val ramp = dashRamp(6.0, 4.0, listOf(12.0, 16.0)) { 2.0 }
        val items = (ramp as Json.Arr).items
        assertEquals(jstr("step"), items[0])
        // Кожен ВИХІД кроку — обгорнутий масив; входи зупинок лишаються числами.
        val outputs = listOf(items[2]) + items.drop(3).filterIndexed { i, _ -> i % 2 == 1 }
        outputs.forEach {
            assertTrue("вихід пунктиру не обгорнутий: ${brief(it)}", isLiteralArray(it))
        }
        assertTrue(outputs.size >= 3)
    }

    private fun isLiteralArray(node: Json): Boolean {
        val items = (node as? Json.Arr)?.items ?: return false
        return items.size == 2 && items[0] == jstr("literal") && items[1] is Json.Arr
    }
}
