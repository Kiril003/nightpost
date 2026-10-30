package local.phantom.companion.feature.stream.ui.map.icons

// Місця: їжа, торгівля, ночівля, природа, власні мітки оператора.

/** Їжа: виделка й ніж. */
internal fun GlyphPen.food() {
    poly(6.4f, 3.6f, 6.4f, 9.4f, 10.4f, 9.4f, 10.4f, 3.6f)
    line(8.4f, 9.4f, 8.4f, 20.4f)
    line(16.2f, 20.4f, 16.2f, 11.6f)
    polyClosed(16.2f, 11.6f, 16.2f, 3.8f, 18.8f, 7.4f, 18.8f, 11.6f)
}

/** Кава: чашка на блюдці. */
internal fun GlyphPen.cafe() {
    poly(4.6f, 8.8f, 6.2f, 18.4f, 15.4f, 18.4f, 17f, 8.8f)
    line(4.2f, 8.8f, 17.4f, 8.8f)
    arc(17f, 12.4f, 3f, -70f, 140f)
    line(3.4f, 21f, 19.4f, 21f)
}

/** Бар: келих. */
internal fun GlyphPen.bar() {
    polyClosed(4f, 4.8f, 20f, 4.8f, 12f, 14f)
    line(12f, 14f, 12f, 20.4f)
    line(8f, 20.6f, 16f, 20.6f)
}

/** Пекарня: хлібина з надрізами. Короткі надрізи читались як три крапки. */
internal fun GlyphPen.bread() {
    polyClosed(3.6f, 12.4f, 5.4f, 8.4f, 18.6f, 8.4f, 20.4f, 12.4f, 19.4f, 19.2f, 4.6f, 19.2f)
    line(9.4f, 10.6f, 7.2f, 16.6f)
    line(13f, 10.6f, 10.8f, 16.6f)
    line(16.6f, 10.6f, 14.4f, 16.6f)
}

/** Супермаркет: візок. */
internal fun GlyphPen.cart() {
    poly(2.6f, 4.6f, 5.6f, 4.6f, 8.6f, 16.4f, 18.6f, 16.4f)
    poly(6.4f, 8.6f, 21f, 8.6f, 18.6f, 16.4f)
    dot(9.6f, 19.6f, 1.5f)
    dot(17.4f, 19.6f, 1.5f)
}

/** Крамниця: вітрина під маркізою. Ринок, ТРЦ, будматеріали. */
internal fun GlyphPen.shop() {
    box(2.8f, 4.4f, 18.4f, 4.4f, 0.8f)
    poly(4.6f, 8.8f, 4.6f, 20.4f, 19.4f, 20.4f, 19.4f, 8.8f)
    box(9.4f, 13.6f, 5.2f, 6.8f, 0.5f)
}

/** Ночівля: ліжко. Готель, хостел, садиба. */
internal fun GlyphPen.bed() {
    line(3f, 6.4f, 3f, 19.6f)
    poly(3f, 12.4f, 20.6f, 12.4f, 20.6f, 19.6f)
    line(2.6f, 19.6f, 21.4f, 19.6f)
    solidBox(5f, 9.4f, 5.2f, 3f, 1f)
}

/** Зелень: дерево. Земля під стовбуром — щоб крона не читалась як кулька. */
internal fun GlyphPen.tree() {
    circle(12f, 9.4f, 6.6f)
    line(12f, 20.2f, 12f, 15.6f)
    line(8.6f, 20.8f, 15.4f, 20.8f)
}

/** Висота: вершини. */
internal fun GlyphPen.mountain() {
    polyClosed(2.6f, 20f, 9.4f, 6.8f, 14f, 14f, 16.6f, 10.4f, 21.4f, 20f)
}

/**
 * Переправа: висячий міст. Єдина категорія каталогу, для якої в родині не
 * знайшлось родича — ані «місце», ані «барʼєр» не кажуть «тут можна перейти».
 */
internal fun GlyphPen.bridge() {
    line(7.4f, 5.2f, 7.4f, 19.6f)
    line(16.6f, 5.2f, 16.6f, 19.6f)
    line(2.6f, 15.4f, 21.4f, 15.4f)
    poly(2.6f, 10.4f, 7.4f, 5.2f, 12f, 11.2f, 16.6f, 5.2f, 21.4f, 10.4f)
}

