package local.phantom.companion.core.sensor.nav

/**
 * What the operator is TOLD when the [ConsistencyGate] does not simply trust the
 * sky. Three states, three sentences, and the spoof sentence is never lent to
 * the other two: a false accusation of jamming costs the banner its meaning, and
 * the banner is the one thing on that screen that has to keep it.
 *
 * Hardcoded rather than in `values/`: core-sensor carries no resources (Kotlin +
 * manifest only). They belong in strings.xml the moment a UI module owns this
 * surface.
 *
 * Split out of the gate itself so the gate stays a decision class and stays
 * under the file-size rule. Nothing here decides anything.
 */

// Сказане людині — без «GPS», «±м» і «супутникової точки» (закон 26.09):
// радіус лишається в displayHAccM, а речення каже лише, що робити з точкою.

internal const val UNCERTAIN_UA = "Місце приблизне — можу помилитись із поворотом"

internal const val UNCERTAIN_EN = "Position is rough — I may miss a turn"

internal fun spoofUa(source: ConsistencyGate.Source) = when (source) {
    ConsistencyGate.Source.NETWORK_HOLD -> "Телефону підсовують чуже місце. Показую приблизне справжнє"
    ConsistencyGate.Source.HELD -> "Телефону підсовують чуже місце. Показую останнє певне"
    else -> "Телефону підсовують чуже місце. Де ти — не знаю"
}

internal fun spoofEn(source: ConsistencyGate.Source) = when (source) {
    ConsistencyGate.Source.NETWORK_HOLD -> "The phone is being fed a false position. Showing a rough real one"
    ConsistencyGate.Source.HELD -> "The phone is being fed a false position. Showing the last sure one"
    else -> "The phone is being fed a false position. Where you are is unknown"
}

internal fun noFixUa(source: ConsistencyGate.Source) = when (source) {
    ConsistencyGate.Source.NETWORK_HOLD -> "Сигнал зник. Показую приблизне місце"
    ConsistencyGate.Source.HELD -> "Сигнал зник. Показую останнє певне місце"
    else -> ConsistencyGate.NO_FIX_UNKNOWN_UA
}

internal fun noFixEn(source: ConsistencyGate.Source) = when (source) {
    ConsistencyGate.Source.NETWORK_HOLD -> "Signal lost. Showing a rough position"
    ConsistencyGate.Source.HELD -> "Signal lost. Showing the last sure position"
    else -> ConsistencyGate.NO_FIX_UNKNOWN_EN
}
