package local.phantom.companion.feature.precision

import android.content.Context
import local.phantom.companion.core.indoor.rtt.RttAvailability
import local.phantom.companion.core.indoor.rtt.WifiRttRanger

/**
 * Одна-єдина, суто читальна перевірка: чи вміє цей пристрій Wi-Fi RTT.
 *
 * **Чому лише перевірка, а не вимірювання.** Wi-Fi RTT (802.11mc FTM) міряє
 * фізичну ВІДСТАНЬ до точки доступу. Відстань — не координата: щоб отримати
 * позицію, потрібні координати самих точок доступу, а їх у застосунку немає й
 * офлайн узятись їм нізвідки. Виміряти дальності й видати з них «позицію» без
 * якорів означало б вигадати точку — рівно те, проти чого існує цей екран.
 *
 * **Чому це взагалі показується.** Бо мовчання тут гірше за відповідь. Екран
 * називає Wi-Fi RTT одним із можливих джерел розвʼязку; людина має право знати,
 * чи є воно в неї фізично — і чому воно не працює, навіть коли залізо є.
 *
 * **Чому це не коштує дозволу.** [WifiRttRanger.availability] лише запитує
 * системну ознаку `FEATURE_WIFI_RTT`, стан служби і наявність уже виданого
 * дозволу. Жодного запиту дозволу, жодного сканування ефіру, жодного сеансу
 * вимірювання — і жодного впливу на батарею.
 */
internal object RttCapability {

    /**
     * @return чому RTT (не)доступний; [RttAvailability.NO_HARDWARE], якщо
     *   платформа відповіла винятком — на пристрої без служби це рівносильно
     *   відсутності апаратури, і вигадувати тут кращу відповідь нема з чого.
     */
    fun probe(context: Context): RttAvailability =
        runCatching { WifiRttRanger(context.applicationContext).availability() }
            .getOrDefault(RttAvailability.NO_HARDWARE)
}
