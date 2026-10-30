package local.phantom.obsidian

import java.io.File

/**
 * Випічка асетів. Викликається `:tools:obsidian:bake`, аргумент — тека
 * `feature-stream/src/main/assets/styles`.
 *
 * Файли комітяться: асет — це офлайн-фолбек, і мапа мусить піднятись на
 * пристрої, який ще жодного разу не бачив мережі.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: error("bake: не передано теку асетів"))
    out.mkdirs()
    for (variant in Variant.entries) {
        val file = File(out, "${variant.slug}.json")
        val text = StyleCompiler.bake(variant)
        val changed = !file.exists() || file.readText() != text
        if (changed) file.writeText(text)
        println("${file.path}: ${StyleCompiler.layers(variant).size} шарів${if (changed) "" else " (без змін)"}")
    }
}
