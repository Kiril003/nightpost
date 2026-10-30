package local.phantom.companion.core.solana

/** Без блокування екрана підпису немає зовсім: ключ гаманця відчиняється лише після відбитка/PIN. */
object SigningConsent {

    suspend fun ask(hasScreenLock: Boolean, unlock: suspend () -> Boolean): Boolean = hasScreenLock && unlock()
}
