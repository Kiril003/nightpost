package local.phantom.companion.feature.stream.ui.map.icons

// Рух: пальне, транспорт, залізо. Кожен вид транспорту має власний СИЛУЕТ,
// не власний колір — на мапі колір відбирає кільце піна, а вночі й під
// червоним світлом від кольору лишається тільки форма.

/** АЗС: колонка з пістолетом. */
internal fun GlyphPen.fuel() {
    box(3.6f, 4.6f, 10.4f, 16f, 1.6f)
    solidBox(5.8f, 7f, 6f, 4.2f, 0.7f)
    line(3.6f, 20.6f, 14f, 20.6f)
    poly(14f, 11f, 17.8f, 11f, 17.8f, 6.6f)
    dot(17.8f, 5f, 1.2f)
}

/** Зарядка EV: акумулятор із блискавкою. */
internal fun GlyphPen.ev() {
    box(3f, 6.8f, 15.4f, 10.4f, 1.6f)
    solidBox(19f, 10f, 2.2f, 4f, 0.7f)
    solid(11.8f, 8.2f, 7.6f, 12.6f, 10.2f, 12.6f, 9.2f, 15.8f, 13.6f, 11.4f, 11f, 11.4f)
}

/** Паркінг: «P» у полі — знак, який читається без мови. */
internal fun GlyphPen.parking() {
    box(3.6f, 3.6f, 16.8f, 16.8f, 3f)
    line(9.4f, 17f, 9.4f, 7f)
    line(9.4f, 7f, 13f, 7f)
    arc(13f, 9.5f, 2.5f, 270f, 180f)
    line(13f, 12f, 9.4f, 12f)
}

/** СТО: ріжковий ключ. */
internal fun GlyphPen.repair() {
    // Розріз губок дивиться ВІД руківʼя. Коли він дивився в бік руківʼя,
    // ручка його затуляла і ключ читався як лупа.
    arc(16.6f, 7.4f, 3.6f, 30f, 270f)
    line(14f, 10f, 6.2f, 17.8f)
}

/** Шиномонтаж: колесо. */
internal fun GlyphPen.tyre() {
    circle(12f, 12f, 8.4f)
    circle(12f, 12f, 3.4f)
    line(12f, 3.6f, 12f, 8.6f)
    line(12f, 15.4f, 12f, 20.4f)
    line(3.6f, 12f, 8.6f, 12f)
    line(15.4f, 12f, 20.4f, 12f)
}

/** Авто: кузов і колеса. Мийка, прокат, усе, що є машиною. */
internal fun GlyphPen.car() {
    polyClosed(3f, 17f, 3f, 12f, 6f, 8f, 18f, 8f, 21f, 12f, 21f, 17f)
    dot(7.2f, 17.6f, 1.7f)
    dot(16.8f, 17.6f, 1.7f)
}

/** Таксі: авто з ліхтарем на даху. */
internal fun GlyphPen.taxi() {
    car()
    solidBox(9.4f, 4.4f, 5.2f, 3f, 0.8f)
}

/** Автобус. Тролейбус носить той самий силует: різниця — у маршруті. */
internal fun GlyphPen.bus() {
    box(4f, 4.2f, 16f, 12.8f, 2.4f)
    solidBox(6.2f, 6.8f, 11.6f, 3.8f, 0.8f)
    dot(8f, 18.6f, 1.6f)
    dot(16f, 18.6f, 1.6f)
}

/** Трамвай: вужчий вагон, штанга під дротом, рейка. */
internal fun GlyphPen.tram() {
    box(6f, 6.4f, 12f, 12.2f, 2.4f)
    solidBox(7.8f, 8.8f, 8.4f, 3.4f, 0.8f)
    line(12f, 6.4f, 12f, 4f)
    line(7.6f, 4f, 16.4f, 4f)
    line(3f, 21f, 21f, 21f)
}

/** Метро: «M» у колі. */
internal fun GlyphPen.metro() {
    circle(12f, 12f, 8.6f)
    poly(8f, 15.6f, 8f, 8.4f, 12f, 13f, 16f, 8.4f, 16f, 15.6f)
}

/** Залізниця: лоб потяга над колією. */
internal fun GlyphPen.train() {
    box(5f, 3.4f, 14f, 14f, 3f)
    solidBox(7.4f, 6.4f, 9.2f, 4f, 0.8f)
    dot(8.6f, 14.6f, 1.1f)
    dot(15.4f, 14.6f, 1.1f)
    line(8f, 17.4f, 6.8f, 20.4f)
    line(16f, 17.4f, 17.2f, 20.4f)
    line(3.6f, 21.4f, 20.4f, 21.4f)
}

/** Зупинка: табличка на стовпі. */
internal fun GlyphPen.stop() {
    box(4.4f, 3f, 15.2f, 7.6f, 1.6f)
    solidBox(7f, 5.4f, 10f, 2.2f, 0.6f)
    line(12f, 10.6f, 12f, 21f)
    line(8.4f, 21.4f, 15.6f, 21.4f)
}

/** Порт: корпус, щогла, вітрило. */
internal fun GlyphPen.boat() {
    polyClosed(2.8f, 15.4f, 21.2f, 15.4f, 17.6f, 20.6f, 6.4f, 20.6f)
    line(12f, 15.4f, 12f, 3.6f)
    solid(12.8f, 5f, 18.6f, 13.4f, 12.8f, 13.4f)
}
