package local.phantom.companion.feature.stream.ui.map.icons.marks

import android.graphics.Bitmap
import local.phantom.companion.feature.stream.ui.map.world.WorldKind

/**
 * Тіла світу — та сама коробка, те саме світло, та сама товщина, що й у знаків
 * подій: ліва грань освітлена ([MarkTone.BONE]/[MarkTone.LIT]), права — у тіні
 * ([MarkTone.DEEP]/[MarkTone.SHADE]), силует — чорнило [MarkBox.INK_W]. Кожне
 * тіло впізнається обрисом із 24dp: хрест, колонка, кіоск, стовп зі знаком,
 * ґратка комірок, дві стулки, дім із блискавкою.
 */
internal object WorldFigures {

    fun draw(kind: WorldKind): MarkPen.() -> Unit = when (kind) {
        WorldKind.DANGER -> MarkKind.DANGER.draw
        WorldKind.CRASH -> MarkKind.CRASH.draw
        WorldKind.CHECKPOINT -> MarkKind.CHECKPOINT.draw
        WorldKind.SHELTER -> MarkKind.SHELTER.draw
        WorldKind.CAMERA -> MarkKind.CAMERA.draw
        WorldKind.LIFT_GATE -> MarkKind.GATE.draw
        WorldKind.PERSON -> local.phantom.companion.feature.stream.ui.map.peers.PeerBody.solid()
        WorldKind.HOSPITAL -> MarkPen::hospital
        WorldKind.PHARMACY -> MarkPen::pharmacy
        WorldKind.RESILIENCE -> MarkPen::resilience
        WorldKind.FUEL -> MarkPen::fuel
        WorldKind.ATM -> MarkPen::atm
        WorldKind.STOP -> MarkPen::stop
        WorldKind.POST -> MarkPen::post
        WorldKind.GATE -> MarkPen::swingGate
    }

    /**
     * Бурштин — лише [accent] (мета, укриття під тривогою). Корал — лише
     * небезпека й ДТП. Решта світу нейтральна: ідентичність несе силует.
     */
    fun palette(kind: WorldKind, isDark: Boolean, accent: Boolean): MarkPalette = when {
        // Небезпека лишається коралом і як мета: бурштин на ній збрехав би про неї.
        accent && kind != WorldKind.DANGER && kind != WorldKind.CRASH -> MarkPalette.accent(isDark)
        kind == WorldKind.PERSON -> MarkPalette.role(0xFFA78BFA.toInt(), 0xFF5B21B6.toInt(), 0xFF6D28D9.toInt(), isDark)
        else -> MarkKind.entries.firstOrNull { it.id == kind.id }?.let { MarkPalette.of(it, isDark) }
            ?: MarkPalette.neutral(isDark)
    }

    fun imageId(kind: WorldKind, isDark: Boolean, accent: Boolean): String =
        "mark-${kind.id}${if (accent) "-accent" else ""}-${if (isDark) "night" else "day"}"

    fun figure(kind: WorldKind, isDark: Boolean, accent: Boolean, density: Float): Bitmap =
        MarkPlate.bake(palette(kind, isDark, accent), density, draw(kind))
}

// ── аптека: крамниця з хрестом над входом ────────────────────────────────
private fun MarkPen.pharmacy() {
    poly(MarkTone.DEEP, 88f, 56f, 98f, 48f, 98f, 110f, 88f, 118f)
    box(MarkTone.BONE, 14f, 56f, 74f, 62f, 4f)
    box(MarkTone.PLATE, 14f, 56f, 74f, 13f, 4f)
    box(MarkTone.LIT, 22f, 78f, 18f, 16f, 2f)
    plane(MarkTone.INK, 50f, 80f, 74f, 80f, 74f, 118f, 50f, 118f)
    boxEdge(14f, 56f, 74f, 62f, 4f)
    disc(MarkTone.BONE, 51f, 30f, 20f)
    plate(MarkTone.PLATE, 45f, 17f, 12f, 26f)
    plate(MarkTone.PLATE, 38f, 24f, 26f, 12f)
    line(MarkTone.INK, MarkBox.SEAM_W, 51f, 50f, 51f, 56f)
}

// ── лікарня: висока будівля, великий хрест на фасаді ─────────────────────
private fun MarkPen.hospital() {
    poly(MarkTone.DEEP, 90f, 26f, 100f, 18f, 100f, 110f, 90f, 118f)
    box(MarkTone.BONE, 10f, 26f, 80f, 92f, 4f)
    box(MarkTone.PLATE, 10f, 26f, 80f, 12f, 4f)
    plate(MarkTone.PLATE, 42f, 44f, 16f, 34f)
    plate(MarkTone.PLATE, 33f, 53f, 34f, 16f)
    box(MarkTone.LIT, 16f, 86f, 16f, 12f, 2f)
    box(MarkTone.LIT, 68f, 86f, 16f, 12f, 2f)
    plane(MarkTone.INK, 40f, 92f, 60f, 92f, 60f, 118f, 40f, 118f)
    boxEdge(10f, 26f, 80f, 92f, 4f)
}