/** Оглядове: око. Панорама, атракція. */
internal fun GlyphPen.view() {
    polyClosed(
        2.8f, 12f, 7f, 7.4f, 12f, 5.8f, 17f, 7.4f, 21.2f, 12f,
        17f, 16.6f, 12f, 18.2f, 7f, 16.6f,
    )
    dot(12f, 12f, 2.4f)
}

/** Вода: крапля. Бювет, криниця, джерело, мийка. */
internal fun GlyphPen.water() {
    polyClosed(
        12f, 2.8f, 16f, 8.8f, 18f, 13.4f, 16.4f, 18f,
        12f, 20.6f, 7.6f, 18f, 6f, 13.4f, 8f, 8.8f,
    )
}

/** Людина: вбиральня і все, що вимірюється присутністю людини. */
internal fun GlyphPen.person() {
    circle(12f, 6.8f, 3.2f)
    polyClosed(5.6f, 20.6f, 7f, 14.6f, 10f, 12f, 14f, 12f, 17f, 14.6f, 18.4f, 20.6f)
}

/** Дім оператора. */
internal fun GlyphPen.home() {
    poly(2.6f, 12f, 12f, 3.6f, 21.4f, 12f)
    poly(5.6f, 10.4f, 5.6f, 20.4f, 18.4f, 20.4f, 18.4f, 10.4f)
    box(10f, 15f, 4f, 5.4f, 0.4f)
}

/** Робота: кейс. */
internal fun GlyphPen.work() {
    box(2.8f, 7.8f, 18.4f, 12.4f, 2f)
    poly(9f, 7.8f, 9f, 5.4f, 15f, 5.4f, 15f, 7.8f)
    line(2.8f, 13.4f, 21.2f, 13.4f)
}

/** Улюблене: зірка, залита. Єдиний повністю залитий гліф родини. */
internal fun GlyphPen.star() {
    solid(*starPoints(cx = 12f, cy = 12.4f, outer = 9.6f, inner = 4.2f, points = 5))
}

/** Звичайне місце: ромб із точкою. Хвіст таксономії, і жодного емодзі. */
internal fun GlyphPen.place() {
    polyClosed(12f, 4.4f, 19.6f, 12f, 12f, 19.6f, 4.4f, 12f)
    dot(12f, 12f, 2.2f)
}

/** Вхід: двері. Ворота, підʼїзд, прохід. */
internal fun GlyphPen.door() {
    box(6f, 3.4f, 12f, 17.2f, 1f)
    dot(15f, 12.4f, 1.2f)
    line(3.6f, 20.6f, 20.4f, 20.6f)
}

/**
 * Вихід: дверний отвір і стрілка З нього.
 *
 * Окремий знак, а не [door] у червоному, і причина в тому, КОЛИ його читають:
 * аварійний вихід шукають, коли треба вийти — часто поспіхом. Колір у цю мить
 * розрізняє мало (а для дальтоніка не розрізняє нічого), напрямок стрілки
 * розрізняє завжди. Той самий словник, що й на справжній табличці виходу.
 */
internal fun GlyphPen.exit() {
    box(3.4f, 3.4f, 9.2f, 17.2f, 1f)
    line(14f, 12f, 19.2f, 12f)
    solid(18.8f, 8.8f, 22f, 12f, 18.8f, 15.2f)
}

/** Сходи. Ескалатор носить їх само: під пальцем різниця в підписі, не в знаку. */
internal fun GlyphPen.stairs() {
    poly(
        2.8f, 20.4f, 8f, 20.4f, 8f, 16f, 13f, 16f,
        13f, 11.6f, 18f, 11.6f, 18f, 7.2f, 21.2f, 7.2f,
    )
}

/** Ліфт: кабіна зі стрілками. */
internal fun GlyphPen.elevator() {
    box(4f, 3.4f, 16f, 17.2f, 1.6f)
    line(9.4f, 15.2f, 9.4f, 8.6f)
    solid(7.8f, 10.2f, 9.4f, 7.6f, 11f, 10.2f)
    line(14.6f, 8.8f, 14.6f, 15.4f)
    solid(12.9f, 13.8f, 14.6f, 16.4f, 16.3f, 13.8f)
}
