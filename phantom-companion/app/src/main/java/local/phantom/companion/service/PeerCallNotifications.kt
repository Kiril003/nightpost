package local.phantom.companion.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import local.phantom.companion.MainActivity
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerCallLine
import local.phantom.companion.ui.messenger.CallAudioRoute

/**
 * Дзвінок підіймає апарат.
 *
 * Рядок у шторці тут не годиться, і це вже було записано в [local.phantom
 * .companion.core.net.peer.PeerAnnounce]: «дзвінок має власний голос —
 * повноекранне сповіщення з двома кнопками, яке підіймає апарат». Довгий час
 * це був опис наміру: кадрам дзвінка не було куди приїхати, і показувати не
 * було чого.
 *
 * Окремий канал, а не спільний із листами: людина глушить розмови на ніч, і
 * дзвінок мусить пережити це рішення окремо від них. `CATEGORY_CALL` + стиль
 * `CallStyle` — щоб система малювала це дзвінком, з власними кнопками й
 * власним місцем на замкненому екрані, а не ще однією плашкою.
 *
 * **Дві кнопки роблять різні речі, і це не недогляд.** «Відхилити» — рівно
 * один кадр, тож його шле сам телефон, не відкриваючи застосунку
 * ([PeerCallActionReceiver]). «Взяти» вимагає мікрофона й рушія WebRTC, а той
 * живе у веб-месенджері — тож ця кнопка ВІДЧИНЯЄ месенджер, де на екрані вже
 * дзвонить його власний виклик. Намалювати тут другий екран дзвінка означало б
 * дві машини станів на один дзвінок.
 */
object PeerCallNotifications {

    /**
     * `_v2`, бо налаштування каналу НЕЗМІННІ після створення.
     *
     * До 21.09 канал заводився без звуку взагалі: `enableVibration(true)` і
     * жодного `setSound`. Android під `CallStyle.forIncomingCall` рингтона сам
     * НЕ грає — його має дати застосунок. Тому вхідний дзвінок віддавав одне
     * тенькання сповіщення й вібрацію, і власник сказав про це рівно те, що це
     * й було: «воно навіть не дзвонить».
     *
     * Дописати звук у наявний канал неможливо: система бере налаштування з
     * першого створення й далі ігнорує зміни, бо каналом володіє людина, а не
     * застосунок. Єдина чесна дорога — НОВИЙ канал; старий гасимо, щоб у
     * налаштуваннях не лишилось двох однакових рядків, між якими не обрати.
     */
    const val CHANNEL = "phantom_peer_call_v2"

    /** Той, що заводився без звуку. Тримаємо імʼя лише щоб його прибрати. */
    private const val CHANNEL_SILENT_V1 = "phantom_peer_call_v1"
    const val HOST_CALL = "call"
    const val ID = 4370
    private const val TAG = "PhantomCall"
    private const val MISSED_ID = 4371

