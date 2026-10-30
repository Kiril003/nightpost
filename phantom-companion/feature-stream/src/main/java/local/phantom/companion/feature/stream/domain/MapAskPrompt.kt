package local.phantom.companion.feature.stream.domain

/**
 * Текст, яким мапа розмовляє з моделлю.
 *
 * Живе в `domain`, а не поруч із аркушем, свідомо: це зміст запиту, а не хром
 * інтерфейсу, і в `strings.xml` йому теж не місце — перекладати системну рамку
 * означало б міняти поведінку моделі разом із мовою застосунку.
 */
object MapAskPrompt {

    /**
     * Системна рамка. «Без інструментів» тут — умова, а не побажання: виклик
     * `map.*` перекинув би камеру, тобто повернув би той самий пінг-понг
     * мапа→чат→мапа, від якого ця поверхня й тікає.
     *
     * Про довжину сказано окремо: цю відповідь читають однією рукою, стоячи
     * надворі, і чотири речення — стеля, а не мета.
     */
    const val SYSTEM: String =
        "Ти Nightpost, супутник екіпажу на мапі. Відповідай стисло й по суті: " +
            "2–4 речення, українською, без списків і без вступів на кшталт «звісно». " +
            "Оператор уже дивиться на мапу — не пропонуй йому щось на ній показати " +
            "і не викликай жодних інструментів. Чого не знаєш — не вигадуй, так і " +
            "скажи. Якщо питання про мережу закладів чи тип місця, поясни, що це, " +
            "і не вдавай, ніби знаєш, які саме поруч."

    /**
     * Питання разом із тим, що робить його питанням САМЕ ЦІЄЇ людини зараз.
     *
     * Без цих рядків відповідь однакова для оператора за рогом і за сорок
     * кілометрів, о третій ночі й опівдні. Кожен рядок опційний: немає позиції
     * — рядок не малюється, і вигадувати нічого.
     */
    fun of(
        question: String,
        here: Pair<Double, Double>? = null,
        locality: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String = buildString {
        locality?.takeIf { it.isNotBlank() }?.let {
            append(LABEL_LOOKING_AT).append(it).append("\n")
        }
        here?.let { (lat, lon) ->
            // Крапка, не кома: український локаль дав би «49,9720, 28,7957» —
            // три коми в рядку, який людина потім читає вголос у рацію.
            append(LABEL_COORDS)
                .append("%.4f, %.4f".format(java.util.Locale.US, lat, lon))
                .append("\n")
        }
        append(BriefingFacts.LABEL_NOW).append(BriefingFacts.clock(nowMs)).append("\n\n")
        append(LABEL_QUESTION).append(question)
    }

    /** Під яким рядком копія відповіді лягає в розмову, щоб історія читалась. */
    fun echoTitle(question: String): String = ECHO_PREFIX + question

    private const val LABEL_LOOKING_AT = "Оператор дивиться на: "
    private const val LABEL_COORDS = "Координати оператора: "
    private const val LABEL_QUESTION = "Питання оператора: "
    private const val ECHO_PREFIX = "З мапи: "
}
