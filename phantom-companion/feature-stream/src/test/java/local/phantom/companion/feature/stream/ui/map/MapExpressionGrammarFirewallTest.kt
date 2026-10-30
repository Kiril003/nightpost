package local.phantom.companion.feature.stream.ui.map

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.jar.JarFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyValue

/**
 * ТЕ САМЕ, ЩО [SymbolExpressionFirewallTest], АЛЕ НЕ ОЧИМА.
 *
 * Той тест читає ВИХІДНИК і тому сліпий рівно там, де вираз збирає помічник:
 * `textSize(classMatch(ramp(...)))` не містить у своєму тексті `Expression.zoom()`
 * взагалі. Саме так `zoom()` цілий вал сидів усередині `match` у голосі підписів —
 * а MapLibre відкидає `text-size` разом із такою формою, і драбина ролей не
 * діяла на жодному населеному пункті.
 *
 * Тут вираз ЗБИРАЄТЬСЯ і серіалізується тим самим `toArray()`, який їде в JNI,
 * і перевіряється та сама граматика, яку читає парсер рушія. Помічники в обхід
 * не пролазять — перевіряється результат, а не текст.
 *
 * GATE LAW: тест ходить рефлексією по зібраних класах пакета мапи, тож на валі
 * його треба запускати з `--rerun`.
 */
class MapExpressionGrammarFirewallTest {

    private val mapPackage = "local.phantom.companion.feature.stream.ui.map"

