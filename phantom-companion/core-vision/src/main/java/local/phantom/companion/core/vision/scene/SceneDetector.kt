package local.phantom.companion.core.vision.scene

/**
 * Що рушій побачив за один погляд.
 *
 * Той самий урок, що вже записаний у `ImageInspector`, і його доводиться
 * тримати вдруге, бо тут ціна вища: порожній [Seen] — це ВИМІР («дивився,
 * не знайшов нічого»), а [Blind] — збій. Якщо їх звести в один порожній
 * список, розділ малюватиме чисте скло і над зламаним рушієм, і над порожньою
 * стіною, а людина вирішить, що камера просто нічого не бачить.
 */
sealed interface SceneLook {
    data class Seen(val objects: List<Detection>) : SceneLook
    data class Blind(val reason: String) : SceneLook
}

/**
 * Один погляд у кадр — і нічого про те, ЧИМ дивитись.
 *
 * Сенс цієї межі в тому, що заміна двигуна не переписує розділ: сьогодні за
 * нею ML Kit, який уже на пристрої, завтра — ONNX із кузні. Екран, трекер і
 * накладання про це не знають нічого.
 */
interface SceneDetector {

    /** Кого цей рушій узагалі вміє бачити. Розділ не пропонує того, чого немає. */
    val kinds: Set<ObjectKind>

    suspend fun look(frame: VisionFrame): SceneLook

    fun close()
}
