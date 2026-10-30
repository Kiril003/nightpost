package local.phantom.companion.feature.stream.ui.map

/**
 * Відповідь PHANTOM на питання, поставлене З МАПИ.
 *
 * Досі рядок «Запитати PHANTOM про «х»» у пошуку кликав `onAskAi`, а той —
 * `onAskDialogue`, тобто забирав оператора зі стрічки мапи в стрічку розмови.
 * Людина питала про те, на що дивиться, і в нагороду втрачала те, на що
 * дивилась. Власник назвав це серед випадків «мене з мапи вибило».
 *
 * Картка місця цю задачу вже розвʼязала правильно ([dossier.DossierBriefing]):
 * токени течуть у власну поверхню, копія лягає в розмову як історія. Тут те
 * саме для вільного питання, і навмисно окремий тип: у картки завжди є місце,
 * у питання його може не бути зовсім.
 *
 * Текст, яким це питання йде до моделі, живе в
 * [local.phantom.companion.feature.stream.domain.MapAskPrompt] — тут лише стан.
 */
internal data class MapAnswer(
    /** Про що спитали — рівно те, що набрала людина. */
    val question: String = "",
    val text: String = "",
    val phase: Phase = Phase.IDLE,
    /** Чесний текст невдачі; null поки нічого не зламалось. */
    val error: String? = null,
) {
    enum class Phase { IDLE, THINKING, STREAMING, DONE, FAILED }

    val isBusy: Boolean get() = phase == Phase.THINKING || phase == Phase.STREAMING
    val hasText: Boolean get() = text.isNotBlank()

    /** Чи є взагалі що показувати — аркуш живе рівно доти, доки це істина. */
    val isLive: Boolean get() = phase != Phase.IDLE
}
