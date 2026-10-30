package local.phantom.companion.data.intent

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Єдине місце, звідки просять дозволи SMS: екран «Без інтернету» у вебі, після
 * людського пояснення. Міст кладе прохання сюди, екран застосунку
 * (WebMessengerHost) показує системне вікно. Більше ніде SMS не просять.
 */
object IntentPermit {
    private val asks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val requests: SharedFlow<Unit> = asks.asSharedFlow()

    fun ask(): Boolean = asks.tryEmit(Unit)

    val PERMISSIONS = arrayOf("android.permission.SEND_SMS", "android.permission.RECEIVE_SMS")
}
