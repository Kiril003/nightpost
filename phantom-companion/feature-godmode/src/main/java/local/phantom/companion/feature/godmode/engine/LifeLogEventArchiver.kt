package local.phantom.companion.feature.godmode.engine

import local.phantom.companion.core.data.lifelog.LifeLogArchive

/**
 * Що подія GodMode лишає по собі в довічному архіві.
 *
 * PHANTOM памʼятає те, що йому сказали, а не те, що він підслухав. Шторка
 * сповіщень — це чужі слова, які ОС передає слухачеві: їх можна ПОКАЗАТИ,
 * але не можна ЗАПАМʼЯТАТИ. Тому сповіщення лишають тут лише конверт —
 * який застосунок і хто написав. Тіло повідомлення не потрапляє в архів
 * ніколи, а отже й у вектор [LifeLogArchive.searchMemories].
 *
 * What a GodMode event is allowed to leave in the lifelong archive.
 * Notifications leave an envelope — which app, which sender. Never the body.
 */
class LifeLogEventArchiver(
    private val lifeLog: LifeLogArchive,
    private val ownPackagePrefix: String = OWN_PACKAGE_PREFIX,
) {

    private var lastArchivedPkg: String? = null

    suspend fun onEvent(event: GodModeEvent) {
        when (event) {
            // Робоча памʼять TaskerEngine, не біографія.
            is GodModeEvent.ScreenSnapshot -> Unit

            is GodModeEvent.ScreenChanged -> {
                val pkg = event.pkg
                if (pkg.isNotBlank() &&
                    !pkg.startsWith(ownPackagePrefix) &&
                    pkg != lastArchivedPkg
                ) {
                    lastArchivedPkg = pkg
                    lifeLog.archiveEvent(TYPE_SCREEN_CHANGED, pkg)
                }
            }

            is GodModeEvent.Notification ->
                envelopeOf(event.pkg, event.title, event.text)
                    ?.let { lifeLog.archiveEvent(TYPE_NOTIFICATION, it) }

            is GodModeEvent.NotificationBurst ->
                burstEnvelopeOf(event.pkg, event.combinedText)
                    ?.let { lifeLog.archiveEvent(TYPE_NOTIFICATION, it) }
        }
    }

    /**
     * Прибирає сирий текст сповіщень, який цей шов писав до виправлення.
     * Ідемпотентна — нові рядки лежать під іншим типом, тож повторний
     * запуск на кожному старті нічого нового не зʼїдає.
     */
    suspend fun purgeRawNotificationRows(): Int =
        LEGACY_RAW_TYPES.sumOf { lifeLog.purgeType(it) }

    private fun envelopeOf(pkg: String, title: String, text: String): String? {
        val decision = NotificationClassifier.classify(
            packageName = pkg,
            channelId = null,
            title = title,
            text = text,
        )
        if (decision.classification == NotificationClassifier.Classification.SPAM) return null
        val sender = decision.redactedTitle.trim()
        return listOf(pkg.trim(), sender).filter { it.isNotEmpty() }.joinToString(SEP)
    }

    private fun burstEnvelopeOf(pkg: String, combinedText: String): String? {
        val decision = NotificationClassifier.classify(
            packageName = pkg,
            channelId = null,
            title = "",
            text = combinedText,
        )
        if (decision.classification == NotificationClassifier.Classification.SPAM) return null
        val count = combinedText.lineSequence().count { it.isNotBlank() }
        return "${pkg.trim()}${SEP}сповіщень: $count"
    }

    companion object {
        const val TYPE_SCREEN_CHANGED: String = "ScreenChanged"

        /** Конверт сповіщення. Навмисно НЕ «Notification» — див. [LEGACY_RAW_TYPES]. */
        const val TYPE_NOTIFICATION: String = "NotificationEnvelope"

        /**
         * Типи, під якими до виправлення лежав `event.toString()` — тобто
         * сирий заголовок і текст чужого повідомлення. Ці родини вилучені
         * назавжди, тому новий конверт і має інше імʼя.
         */
        val LEGACY_RAW_TYPES: List<String> = listOf("Notification", "NotificationBurst")

        private const val OWN_PACKAGE_PREFIX = "local.phantom.companion"
        private const val SEP = " · "
    }
}