// ── пункт незламності: дім і блискавка — світло й тепло ─────────────────
private fun MarkPen.resilience() {
    poly(MarkTone.DEEP, 92f, 64f, 100f, 58f, 100f, 112f, 92f, 118f)
    poly(MarkTone.BONE, 16f, 64f, 92f, 64f, 92f, 118f, 16f, 118f)
    poly(MarkTone.PLATE, 8f, 68f, 54f, 26f, 100f, 68f, 92f, 72f, 54f, 38f, 16f, 72f)
    box(MarkTone.LIT, 22f, 80f, 16f, 14f, 2f)
    plane(MarkTone.INK, 70f, 88f, 86f, 88f, 86f, 118f, 70f, 118f)
    poly(MarkTone.INK, 56f, 70f, 40f, 96f, 50f, 96f, 44f, 116f, 64f, 88f, 53f, 88f, 60f, 70f)
    edge(16f, 64f, 92f, 64f, 92f, 118f, 16f, 118f)
}

// ── АЗС: колонка з екраном і пістолетом на шлангу ────────────────────────
private fun MarkPen.fuel() {
    poly(MarkTone.DEEP, 66f, 40f, 74f, 34f, 74f, 112f, 66f, 118f)
    box(MarkTone.BONE, 20f, 40f, 46f, 78f, 5f)
    box(MarkTone.INK, 28f, 50f, 30f, 20f, 2f)
    plate(MarkTone.LIT, 31f, 53f, 24f, 6f)
    box(MarkTone.PLATE, 14f, 108f, 58f, 10f, 2f)
    line(MarkTone.INK, MarkBox.INK_W, 66f, 60f, 84f, 60f, 90f, 70f, 90f, 100f, 80f, 106f)
    box(MarkTone.SHADE, 76f, 48f, 12f, 18f, 2f)
}

// ── банкомат: кіоск з екраном і прорізом для картки ──────────────────────
private fun MarkPen.atm() {
    poly(MarkTone.DEEP, 84f, 36f, 94f, 28f, 94f, 110f, 84f, 118f)
    box(MarkTone.BONE, 18f, 36f, 66f, 82f, 5f)
    box(MarkTone.PLATE, 18f, 36f, 66f, 10f, 5f)
    box(MarkTone.INK, 28f, 52f, 46f, 26f, 3f)
    plate(MarkTone.GLOW, 31f, 55f, 40f, 8f)
    line(MarkTone.INK, 5f, 34f, 88f, 68f, 88f)
    box(MarkTone.SHADE, 32f, 98f, 38f, 8f, 2f)
}

// ── зупинка: стовп зі знаком і навіс із лавою ────────────────────────────
private fun MarkPen.stop() {
    poly(MarkTone.SHADE, 52f, 58f, 100f, 58f, 100f, 112f, 52f, 112f)
    box(MarkTone.BONE, 48f, 52f, 56f, 10f, 2f)
    box(MarkTone.LIT, 56f, 94f, 42f, 8f, 2f)
    line(MarkTone.INK, MarkBox.SEAM_W, 60f, 102f, 60f, 114f)
    line(MarkTone.INK, MarkBox.SEAM_W, 94f, 102f, 94f, 114f)
    poly(MarkTone.BONE, 26f, 44f, 32f, 44f, 32f, 118f, 26f, 118f)
    box(MarkTone.PLATE, 8f, 10f, 42f, 38f, 8f)
    box(MarkTone.BONE, 15f, 18f, 28f, 18f, 4f)
    dot(MarkTone.INK, 20f, 38f, 3f)
    dot(MarkTone.INK, 38f, 38f, 3f)
}

// ── пошта: поштомат — ґратка комірок ─────────────────────────────────────
private fun MarkPen.post() {
    poly(MarkTone.DEEP, 94f, 34f, 102f, 28f, 102f, 112f, 94f, 118f)
    box(MarkTone.BONE, 12f, 34f, 82f, 84f, 4f)
    box(MarkTone.PLATE, 12f, 34f, 82f, 14f, 4f)
    line(MarkTone.INK, MarkBox.SEAM_W, 39f, 48f, 39f, 118f)
    line(MarkTone.INK, MarkBox.SEAM_W, 66f, 48f, 66f, 118f)
    line(MarkTone.INK, MarkBox.SEAM_W, 12f, 72f, 94f, 72f)
    line(MarkTone.INK, MarkBox.SEAM_W, 12f, 96f, 94f, 96f)
    dot(MarkTone.INK, 33f, 60f, 2.4f)
    dot(MarkTone.INK, 60f, 84f, 2.4f)
    dot(MarkTone.INK, 87f, 108f, 2.4f)
    boxEdge(12f, 34f, 82f, 84f, 4f)
}

// ── ворота: два стовпи й дві ґратчасті стулки ────────────────────────────
private fun MarkPen.swingGate() {
    poly(MarkTone.LIT, 22f, 60f, 54f, 64f, 54f, 112f, 22f, 108f)
    poly(MarkTone.SHADE, 54f, 64f, 86f, 60f, 86f, 108f, 54f, 112f)
    listOf(32f, 43f, 65f, 76f).forEach { x -> line(MarkTone.INK, MarkBox.SEAM_W, x, 62f, x, 110f) }
    poly(MarkTone.BONE, 10f, 40f, 22f, 40f, 22f, 118f, 10f, 118f)
    poly(MarkTone.BONE, 86f, 40f, 98f, 40f, 98f, 118f, 86f, 118f)
    box(MarkTone.PLATE, 8f, 34f, 16f, 8f, 2f)
    box(MarkTone.PLATE, 84f, 34f, 16f, 8f, 2f)
}
