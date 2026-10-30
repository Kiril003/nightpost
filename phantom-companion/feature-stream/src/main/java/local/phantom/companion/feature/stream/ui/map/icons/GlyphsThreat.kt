package local.phantom.companion.feature.stream.ui.map.icons

// Загрози. Значок тривоги читається на швидкості й у поганому світлі, тож
// тут силует майже завжди залитий: контур на 16dp у диску над областю
// перетворюється на сітку ліній, а залите тіло лишається тілом.

/** БПЛА: дельтоподібне крило «шахеда». Кіль відрізняє його від шеврона пука. */
internal fun GlyphPen.drone() {
    solid(12f, 3.2f, 21f, 18.2f, 12f, 14.6f, 3f, 18.2f)
    solidBox(11.2f, 14.2f, 1.6f, 6.4f, 0.4f)
}

/** Крилата ракета: корпус, крила, факел. Вузькі крила читались як стрілка. */
internal fun GlyphPen.missile() {
    solid(12f, 2.4f, 15.4f, 9.4f, 15.4f, 16.6f, 8.6f, 16.6f, 8.6f, 9.4f)
    solid(8.6f, 10.6f, 3.4f, 18.2f, 8.6f, 18.2f)
    solid(15.4f, 10.6f, 20.6f, 18.2f, 15.4f, 18.2f)
    solid(10.2f, 17.4f, 13.8f, 17.4f, 12f, 21.4f)
}

/** МіГ: літак зі стрілоподібним крилом. */
internal fun GlyphPen.jet() {
    solid(
        12f, 2.4f, 14f, 10f, 21.4f, 16f, 21.4f, 18.4f, 13.4f, 15.4f,
        13f, 19f, 16f, 21.4f, 12f, 20.4f, 8f, 21.4f, 11f, 19f,
        10.6f, 15.4f, 2.6f, 18.4f, 2.6f, 16f, 10f, 10f,
    )
}

/** КАБ: авіабомба з оперенням. */
internal fun GlyphPen.bomb() {
    solid(12f, 2.8f, 15.2f, 8.4f, 15.2f, 15.4f, 8.8f, 15.4f, 8.8f, 8.4f)
    poly(8.8f, 15.4f, 5.8f, 21.2f, 12f, 18.2f, 18.2f, 21.2f, 15.2f, 15.4f)
}

/** Артилерія: розрив. */
internal fun GlyphPen.burst() {
    solid(*starPoints(cx = 12f, cy = 12f, outer = 9.6f, inner = 3.8f, points = 8))
}

/** Балістика: крута траєкторія з падінням. */
internal fun GlyphPen.ballistic() {
    poly(2.8f, 21f, 4.4f, 12f, 9f, 5.4f, 14f, 4.4f, 17.6f, 8.2f, 18.6f, 13f, 19f, 16.4f)
    solid(19f, 21.4f, 16.4f, 16.4f, 21.4f, 16.4f)
}

/** Вуличні бої: схрещені клинки. */
internal fun GlyphPen.swords() {
    line(4.6f, 19.4f, 19.4f, 4.6f)
    line(19.4f, 19.4f, 4.6f, 4.6f)
    dot(4.6f, 19.4f, 1.4f)
    dot(19.4f, 19.4f, 1.4f)
}

/** Хімічна небезпека: колба з випаровуванням. */
internal fun GlyphPen.chemical() {
    polyClosed(9.4f, 3.6f, 9.4f, 9.6f, 4.4f, 19f, 19.6f, 19f, 14.6f, 9.6f, 14.6f, 3.6f)
    line(8.2f, 3.6f, 15.8f, 3.6f)
    dot(10.4f, 15.4f, 1.1f)
    dot(14f, 16.8f, 0.9f)
}

/** Радіація: трилисник. */
internal fun GlyphPen.nuclear() {
    dot(12f, 12f, 2.4f)
    listOf(-120f, 0f, 120f).forEach { start ->
        solid(*sectorPoints(cx = 12f, cy = 12f, rIn = 4f, rOut = 9.4f, startDeg = start - 30f, sweepDeg = 60f))
    }
}
