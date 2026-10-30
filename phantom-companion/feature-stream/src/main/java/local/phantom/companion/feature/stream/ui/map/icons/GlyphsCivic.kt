package local.phantom.companion.feature.stream.ui.map.icons

// Держава, безпека, інфраструктура. Числа — одиниці сітки 24×24 (GlyphGrid),
// живе поле 2..22; y росте вниз.

/** Укриття: щит із дахом усередині — захист, а не просто «увага». */
internal fun GlyphPen.shelter() {
    polyClosed(12f, 2.8f, 20f, 6f, 20f, 12.4f, 12f, 21f, 4f, 12.4f, 4f, 6f)
    poly(8f, 13.6f, 12f, 9.8f, 16f, 13.6f)
}

/** Поліція: кашкет. Щит уже зайнятий укриттям, зірка — збереженим місцем. */
internal fun GlyphPen.police() {
    // Козирок мусить бути помітно ширшим за тулію, інакше силует читається як
    // накривка на мисці, а не як кашкет.
    polyClosed(6.6f, 13f, 7.6f, 7.6f, 16.4f, 7.6f, 17.4f, 13f)
    solidBox(6.6f, 11.6f, 10.8f, 1.6f)
    box(2.6f, 13f, 18.8f, 3.2f, 1.6f)
    dot(12f, 9.8f, 1.2f)
}

/** Блокпост: восьмикутник «стоп» із перекладиною. */
internal fun GlyphPen.checkpoint() {
    polyClosed(
        19.4f, 15.1f, 15.1f, 19.4f, 8.9f, 19.4f, 4.6f, 15.1f,
        4.6f, 8.9f, 8.9f, 4.6f, 15.1f, 4.6f, 19.4f, 8.9f,
    )
    line(7.6f, 12f, 16.4f, 12f)
}

/** Шлагбаум: стовп, стріла опущена, пʼята на землі. */
internal fun GlyphPen.barrier() {
    line(5f, 20.6f, 5f, 9f)
    dot(5f, 9f, 1.5f)
    line(6.8f, 9f, 21f, 9f)
    line(2.4f, 20.6f, 7.6f, 20.6f)
}

/** Небезпека: трикутник із знаком оклику — і єдиний знак «тривога без типу». */
internal fun GlyphPen.danger() {
    polyClosed(12f, 3.6f, 21.2f, 20f, 2.8f, 20f)
    line(12f, 9.6f, 12f, 14.6f)
    dot(12f, 17.4f, 1.1f)
}

/** ДТП: авто і розрив на ньому. Дві зустрічні стрілки читались як квітка. */
internal fun GlyphPen.accident() {
    polyClosed(2.6f, 18f, 2.6f, 14.4f, 4.6f, 11.4f, 12.4f, 11.4f, 14.4f, 14.4f, 14.4f, 18f)
    dot(5.2f, 18.6f, 1.4f)
    dot(11.8f, 18.6f, 1.4f)
    solid(*starPoints(cx = 16.4f, cy = 7f, outer = 5f, inner = 2f, points = 6))
}

/** Камера: корпус, обʼєктив, три промені спалаху. */
internal fun GlyphPen.camera() {
    box(3.4f, 7.4f, 13f, 9.2f, 1.6f)
    circle(9.9f, 12f, 2.8f)
    line(18f, 9.4f, 21f, 8f)
    line(18f, 12f, 21.4f, 12f)
    line(18f, 14.6f, 21f, 16f)
}

/** Лежачий поліцейський: дорога з горбом. */
internal fun GlyphPen.bump() {
    line(2.4f, 18f, 7f, 18f)
    arc(12f, 18f, 5f, 180f, 180f)
    line(17f, 18f, 21.6f, 18f)
}

/** Вогонь: ДСНС, пожежна частина. */
internal fun GlyphPen.fire() {
    polyClosed(
        12f, 2.8f, 16.6f, 9f, 17.6f, 14f, 12f, 21.2f,
        6.4f, 14f, 8.6f, 9.4f, 11f, 13f, 11.5f, 7.4f,
    )
}

