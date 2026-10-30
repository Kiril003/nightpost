package local.phantom.companion.core.net.pack

import local.phantom.companion.core.net.geosrc.SourceUnavailable

/**
 * Стан завантаження пака країни.
 *
 * Станів сім, а не «іде/не іде»: «нема адреси» нічим не лікується, «підпис не
 * сходиться» — привід НЕ пробувати ще раз, «мережа мовчить» — привід пробувати.
 * Одна пігулка «помилка» на всі була б тією ж брехнею, що й порожній шар
 * замість «джерело недоступне».
 */
sealed interface PackDownload {

    /** `phantom.packsBaseUrl` порожній: паки нізвідки брати, і це не збій. */
    data object NotConfigured : PackDownload

    /** Тягнемо манiфест і звіряємо підпис. Асети ще не чіпали. */
    data object Checking : PackDownload

    data class Downloading(
        val file: String,
        val percent: Int,
        val doneBytes: Long,
        val totalBytes: Long,
    ) : PackDownload

    /** Байти на диску, рахуємо sha256 і підміняємо каталог. */
    data object Verifying : PackDownload

    /** Є новіший пак; [PackFetcher.check] називає розмір, не витрачаючи його. */
    data class Available(val packId: String, val version: Int, val bytes: Long) : PackDownload

    /** [fresh] = приїхало зараз; false — на диску вже лежало найновіше. */
    data class Installed(val packId: String, val version: Int, val fresh: Boolean) : PackDownload

    data class Failed(val reason: PackFailure) : PackDownload
}

/**
 * Чому пак не приїхав. Мовчазної відмови не буває — як і в [SourceUnavailable],
 * причина типізована й показувана.
 */
sealed interface PackFailure {

    val detail: String

    /** Мережа, HTTP, ліміт частоти — словник геоджерел, не другий свій. */
    data class Source(val reason: SourceUnavailable) : PackFailure {
        override val detail: String get() = reason.detail
    }

    /** Підпис не сходиться або якоря довіри у збірці нема. Повтор не поможе. */
    data class Signature(val note: String) : PackFailure {
        override val detail: String get() = "підпис пака: $note"
    }

    /** Ворота відхилили пак за змістом: застара збірка, версія назад, ліцензія. */
    data class Refused(val note: String) : PackFailure {
        override val detail: String get() = note
    }

    /** Файл доїхав, але це не той файл, який обіцяв манiфест. */
    data class Corrupt(val file: String, val expected: String, val got: String) : PackFailure {
        override val detail: String get() = "«$file»: sha256 $got замість $expected"
    }

    /** Диск: місця нема, каталог не створюється, файл не пишеться. */
    data class Disk(val note: String) : PackFailure {
        override val detail: String get() = "диск: $note"
    }

    /** Оператор (або система) скасував завантаження. Вистояне лишається. */
    data object Cancelled : PackFailure {
        override val detail: String get() = "завантаження скасовано"
    }
}
