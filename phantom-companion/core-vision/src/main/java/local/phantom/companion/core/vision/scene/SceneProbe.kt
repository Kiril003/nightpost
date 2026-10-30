package local.phantom.companion.core.vision.scene

/**
 * Один зонд рушія (розпізнавач тексту, сканер кодів, …) і його підсумок.
 *
 * Винесено з рушія навмисно й дослівно — так само, як `ImageInspector.combine`:
 * клієнти ML Kit створюються в полях, тож на чистій JVM самого рушія не
 * викликати, і правило «сліпий ≠ порожній» лишалось би без сторожа. А воно
 * головне: саме тут вирішується, побачить людина «нічого не знайшов» чи
 * «рушій не зміг подивитись».
 */
internal sealed interface Probe {
    data class Done(val found: List<Detection>) : Probe
    data class Failed(val reason: String) : Probe
    object Skipped : Probe
}

/**
 * [SceneLook.Blind] — лише коли ЖОДЕН із запитаних зондів не спрацював.
 * Один упав, другий відпрацював — це все ще погляд, просто вужчий.
 */
internal fun combine(probes: List<Probe>): SceneLook {
    val asked = probes.filterNot { it is Probe.Skipped }
    if (asked.isNotEmpty() && asked.all { it is Probe.Failed }) {
        return SceneLook.Blind((asked.first() as Probe.Failed).reason)
    }
    return SceneLook.Seen(asked.filterIsInstance<Probe.Done>().flatMap { it.found })
}