/** Держустанова: фронтон, три колони, цоколь. ЦНАП, ратуша, суд, музей. */
internal fun GlyphPen.civic() {
    poly(2.8f, 9.2f, 12f, 4f, 21.2f, 9.2f)
    line(6.6f, 9.2f, 6.6f, 18.2f)
    line(12f, 9.2f, 12f, 18.2f)
    line(17.4f, 9.2f, 17.4f, 18.2f)
    line(2.8f, 20.4f, 21.2f, 20.4f)
}

/** Гроші: картка зі смугою. Банкомат, банк, обмін, пункт оплати. */
internal fun GlyphPen.card() {
    box(2.8f, 6.6f, 18.4f, 11.6f, 2f)
    solidBox(2.8f, 9.6f, 18.4f, 2.6f)
    line(6.2f, 15.2f, 10.4f, 15.2f)
}

/** Пошта: конверт. */
internal fun GlyphPen.post() {
    box(2.8f, 6.6f, 18.4f, 11.6f, 1.6f)
    poly(3.6f, 7.6f, 12f, 13.8f, 20.4f, 7.6f)
}

/** Поштомат / посилка: коробка з перевʼязкою. Хрест по центру давав вікно. */
internal fun GlyphPen.parcel() {
    box(3.4f, 6.8f, 17.2f, 13.2f, 1.4f)
    line(3.4f, 11.2f, 20.6f, 11.2f)
    line(12f, 11.2f, 12f, 20f)
}

/** Бібліотека: розгорнута книга. */
internal fun GlyphPen.book() {
    polyClosed(12f, 7.4f, 4f, 9f, 4f, 19.4f, 12f, 18.4f)
    polyClosed(12f, 7.4f, 20f, 9f, 20f, 19.4f, 12f, 18.4f)
}

/** Освіта: академічна шапочка. Школа, садок, університет. */
internal fun GlyphPen.school() {
    solid(12f, 5.4f, 21.6f, 9.6f, 12f, 13.8f, 2.4f, 9.6f)
    poly(7f, 11.8f, 7f, 16.4f)
    arc(12f, 16.4f, 5f, 0f, 180f)
    poly(17f, 11.8f, 17f, 16.4f)
    line(20.4f, 10.2f, 20.4f, 15.4f)
    dot(20.4f, 16.6f, 1f)
}

/** Храм: будівля з хрестом. Вузький корпус і тонкий хрест читались як пляшка. */
internal fun GlyphPen.church() {
    polyClosed(5.4f, 20.6f, 5.4f, 12.4f, 12f, 7.4f, 18.6f, 12.4f, 18.6f, 20.6f)
    line(12f, 2.6f, 12f, 7.4f)
    line(9f, 4.6f, 15f, 4.6f)
    box(10f, 15.6f, 4f, 5f, 0.4f)
}

/** Енергія: блискавка. Генератор, підстанція, трансформатор. */
internal fun GlyphPen.power() {
    solid(13.6f, 2.6f, 6f, 13.4f, 11f, 13.4f, 9.6f, 21.4f, 18f, 10f, 13f, 10f)
}

/** Звʼязок: три дуги й точка. */
internal fun GlyphPen.wifi() {
    arc(12f, 18f, 10.5f, 205f, 130f)
    arc(12f, 18f, 7f, 205f, 130f)
    arc(12f, 18f, 3.5f, 205f, 130f)
    dot(12f, 19f, 1.3f)
}

/** Промзона: пилчастий дах і корпус. */
internal fun GlyphPen.factory() {
    polyClosed(
        3f, 20.4f, 3f, 12.6f, 9f, 15.6f, 9f, 12.6f,
        15f, 15.6f, 15f, 8.6f, 21f, 8.6f, 21f, 20.4f,
    )
}
