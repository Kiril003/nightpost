package local.phantom.companion.data.nearby

import android.Manifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.NearbyCash
import local.phantom.companion.core.solana.NearbyState
import local.phantom.companion.core.solana.WalletAddressFrame

/** Дорога «телефон до телефона» без роутера. Сама нічого не шифрує й не вирішує. */
interface NearbyCourier : NearbyCash {
    /** Кадри людині [toId] поруч одним зʼєднанням, по черзі; повертає квитанції, які вона дала. */
    suspend fun handOff(toId: String, frames: List<String>): List<String>

    /** Квитанцію перевірено ключем пари — лише тепер «передано» правда. */
    fun landed() = Unit

    /** Купюра цією спробою не дійшла. До [landed] чи [missed] стан лишається «шукаємо»: між ними не блимає «не знайшли». */
    fun missed() = Unit
}

/** Збірка без цієї дороги або телефон без сервісів Google. */
class NoNearby(state: NearbyState = NearbyState.OFF) : NearbyCourier {
    override val state: StateFlow<NearbyState> = MutableStateFlow(state)
    override fun missingPermissions(): List<String> = emptyList()
    override fun wantedNow(): Boolean = false
    override fun listen() = Unit
    override suspend fun handOff(toId: String, frames: List<String>): List<String> = emptyList()
}

/**
 * Що показати про дорогу поруч. «Передано» — лише про останню спробу: стара вдала
 * передача не має світитися, поки нова ще шукає чи вже не вдалась. І «передано»
 * важить більше за вікно прийому, бо месенджер без інтернету слухає сам.
 */
class NearbyStatus {
    @Volatile var listening = false
        private set
    @Volatile private var searching = false
    @Volatile private var handed = false

    fun searching() {
        handed = false
        searching = true
    }

    fun missed() {
        searching = false
    }

    fun landed() {
        searching = false
        handed = true
    }

    fun listen(on: Boolean) {
        if (on) handed = false
        listening = on
    }

    fun state(): NearbyState = when {
        searching -> NearbyState.SEARCHING
        handed -> NearbyState.HANDED
        listening -> NearbyState.LISTENING
        else -> NearbyState.IDLE
    }
}

object NearbyPlan {

    /** Лише купюра і лише без інтернету: з інтернетом її довезе сховок. */
    fun worthTrying(body: String, online: Boolean): Boolean = !online && CashVoucher.isVoucher(body)

    /**
     * Адреса й кишеня, що ще не дійшли, їдуть разом із купюрою: без кишені отримувач
     * не звірить, чиєю вона підписана, і картка стане червоною.
     */
    fun ridesWithCash(body: String): Boolean = WalletAddressFrame.isAddress(body)

    /** Пошук триває до хвилини; між спробами радіо мовчить, щоб не гріти телефон. */
    const val PAUSE_MS = 30_000L

    fun due(lastTriedAtMs: Long?, nowMs: Long): Boolean = lastTriedAtMs == null || nowMs - lastTriedAtMs >= PAUSE_MS

    /** Runtime-дозволи Nearby Connections для цієї версії Android. */
    fun permissions(sdk: Int): List<String> = buildList {
        if (sdk <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdk >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}
