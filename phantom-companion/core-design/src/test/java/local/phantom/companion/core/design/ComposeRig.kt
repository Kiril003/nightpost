package local.phantom.companion.core.design

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Справжня композиція без пристрою й без екрана.
 *
 * Потрібна там, де твердження звучить «цього більше немає в композиції». Такі
 * речі не доводяться прапорцем поруч: прапорець можна поставити й не зняти
 * анімацію. Тут стоїть [BroadcastFrameClock], і його [BroadcastFrameClock.hasAwaiters]
 * відповідає на єдине питання, що має значення для батареї — чи хтось у цій
 * композиції ще чекає на кадр.
 *
 * Аплаєр порожній: сюди беруться лише композабли БЕЗ вузлів UI.
 */
internal class ComposeRig {

    private class NoNodeApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(Dispatchers.Unconfined + clock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val composition = Composition(NoNodeApplier(), recomposer)

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
        composition.setContent(content)
        settle(0L)
    }

    /** Віддає один кадр і дає композиції догнати наслідки. */
    fun settle(frameTimeNanos: Long) {
        Snapshot.sendApplyNotifications()
        clock.sendFrame(frameTimeNanos)
        Snapshot.sendApplyNotifications()
    }

    /** Чи чекає ще щось у цій композиції на наступний кадр. */
    fun wantsFrames(): Boolean = clock.hasAwaiters

    fun dispose() {
        composition.dispose()
        recomposer.cancel()
        scope.cancel()
    }
}
