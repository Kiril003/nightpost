package local.phantom.companion.core.voice.duplex

/**
 * κ — скільки нашого власного голосу повертається в мікрофон на цьому
 * конкретному телефоні.
 *
 * У воротаря перехоплення це число було константою 0.75 із приміткою «треба
 * проміряти на пристрої». Геометрія динаміка й мікрофона в кожного апарата
 * своя: 0.75 на одному — правда, на іншому — втрачена чутливість (людину не
 * чути, поки вона не закричить).
 *
 * Міряти можна без жодного каліброваного гудка: поки PHANTOM говорить і
 * ніхто його не перебиває, у мікрофоні САМЕ його відлуння, і κ — це просто
 * відношення рівнів. Оцінка асиметрична навмисне: вгору швидко, вниз
 * повільно. Недооцінити κ — це поріг нижче власного ехо і петля
 * самоперебивання; переоцінити — лише втрачена чутливість. Одна з цих
 * помилок ламає застосунок, друга — ні.
 *
 * Чистий клас: без Android, без годинника.
 */
class EchoCouplingProbe(
    private val fallback: Float = 0.75f,
    /** Тихі кадри нічого не кажуть про звʼязок — ділення на шум. */
    private val minOutput: Float = 0.15f,
    private val riseAlpha: Float = 0.35f,
    private val fallAlpha: Float = 0.01f,
    private val minSamples: Int = 60,
    private val floor: Float = 0.05f,
    private val ceiling: Float = 1.5f,
) {

    private var estimate = 0f
    private var samples = 0

    /**
     * Один кадр, знятий, поки PHANTOM говорить і воротар ще НЕ спрацював.
     * Кадри після спрацювання не годяться: там уже може бути людина.
     */
    fun observe(micLevel: Float, outputLevel: Float) {
        if (outputLevel < minOutput) return
        val ratio = (micLevel / outputLevel).coerceIn(0f, ceiling)
        estimate = if (ratio > estimate) {
            estimate + (ratio - estimate) * riseAlpha
        } else {
            estimate + (ratio - estimate) * fallAlpha
        }
        if (samples < Int.MAX_VALUE) samples++
    }

    /**
     * Проміряне κ, або запасне значення, поки доказів замало. Ніколи не
     * повертає нуль: пристрій, який чомусь не чує себе взагалі, не має
     * отримати поріг, який ловить кожен шурхіт.
     */
    fun coupling(): Float =
        if (samples < minSamples) fallback else estimate.coerceIn(floor, ceiling)

    /** Чи спирається число на вимірювання, а не на здогад. */
    val measured: Boolean get() = samples >= minSamples

    /** Для діагностики на пристрої — саме це число варто друкувати в лог. */
    fun snapshot(): String {
        val value = "%.2f".format(coupling())
        val seen = samples
        val need = minSamples
        return if (measured) "κ=$value · проміряно на $seen кадрах"
        else "κ=$value · запасне, зібрано $seen з $need"
    }

    fun reset() {
        estimate = 0f
        samples = 0
    }
}
