package local.phantom.companion.feature.stream.ui.map.icons.marks

import local.phantom.companion.core.data.spatial.MARK_CLASS_CAMERA
import local.phantom.companion.core.data.spatial.MARK_CLASS_CHECKPOINT
import local.phantom.companion.core.data.spatial.MARK_CLASS_CRASH
import local.phantom.companion.core.data.spatial.MARK_CLASS_DANGER
import local.phantom.companion.core.data.spatial.MARK_CLASS_GATE
import local.phantom.companion.core.data.spatial.MARK_CLASS_SHELTER

/**
 * Шість тіл. Силует несе клас: ворота, будка, зімʼяте авто,
 * голова на щоглі, портал зі сходами вниз, гранована призма — вони різні
 * боковим зором, до кольору й до підпису. Це та властивість, заради якої
 * обрано «пін-фігурку», а не шість однакових дисків.
 *
 * Числа — авторська геометрія напрямку B (аркуші сесії, `marks/directions.py`),
 * перенесена як програма пера. Її не варто «підрівнювати»: кожен зсув тут
 * перевірявся на ×1 і на 36dp, і половина з них там, де інтуїція помиляється
 * (голова камери свідомо завелика, спалах ДТП свідомо домінує над кузовом).
 */
internal enum class MarkKind(val id: String, val draw: MarkPen.() -> Unit) {
    GATE(MARK_CLASS_GATE, MarkPen::gate),
    CHECKPOINT(MARK_CLASS_CHECKPOINT, MarkPen::checkpoint),
    CRASH(MARK_CLASS_CRASH, MarkPen::crash),
    CAMERA(MARK_CLASS_CAMERA, MarkPen::camera),
    SHELTER(MARK_CLASS_SHELTER, MarkPen::shelter),
    DANGER(MARK_CLASS_DANGER, MarkPen::danger),
    ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }

        /** Невідомий клас — [DANGER]: найслабше твердження, яке не бреше. */
        fun of(id: String?): MarkKind = BY_ID[id] ?: DANGER
    }
}

// ── ворота: смугастий брус на стовпах ────────────────────────────────────
private fun MarkPen.gate() {
    // Дальній стовп першим — брус мусить лягти поверх нього.
    poly(MarkTone.LIT, 15f, 64f, 27f, 64f, 27f, 116f, 15f, 116f)
    plane(MarkTone.SHADE, 22f, 64f, 27f, 64f, 27f, 116f, 22f, 116f)

    // Брус у ракурсі: дальній кінець тонший за завісний.
    val beam = floatArrayOf(76f, 58f, 76f, 79f, 16f, 68f, 16f, 55f)
    poly(MarkTone.BONE, *beam)
    stripes(MarkTone.PLATE, beam, 14f, 54f, 78f, 80f, count = 5, slant = -9f)
    edge(*beam)

    poly(MarkTone.BONE, 74f, 34f, 92f, 34f, 92f, 116f, 74f, 116f)
    plane(MarkTone.SHADE, 85f, 34f, 92f, 34f, 92f, 116f, 85f, 116f)
    poly(MarkTone.PLATE, 72f, 28f, 94f, 28f, 94f, 36f, 72f, 36f)
    disc(MarkTone.DEEP, 83f, 68f, 7f)
    dot(MarkTone.BONE, 83f, 68f, 2.4f)
}

// ── пункт пропуску: будка на 3/4 з бетонним блоком ─────────────────────────────
private fun MarkPen.checkpoint() {
    // Бетонний блок попереду будки — українська підпис під цим класом.
    poly(MarkTone.LIT, 4f, 98f, 26f, 98f, 30f, 116f, 1f, 116f)
    poly(MarkTone.PLATE, 12f, 44f, 76f, 44f, 94f, 31f, 30f, 31f)
    poly(MarkTone.SHADE, 74f, 44f, 92f, 31f, 92f, 102f, 74f, 116f)

    val front = floatArrayOf(20f, 44f, 74f, 44f, 74f, 116f, 20f, 116f)
    poly(MarkTone.BONE, *front)
    stripes(MarkTone.PLATE, front, 18f, 92f, 76f, 116f, count = 5, slant = 12f)
    edge(*front)

    box(MarkTone.INK, 29f, 55f, 36f, 26f, 2f)
    line(MarkTone.LIT, MarkBox.SEAM_W, 47f, 55f, 47f, 81f)
}

// ── ДТП: зімʼяте авто, колесо з осі, спалах ──────────────────────────────
private fun MarkPen.crash() {
    // Спалах мусить ДОМІНУВАТИ: на 108px два силуети авто за 200 м один від
    // одного — це один символ, а оператор мусить відрізнити екіпаж від аварії
    // без підпису.
    val body = floatArrayOf(
        26f, 98f, 26f, 88f, 36f, 84f, 46f, 82f, 56f, 70f,
        78f, 70f, 88f, 82f, 98f, 86f, 98f, 98f,
    )
    rotated(-7f, 62f, 88f) {
        poly(MarkTone.BONE, *body)
        clipped(body) { plate(MarkTone.SHADE, 20f, 87f, 90f, 8f) }
        edge(*body)
        thin(MarkTone.DEEP, MarkBox.SEAM_W, 58f, 74f, 76f, 74f, 84f, 84f, 56f, 84f)
        disc(MarkTone.INK, 84f, 100f, 11f)
        dot(MarkTone.LIT, 84f, 100f, 4.2f)
    }
    oval(MarkTone.INK, 24f, 110f, 11f, 5f)
    thin(MarkTone.SIGNAL, 3.4f, *markStar(36f, 66f, 27f, 11f, 8))
    plane(MarkTone.BONE, 30f, 60f, 43f, 64f, 33f, 74f)
}

