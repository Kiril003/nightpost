package local.phantom.companion.feature.precision

/**
 * Постачальник стану позиціонування.
 *
 * Навмисно вузький контракт: екран не має знати ні про `LocationManager`, ні
 * про модулі `:core-gnss`/`:core-indoor`. Він споживає готовий потік знімків, а
 * хто його наповнює — справа `:app`, який тримає єдиний фьюжен на весь процес.
 *
 * Завдяки цьому екран перевіряється підставним джерелом на JVM, а реальний
 * збирач сенсорів — окремо й без Compose.
 */
fun interface PrecisionStateSource {
    /** Гарячий потік знімків стану позиціонування. */
    fun state(): kotlinx.coroutines.flow.Flow<PrecisionUiState>
}
