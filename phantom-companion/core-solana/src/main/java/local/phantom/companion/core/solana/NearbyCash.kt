package local.phantom.companion.core.solana

import kotlinx.coroutines.flow.StateFlow

/** Купюра на телефон людини поруч, коли немає ні інтернету, ні спільного роутера. */
interface NearbyCash {
    val state: StateFlow<NearbyState>

    /** Чого бракує, щоб шукати поруч; порожньо — можна. */
    fun missingPermissions(): List<String>

    /** Чи є сенс питати дозволи саме зараз: поруч можливе, а інтернету немає. */
    fun wantedNow(): Boolean

    /** Вікно прийому: телефон оголошує себе знайомим поруч на кілька хвилин. */
    fun listen()
}

enum class NearbyState {
    /** Ця збірка поруч не передає — рядка на екрані немає зовсім. */
    OFF,
    /** Немає сервісів Google Play: лишається спільна Wi-Fi або сховок. */
    NO_SERVICES,
    NEEDS_PERMISSION,
    IDLE,
    LISTENING,
    SEARCHING,
    HANDED,
}
