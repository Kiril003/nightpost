package local.phantom.obsidian

/**
 * Найменший можливий емітер JSON — рівно стільки, скільки треба стилю MapLibre.
 *
 * Власний, а не kotlinx.serialization, з двох причин: модуль має лишатись без
 * жодної зовнішньої залежності, і випічка мусить бути ПОБАЙТОВО стабільною —
 * інакше кожен `bake` дає шум у diff і асет перестає бути перевіряним.
 */
sealed interface Json {
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data class Arr(val items: List<Json>) : Json
    data class Obj(val entries: Map<String, Json>) : Json
}

fun jstr(value: String): Json = Json.Str(value)
fun jnum(value: Number): Json = Json.Num(value.toDouble())
fun jbool(value: Boolean): Json = Json.Bool(value)
fun jarr(vararg items: Json): Json = Json.Arr(items.toList())
fun jarr(items: List<Json>): Json = Json.Arr(items)

/** Пари з `null`-значенням просто зникають — так необов'язкові поля стилю не
 *  доводиться обгортати умовами на кожному виклику. */
fun jobj(vararg pairs: Pair<String, Json?>): Json.Obj =
    Json.Obj(pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

operator fun Json.Obj.plus(other: Json.Obj): Json.Obj = Json.Obj(entries + other.entries)

fun Json.Obj.at(key: String): Json? = entries[key]

/** Ключі об'єктів сортуються, елементи масивів — ніколи: у стилі порядок шарів
 *  і порядок аргументів виразу несуть зміст. */
fun Json.render(): String = StringBuilder().also { write(it, 0) }.toString() + "\n"

private fun Json.write(out: StringBuilder, depth: Int) {
    when (this) {
        is Json.Str -> out.append(quote(value))
        is Json.Bool -> out.append(if (value) "true" else "false")
        is Json.Num -> out.append(number(value))
        is Json.Arr -> writeList(out, depth, "[", "]", items.size) { i, d -> items[i].write(out, d) }
        is Json.Obj -> {
            val keys = entries.keys.sorted()
            writeList(out, depth, "{", "}", keys.size) { i, d ->
                out.append(quote(keys[i])).append(": ")
                entries.getValue(keys[i]).write(out, d)
            }
        }
    }
}

private inline fun writeList(
    out: StringBuilder,
    depth: Int,
    open: String,
    close: String,
    size: Int,
    item: (Int, Int) -> Unit,
) {
    if (size == 0) {
        out.append(open).append(close)
        return
    }
    out.append(open).append('\n')
    val pad = "  ".repeat(depth + 1)
    for (i in 0 until size) {
        out.append(pad)
        item(i, depth + 1)
        if (i != size - 1) out.append(',')
        out.append('\n')
    }
    out.append("  ".repeat(depth)).append(close)
}

/** `2.0` у стилі — це `2`: інакше два прогони компілятора з різних гілок
 *  арифметики дають різний текст на однакових числах. */
internal fun number(value: Double): String {
    require(value.isFinite()) { "нескінченне число в стилі" }
    val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
    if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e15) return rounded.toLong().toString()
    return rounded.toString()
}

private fun quote(raw: String): String {
    val out = StringBuilder(raw.length + 2)
    out.append('"')
    for (c in raw) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
    }
    return out.append('"').toString()
}