    /** Оператори style-spec. Незнайоме імʼя рушій відкине як «Unknown expression». */
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
    )

    /** Індекси дітей, які парсер читає як вирази. Мітки `match` і входи зупинок — літерали. */
    private fun argumentsOf(op: String, size: Int): List<Int> = when (op) {
        "literal" -> emptyList()
        "match" -> (listOf(1) + (3 until size step 2) + listOf(size - 1)).distinct().sorted()
        "step" -> listOf(1, 2) + (4 until size step 2)
        "interpolate", "interpolate-hcl", "interpolate-lab" -> listOf(1, 2) + (4 until size step 2)
        else -> (1 until size).toList()
    }

    private fun render(node: Any?): String = when {
        node == null -> "null"
        node is Array<*> -> node.joinToString(",", "[", "]") { render(it) }
        node is String -> "\"$node\""
        else -> node.toString()
    }

    private fun walk(node: Any?, path: String, where: String, out: MutableList<String>) {
        val items = node as? Array<*> ?: return
        if (items.isEmpty()) return
        val head = items[0]
        if (head !is String) {
            out += "$where$path — імʼя оператора мусить бути рядком, а там ${head?.javaClass?.simpleName}"
            return
        }
        if (head !in operators) {
            out += "$where$path — невідомий оператор «$head»"
            return
        }
        for (i in argumentsOf(head, items.size)) {
            if (i in items.indices) walk(items[i], "$path[$i]", where, out)
        }
    }

    /**
     * `zoom()` дозволено рівно там, де його чекає рушій: прямим входом
     * ЗОВНІШНЬОГО `interpolate`/`step`. Це `[2]` у interpolate і `[1]` у step,
     * і тільки на корені властивості.
     */
    private fun zoomOffenders(root: Any?, where: String, out: MutableList<String>) {
        val items = root as? Array<*> ?: return
        val op = items.firstOrNull() as? String
        val allowed = when (op) {
            "interpolate", "interpolate-hcl", "interpolate-lab" -> 2
            "step" -> 1
            else -> -1
        }
        items.forEachIndexed { i, child ->
            if (i == 0) return@forEachIndexed
            if (i == allowed && isZoom(child)) return@forEachIndexed
            if (containsZoom(child)) {
                out += "$where — zoom() сховано під «$op» на позиції $i: ${render(root).take(160)}"
            }
        }
    }

    private fun isZoom(node: Any?): Boolean {
        val items = node as? Array<*> ?: return false
        return items.size == 1 && items[0] == "zoom"
    }

    private fun containsZoom(node: Any?): Boolean {
        val items = node as? Array<*> ?: return false
        if (isZoom(items)) return true
        if (items.firstOrNull() == "literal") return false
        return items.any { containsZoom(it) }
    }

    private fun candidates(type: Class<*>): List<Any?> = when {
        type == Boolean::class.javaPrimitiveType -> listOf(true, false)
        type == Float::class.javaPrimitiveType -> listOf(1f)
        type == Double::class.javaPrimitiveType -> listOf(1.0)
        type == Int::class.javaPrimitiveType -> listOf(1)
        type == Long::class.javaPrimitiveType -> listOf(1L)
        type == String::class.java -> listOf("x")
        type == Expression::class.java -> listOf(Expression.literal(1))
        type.isArray && type.componentType == String::class.java ->
            listOf<Any?>(arrayOf("a", "b", "c", "d", "e", "f"))
        // Обидві форми пари, які тут узагалі бувають: (зум, кегель) і (клас,
        // барва). Невідповідна кидає всередині й тихо відпадає.
        type.isArray && type.componentType == Pair::class.java -> listOf<Any?>(
            arrayOf(1 to 1f, 5 to 5f),
            arrayOf("a" to 1, "b" to 2),
        )
        List::class.java.isAssignableFrom(type) -> listOf<Any?>(listOf(1 to 1f, 5 to 5f))
        type.isEnum -> listOfNotNull(type.enumConstants?.firstOrNull())
        kotlin.jvm.functions.Function1::class.java.isAssignableFrom(type) -> listOf(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ ->
                if (m.name == "invoke") Expression.literal(1) else null
            },
        )
        else -> emptyList()
    }

    private fun argSets(m: Method): List<Array<Any?>> {
        var out = listOf(emptyList<Any?>())
        for (p in m.parameterTypes) {
            val c = candidates(p)
            if (c.isEmpty()) return emptyList()
            out = out.flatMap { prefix -> c.map { prefix + it } }
        }
        return out.map { it.toTypedArray() }
    }

    private fun mapClassNames(): List<String> {
        val source = Class.forName("$mapPackage.EventMarksLayerKt")
            .protectionDomain.codeSource.location.toURI()
        val path = File(source)
        val prefix = mapPackage.replace('.', '/')
        val names = if (path.isDirectory) {
            path.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(path).path }
                .toList()
        } else {
            JarFile(path).use { jar -> jar.entries().toList().map { it.name } }
        }
        return names
            .map { it.replace(File.separatorChar, '/') }
            .filter { it.startsWith(prefix) && it.endsWith(".class") }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted()
    }

    /** Кожен вираз, який пакет мапи вміє зібрати без Style і без Context. */
    private fun builtExpressions(): List<Pair<String, Expression>> {
        val out = mutableListOf<Pair<String, Expression>>()
        for (name in mapClassNames()) {
            val cls = runCatching { Class.forName(name, true, javaClass.classLoader) }.getOrNull()
                ?: continue
            val instance = runCatching {
                cls.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
            }.getOrNull()
            val methods = runCatching { cls.declaredMethods }.getOrDefault(emptyArray())
            for (m in methods) {
                val rt = m.returnType
                val returnsExpression = Expression::class.java.isAssignableFrom(rt) ||
                    PropertyValue::class.java.isAssignableFrom(rt) ||
                    (rt.isArray && PropertyValue::class.java.isAssignableFrom(rt.componentType))
                if (!returnsExpression) continue
                val isStatic = Modifier.isStatic(m.modifiers)
                if (!isStatic && instance == null) continue
                if (!runCatching { m.isAccessible = true }.isSuccess) continue
                for (args in argSets(m)) {
                    val value = runCatching { m.invoke(if (isStatic) null else instance, *args) }
                        .getOrNull() ?: continue
                    val where = "${cls.simpleName}.${m.name}"
                    unwrap(value).forEach { out += where to it }
                }
            }
        }
        return out
    }

    private fun unwrap(value: Any): List<Expression> = when {
        value is Expression -> listOf(value)
        value is PropertyValue<*> -> listOfNotNull(value.value as? Expression)
        value is Array<*> -> value.filterIsInstance<PropertyValue<*>>()
            .mapNotNull { it.value as? Expression }
        else -> emptyList()
    }

    @Test
    fun `the firewall can still see the builders it was written for`() {
        // Рефлексія мовчить, коли не знаходить нічого, — і тоді зелений тест
        // означає рівно нічого. Два імені, на яких він уже ловив живу поломку.
        val seen = builtExpressions().map { it.first }.toSet()
        assertTrue("figureImage поза досяжністю — брандмауер осліп: $seen", seen.any { "figureImage" in it })
        assertTrue("placeVoice поза досяжністю — брандмауер осліп: $seen", seen.any { "placeVoice" in it })
    }

    @Test
    fun `every expression the map builds serialises to legal grammar`() {
        val built = builtExpressions()
        assertTrue("рефлексія не знайшла жодного виразу — тест осліп", built.size >= 10)
        val offenders = mutableListOf<String>()
        built.forEach { (where, e) -> walk(e.toArray(), "", where, offenders) }
        assertEquals(
            "голий масив в аргументі виразу = властивість, якої на склі немає " +
                "(«Expression name must be a string, but found number instead»).\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `zoom never hides inside a data expression`() {
        val offenders = mutableListOf<String>()
        builtExpressions().forEach { (where, e) -> zoomOffenders(e.toArray(), where, offenders) }
        assertEquals(
            "«zoom expression may only be used as input to a top-level step or " +
                "interpolate expression» — постав камерну криву ЗОВНІ, а вибір за " +
                "даними у виходи зупинок.\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
