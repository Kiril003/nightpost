package local.phantom.companion.feature.stream.ui.map.icons

// Медицина. Аптека і лікарня несуть один і той самий хрест, але аптека — це
// САМ хрест, а лікарня — будівля з хрестом: у стресі різниця між «купити
// ліки» і «їхати в приймальне» має читатись за силуетом, не за підписом.

/** Аптека: хрест. */
internal fun GlyphPen.pharmacy() {
    polyClosed(
        9.4f, 3.4f, 14.6f, 3.4f, 14.6f, 9.4f, 20.6f, 9.4f,
        20.6f, 14.6f, 14.6f, 14.6f, 14.6f, 20.6f, 9.4f, 20.6f,
        9.4f, 14.6f, 3.4f, 14.6f, 3.4f, 9.4f, 9.4f, 9.4f,
    )
}

/** Лікарня: корпус із хрестом. */
internal fun GlyphPen.hospital() {
    box(3.4f, 5.4f, 17.2f, 15.2f, 2f)
    line(12f, 8.8f, 12f, 17.2f)
    line(7.8f, 13f, 16.2f, 13f)
}

/** Медпункт: пульс. Клініка, лікар, швидка. */
internal fun GlyphPen.pulse() {
    poly(2.8f, 12f, 7.4f, 12f, 9.4f, 6.4f, 12.6f, 17.6f, 14.6f, 12f, 21.2f, 12f)
}

/** Дефібрилятор: серце. */
internal fun GlyphPen.heart() {
    polyClosed(
        12f, 20.4f, 4.4f, 12.8f, 4.4f, 9.4f, 7f, 6.8f, 10f, 7.2f,
        12f, 9.8f, 14f, 7.2f, 17f, 6.8f, 19.6f, 9.4f, 19.6f, 12.8f,
    )
}

/** Стоматологія: зуб. */
internal fun GlyphPen.tooth() {
    polyClosed(
        6f, 5.6f, 9f, 4.2f, 12f, 5.2f, 15f, 4.2f, 18f, 5.6f, 18.4f, 11f,
        16f, 20.4f, 14.4f, 13.4f, 12f, 12.9f, 9.6f, 13.4f, 8f, 20.4f, 5.6f, 11f,
    )
}

/** Ветеринар: лапа. */
internal fun GlyphPen.paw() {
    dot(7.6f, 9.2f, 1.9f)
    dot(12f, 7.4f, 1.9f)
    dot(16.4f, 9.2f, 1.9f)
    solid(12f, 20.6f, 7.8f, 18.4f, 6.8f, 14.8f, 9.4f, 12.4f, 14.6f, 12.4f, 17.2f, 14.8f, 16.2f, 18.4f)
}
