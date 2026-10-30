package local.phantom.companion.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Вікно між `startForegroundService()` і `startForeground()`.
 *
 * Поки воно відкрите, `stopService()` не прибирає службу тихо: система рахує
 * це за «не встигла вийти на передній план» і вбиває ПРОЦЕС цілим
 * (`ForegroundServiceDidNotStartInTimeException`). Заміряно 12.09 на
 * emulator-5554 — `Bringing down service while still waiting for start
 * foreground: PeerMeshService`, і наступним рядком FATAL на головному потоці.
 *
 * У тому вікні прибирати можна лише зсередини: доставити службі намір зупинки,
 * дати їй вийти на передній план і аж тоді `stopSelf()`.
 */
class ForegroundStartWindow {

    enum class StopKind { STOP_SERVICE, DELIVER_STOP }

    private val pending = AtomicBoolean(false)

    val open: Boolean get() = pending.get()

    fun startRequested() = pending.set(true)

    fun foregrounded() = pending.set(false)

    fun startRefused() = pending.set(false)

    fun stopKind(): StopKind =
        if (pending.getAndSet(false)) StopKind.DELIVER_STOP else StopKind.STOP_SERVICE
}