// ── камера: голова на щоглі ──────────────────────────────────────────────
private fun MarkPen.camera() {
    // Голова свідомо завелика під щоглу: у пропорції жива камера на 108px —
    // це сіра паличка з крапкою.
    poly(MarkTone.DEEP, 34f, 108f, 72f, 108f, 78f, 118f, 28f, 118f)
    poly(MarkTone.BONE, 46f, 56f, 60f, 56f, 64f, 112f, 42f, 112f)
    plane(MarkTone.SHADE, 54f, 56f, 60f, 56f, 64f, 112f, 56f, 112f)
    rotated(-12f, 46f, 36f) {
        box(MarkTone.BONE, 14f, 22f, 66f, 30f, 5f)
        plate(MarkTone.SHADE, 14f, 40f, 66f, 12f)
        boxEdge(14f, 22f, 66f, 30f, 5f)
        box(MarkTone.PLATE, 8f, 9f, 76f, 12f, 4f)
        disc(MarkTone.INK, 26f, 37f, 12f)
        dot(MarkTone.PLATE, 26f, 37f, 5.4f)
        dot(MarkTone.BONE, 22.6f, 33.6f, 2.2f)
    }
    line(MarkTone.SIGNAL, MarkBox.INK_W, 9f, 56f, 1f, 63f)
    line(MarkTone.SIGNAL, MarkBox.INK_W, 5f, 44f, 0f, 45f)
}

// ── укриття: портал і сходи вниз ─────────────────────────────────────────
private fun MarkPen.shelter() {
    // Гермодвері зі штурвалом малювались першими і читались як ОПТИЧНИЙ
    // ПРИЦІЛ — концентричні кола зі спицями це приціл, хай що вони значать.
    poly(MarkTone.DEEP, 94f, 28f, 103f, 21f, 103f, 110f, 94f, 118f)
    box(MarkTone.BONE, 12f, 28f, 82f, 90f, 4f)
    box(MarkTone.PLATE, 12f, 28f, 82f, 18f, 4f)
    plane(MarkTone.INK, 26f, 46f, 80f, 46f, 80f, 118f, 26f, 118f)
    plane(MarkTone.GLOW, 26f, 46f, 80f, 46f, 80f, 58f, 26f, 58f)

    val steps = arrayOf(
        floatArrayOf(26f, 118f, 80f, 118f, 76f, 110f, 30f, 110f),
        floatArrayOf(30f, 110f, 76f, 110f, 72f, 102f, 34f, 102f),
        floatArrayOf(34f, 102f, 72f, 102f, 68f, 94f, 38f, 94f),
        floatArrayOf(38f, 94f, 68f, 94f, 64f, 86f, 42f, 86f),
    )
    steps.forEachIndexed { i, quad ->
        thin(if (i % 2 == 0) MarkTone.BONE else MarkTone.LIT, 2.4f, *quad)
    }
    boxEdge(12f, 28f, 82f, 90f, 4f)
    line(MarkTone.BONE, 4.4f, 44f, 34f, 53f, 41f, 62f, 34f)
}

// ── небезпека: гранована призма, єдина зі штриховкою ─────────────────────
private fun MarkPen.danger() {
    val front = floatArrayOf(48f, 24f, 88f, 112f, 10f, 112f)
    // Вектор глибини (14,-12): менший давав скалку, що читалась як артефакт
    // рендера, а не як бічна грань.
    poly(MarkTone.DEEP, 48f, 24f, 88f, 112f, 102f, 100f, 62f, 12f)
    line(MarkTone.INK, MarkBox.INK_W, 48f, 24f, 62f, 12f)
    poly(MarkTone.BONE, *front)
    stripes(MarkTone.PLATE, front, 6f, 86f, 94f, 112f, count = 6, slant = 13f)
    edge(*front)
    line(MarkTone.INK, 8.5f, 48f, 52f, 48f, 74f)
    dot(MarkTone.INK, 48f, 83f, 4.8f)
}

/**
 * Брус воріт — окрема картинка на ту саму фічу.
 *
 * Питання «де саме шлагбаум перетинає дорогу» білборд не має чим відповісти:
 * фігурка стоїть до ОКА, а не до дороги. Брус лягає по мапі й повертається за
 * курсом шляху; поки курсу немає — його не малюють узагалі, бо брус без курсу
 * збрехав би про орієнтацію. Малюється горизонтально: `iconRotate = bearing`
 * доводить його впоперек шляху, а не вздовж.
 */
internal object MarkBar {
    const val W = 108f
    const val H = 28f
    const val W_DP = 36f
    const val H_DP = 9.33f

    fun draw(pen: MarkPen) {
        val beam = floatArrayOf(6f, 8f, 102f, 8f, 102f, 20f, 6f, 20f)
        pen.poly(MarkTone.BONE, *beam)
        pen.stripes(MarkTone.PLATE, beam, 4f, 6f, 104f, 22f, count = 7, slant = 7f)
        pen.edge(*beam)
    }
}