    fun registerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Німий попередник більше не потрібен: лишений, він показував би в
        // налаштуваннях два однакові рядки, і людина не знала б, який з них її.
        runCatching { nm.deleteNotificationChannel(CHANNEL_SILENT_V1) }
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.peer_call_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.peer_call_channel_desc)
                enableVibration(true)
                setShowBadge(false)
                // USAGE_NOTIFICATION_RINGTONE — саме той ужиток, що робить звук
                // дзвінком: він іде розмовним трактом, слухається гучності
                // дзвінка, поважає «без звуку» й не глушиться музикою.
                // USAGE_NOTIFICATION дало б те саме одне тенькання, від якого
                // ми тут і йдемо.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build(),
                )
            },
        )
    }

    /**
     * Одне сповіщення на лінію, яке міняється разом із нею. Окремої плашки на
     * кожен стан немає навмисно: «дзвонить», «розмова» і «поклали» — це одна
     * подія, і три плашки про неї були б трьома подіями.
     */
    fun show(
        context: Context,
        line: PeerCallLine.Line,
        callerName: String,
        quiet: Boolean = false,
        video: Boolean = videoNow(context),
    ) {
        IncomingCallTelecom.follow(line)
        when (line) {
            is PeerCallLine.Line.Ringing ->
                if (quiet) missed(context, callerName) else ringing(context, line, callerName)
            is PeerCallLine.Line.Dialing ->
                ongoing(context, callerName, context.getString(R.string.peer_call_dialing), video)
            is PeerCallLine.Line.Talking -> {
                runCatching { NotificationManagerCompat.from(context).cancel(MISSED_ID) }
                ongoing(context, callerName, context.getString(R.string.peer_call_talking), video)
            }
            // «Поклали» — це не сповіщення: людина щойно дивилась на екран, з
            // якого це сталось. Плашка після розмови була б сміттям, яке треба
            // змахнути.
            is PeerCallLine.Line.Over, PeerCallLine.Line.Idle -> {
                current = null
                PeerCallService.stop(context)
                // Вибір звуку й замок близькості скидає служба, але служби могло й не бути (з фону вона не
                // стартує): без цього вибір минулої розмови керував би наступною, а екран гас би біля вуха.
                CallAudioRoute.shared(context).release()
                clear(context)
            }
        }
    }

    private fun videoNow(context: Context): Boolean = runCatching {
        (context.applicationContext as? PhantomApp)?.container?.callHub?.video?.value == true
    }.getOrDefault(false)

    /** Хто й що на плашці розмови, що йде: службу, якій змінюється тип, перезапускають із тим самим текстом. */
    internal data class Ongoing(val who: String, val text: String)

    @Volatile internal var current: Ongoing? = null

    /** Відео ввімкнули чи вимкнули посеред розмови: служба мусить змінити свій тип (камера лише з відео). */
    fun videoChanged(context: Context, video: Boolean) {
        val now = current ?: return
        PeerCallService.start(context, now.who, now.text, video)
    }

    /**
     * Застосунок вийшов на передній план посеред розмови. Якщо службу з фону не завели (вона з фону
     * не стартує), тепер можна: без неї замкнений екран забирає в рушія мікрофон.
     */
    fun holdCall(context: Context) {
        val now = current ?: return
        if (PeerCallService.running) return
        PeerCallService.start(context, now.who, now.text, videoNow(context))
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    internal fun openCall(context: Context) {
        runCatching { open(context).send() }
    }

    private fun ringing(
        context: Context,
        line: PeerCallLine.Line.Ringing,
        callerName: String,
    ) {
        if (IncomingCallTelecom.announce(context, line, callerName)) return
        postRinging(context, line, callerName)
    }

    internal fun postRinging(
        context: Context,
        line: PeerCallLine.Line.Ringing,
        callerName: String,
    ) {
        registerChannel(context)
        if (!allowed(context)) return
        val fullScreen = canUseFullScreen(context)
        Log.i(TAG, "ringing: fullScreenIntent allowed=$fullScreen")
        val notification = ringingNotification(context, line, callerName, fullScreen)
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    private fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
    }

    internal fun ringingNotification(
        context: Context,
        line: PeerCallLine.Line.Ringing,
        callerName: String,
        fullScreenAllowed: Boolean,
    ): Notification {
        val who = callerName.ifBlank { context.getString(R.string.peer_call_unknown) }
        val person = Person.Builder()
            .setName(who)
            .setKey(line.peerId)
            // Той самий тон, що в чаті й на ярлику розмови: хто дзвонить, видно
            // ще до імені. Без іконки шторка малює сірий силует.
            .setIcon(NotificationLook.avatar(context, line.peerId, who, adaptive = false))
            .build()
        // «Взяти» — це відчинити месенджер: слухавку бере рушій, який там живе.
        val answer = open(context)
        val decline = intentFor(context, PeerCallActionReceiver.ACTION_DECLINE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            // Замкнений екран мусить показати, хто дзвонить: інакше рішення
            // «брати чи ні» неможливе, і повноекранне сповіщення марне.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open(context))
            // Той самий намір, що й у тапа: система підіймає його сама, коли
            // екран замкнений, і показує плашкою, коли ні.
            .setFullScreenIntent(open(context), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setContentTitle(who)
            .setContentText(context.getString(R.string.peer_call_ringing))
            // FLAG_INSISTENT дзвонить до скасування: якщо процес помре посеред дзвінка, гасить система.
            .setTimeoutAfter(PeerCall.RING_LIFE_MS)
        // Без цього права Android 14 показує плашку замість екрана: кнопка веде туди, де його дають.
        if (!fullScreenAllowed) {
            builder.addAction(
                NotificationLook.smallIcon,
                context.getString(R.string.peer_call_allow_full_screen),
                fullScreenSettings(context),
            )
        }
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    private fun fullScreenSettings(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        ID + 2,
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Окремий id: ID гасне з кінцем лінії, а пропущений мусить дожити до ранку.
    // Без CallStyle — той вимагає повноекранного наміру, тобто підйому апарата.
    private fun missed(context: Context, callerName: String) {
        registerChannel(context)
        if (!allowed(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentTitle(callerName.ifBlank { context.getString(R.string.peer_call_unknown) })
            .setContentText(context.getString(R.string.peer_call_missed_quiet))
            .setContentIntent(open(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(MISSED_ID, notification) }
    }

    private fun ongoing(context: Context, callerName: String, text: String, video: Boolean) {
        current = Ongoing(callerName, text)
        registerChannel(context)
        if (allowed(context)) {
            val notification = ongoingNotification(context, callerName, text)
            runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
        }
        PeerCallService.start(context, callerName, text, video)
    }

    internal fun ongoingNotification(context: Context, callerName: String, text: String): Notification {
        val who = callerName.ifBlank { context.getString(R.string.peer_call_unknown) }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentTitle(who)
            .setContentText(text)
            .setContentIntent(open(context))
            .addAction(
                NotificationLook.smallIcon,
                context.getString(R.string.peer_call_hang_up),
                intentFor(context, PeerCallActionReceiver.ACTION_DECLINE),
            )
            .build()
    }

    private fun intentFor(context: Context, act: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            act.hashCode(),
            Intent(context, PeerCallActionReceiver::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** `phantom://call` — екран дзвінка, а не перелік розмов. */
    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        ID,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("phantom://$HOST_CALL")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
