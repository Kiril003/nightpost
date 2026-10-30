package local.phantom.obsidian

/**
 * Компілятор стилю «Обсидіан».
 *
 * До нього мапа була чужим стилем, який чотири проходи перефарбовували на
 * пристрої: 111 шарів Liberty, порядок викликів як умова коректності, і
 * перемикання день/ніч ціною повного `setStyle` з ~70 бітмапами. Тут ті самі
 * рішення прийняті ОДИН раз, на JVM, і випікаються у файл.
 *
 * Варіант — не окремий стиль, а та сама програма з іншою полярністю токенів:
 * жодного `if (night)` у жодному шарі. Через це ніч і день не можуть розійтися
 * складом шарів, тільки чорнилом.
 */
enum class Variant(
    val slug: String,
    val title: String,
    val polarity: Polarity,
    /** Наскільки земля відступає під мережу; 0 — на повну. Див. [CartoInk.hush]. */
    val quiet: Double = 0.0,
) {
    NIGHT("obsidian-night", "PHANTOM Obsidian · Ніч", Polarity.NIGHT),
    DAY("obsidian-day", "PHANTOM Obsidian · День", Polarity.DAY),
    DUSK("obsidian-dusk", "PHANTOM Obsidian · Сутінки", Polarity.DUSK),

    /**
     * Мінімал — та сама денна мапа, з якої прибрано ГУЧНІСТЬ, а не зміст.
     *
     * Він НЕ окремий стиль і не окремий склад шарів: угіддя, зони й будівлі
     * лишаються на місці, просто відходять на півдороги до землі, а мережа,
     * вода й підписи стоять на повну. Через це «мінімал» не може мовчки
     * втратити шар — його склад звіряється з ніччю тим самим тестом.
     *
     * Половина — виміряна, не кругла для краси: на ній ліс падає з 1,80:1 до
     * 1,33:1 (тінь, а не масив), будівля з 1,61:1 до 1,26:1, а найконтрастнішим
     * на мапі стають рівно ті двоє, заради яких мінімал і беруть — касинг
     * дороги (1,79:1) і вода (1,77:1).
     */
    MINIMAL("obsidian-minimal", "PHANTOM Obsidian · Мінімал", Polarity.DAY, quiet = 0.5),
}

object StyleCompiler {

    /** Версія контракту айді шарів (див. `Ids.kt`). Росте, коли зникає або
     *  перейменовується якір, за який тримаються контрибуції застосунку. */
    const val CONTRACT = "1"

    /** Дані — OpenStreetMap, схема — OpenMapTiles. Ліцензія їде разом зі
     *  стилем, а не тільки в платівці приладів. */
    const val ATTRIBUTION = "© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap"

    /** Чорнило варіанта — один вивід на весь модуль, щоб тест не збирав своє. */
    fun ink(variant: Variant): CartoInk = CartoInk(Palette(variant.polarity), variant.quiet)

    fun layers(variant: Variant): List<Json.Obj> {
        val ink = ink(variant)
        return groundLayers(ink) + roadLayers(ink) + boundaryLayers(ink) + labelLayers(ink)
    }

    fun compile(variant: Variant): Json.Obj = jobj(
        "version" to jnum(8),
        "name" to jstr(variant.title),
        "metadata" to jobj(
            "phantom:contract" to jstr(CONTRACT),
            "phantom:generator" to jstr("tools/obsidian"),
            "phantom:variant" to jstr(variant.slug),
        ),
        "glyphs" to jstr(GLYPHS_URL),
        "sources" to jobj(
            SOURCE_ID to jobj(
                "type" to jstr("vector"),
                "url" to jstr(TILEJSON_URL),
                "attribution" to jstr(ATTRIBUTION),
            ),
        ),
        "layers" to jarr(layers(variant)),
    )

    fun bake(variant: Variant): String = compile(variant).render()
}
