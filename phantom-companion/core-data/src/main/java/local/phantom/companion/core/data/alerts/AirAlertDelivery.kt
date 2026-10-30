package local.phantom.companion.core.data.alerts

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Чи дійде повітряна тривога — сказане наперед, а не після.
 *
 * `AirAlertNotifications.notify()` чесно виходить, коли дозволу немає, і пише
 * про це в `Log.w`. Тобто застосунок **знає**, що тривога не буде доставлена, і
 * каже це туди, куди людина не дивиться ніколи. У всьому дереві не було
 * жодного екрана й жодного рядка ресурсів, який згадував би, що сповіщення
 * вимкнені (заміряно 30.08.2026 пошуком по всіх модулях).
 *
 * Це не сповіщення про сповіщення: канал, яким така новина мала б їхати, — і є
 * той, що зламаний. Правда мусить жити в застосунку.
 *
 * ## Чому станів два, а не сім
 *
 * Хвороб у власника більше: дозвіл ніколи не питаний / відмовлений раз /
 * відмовлений назавжди / знятий потім / канал вимкнено окремо / канал стишено /
 * «Не турбувати». Але **речення однакове й однаково істинне** для перших
 * пʼяти: сповіщення не буде. Різниця між ними — не стан екрана, а ціль дотику,
 * і вона вирішується в момент дотику.
 *
 * [Quiet] відділено свідомо: плашка **дійде**, але о третій ночі не розбудить.
 * Звести його з [Silent] означало б назвати «не прийде» те, що вдень цілком
 * приходить, — маленька брехня в бік паніки, а ми міряємо в інший бік.
 *
 * ## Чого тут НЕМАЄ і чому
 *
 * **Оптимізація батареї й виробничі «вбивці» служб** — це інша хвороба: не «я
 * не покажу», а «я не знаю». Вбита служба не дізнається про тривогу взагалі, і
 * про це вже говорить свіжість стрічки. Змішати їх — зруйнувати обидві правди.
 *
 * **Відкликаний повноекранний намір (API 34)** сюди теж не входить: тривога
 * лишається звуковою плашкою на каналі будильника, тобто **дійде**. Кричати
 * про це — інфляція гучності, після якої перестануть читати й справжнє.
 *
 * **І головне: [Fine] не означає «дійде».** Виробничі оболонки притримують
 * сповіщення самі, і зсередини це не видно. Тому продукт ніколи не вимовляє
 * позитивної обіцянки: здоровий стан — це тиша, а не запевнення.
 */
/**
 * Ідентифікатор каналу тривоги — тут, а не в `:app`, бо його мусять знати
 * ДВОЄ: той, хто в цей канал пише, і той, хто малює правду про його стан.
 * Дві копії рядка розійшлись би тихо, і розбіжність виглядала б як здоровий
 * канал.
 *
 * v2 — не переназивати: канал в Android незмінний після створення, і зміна
 * id викидає налаштування звуку, які оператор зробив собі.
 */
const val ALARM_CHANNEL_ID = "phantom_air_alert_v2"

sealed interface AirAlertDelivery {

    /** Усе, що ми вміємо перевірити, — гаразд. Продукт мовчить. */
    data object Fine : AirAlertDelivery

    /**
     * Сповіщення не вийде взагалі: дозволу немає, або сповіщення застосунку
     * вимкнені, або канал тривоги погашено до нуля.
     *
     * [canAsk] розрізняє два входи, а не два стани: `true` — системний діалог
     * ще можливий; `false` — лишились тільки налаштування застосунку.
     */
    data class Silent(val canAsk: Boolean) : AirAlertDelivery

    /** Плашка буде, звуку не буде: канал живий, але стишений нижче тривоги. */
    data object Quiet : AirAlertDelivery

    companion object {

        /**
         * Чиста функція над уже виміряними входами — щоб її можна було
         * покрити таблицею на JVM. Читання каркаса живе в [of], і саме воно
         * лишається недоказовим без пристрою.
         *
         * @param permitted чи надано POST_NOTIFICATIONS (до Android 13 — завжди true)
         * @param appEnabled `areNotificationsEnabled()`
         * @param channelImportance важливість каналу тривоги;
         *   `null` — каналу ще немає (до першої реєстрації), і це не хвороба
         * @param askable `shouldShowRequestPermissionRationale`
         */
        fun resolve(
            permitted: Boolean,
            appEnabled: Boolean,
            channelImportance: Int?,
            askable: Boolean,
        ): AirAlertDelivery = when {
            !permitted -> Silent(canAsk = askable)
            !appEnabled -> Silent(canAsk = false)
            channelImportance == NotificationManager.IMPORTANCE_NONE -> Silent(canAsk = false)
            channelImportance != null &&
                channelImportance < NotificationManager.IMPORTANCE_HIGH -> Quiet
            else -> Fine
        }

        /** Той самий висновок, але з живого пристрою. */
        fun of(context: Context, askable: Boolean = false): AirAlertDelivery {
            val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            val appEnabled = runCatching {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrDefault(true)
            val importance = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                null
            } else {
                runCatching {
                    context.getSystemService(NotificationManager::class.java)
                        ?.getNotificationChannel(ALARM_CHANNEL_ID)
                        ?.importance
                }.getOrNull()
            }
            return resolve(permitted, appEnabled, importance, askable)
        }
    }
}
