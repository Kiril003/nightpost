package local.phantom.companion.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import local.phantom.companion.MainActivity
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.data.ChatHushStore
import local.phantom.companion.data.HushRule
import local.phantom.companion.data.LockScreenReveal
import local.phantom.companion.data.LockScreenRevealStore
import local.phantom.companion.data.PeerCircle
import local.phantom.companion.data.PeerRosterStore
import local.phantom.companion.data.ProfilesMirror
import local.phantom.companion.data.Quiet
import local.phantom.companion.data.QuietHours
import local.phantom.companion.data.applock.AppLockStore

/**
 * Хтось написав.
 *
 * Сітка вже приймала, перевіряла підпис і клала повідомлення в чергу — і на
 * цьому все закінчувалось. Друг писав, застосунок мовчав, оператор дізнавався
 * при наступному відкритті. Слухач без сповіщення — це не звʼязок, це скринька.
 *
 * MessagingStyle, а не звичайний рядок: система сама складе кілька реплік
 * однієї людини в одну розмову, покаже імʼя з аватаром і згрупує в шторці.
 * IMPORTANCE_HIGH, але без bypassDnd — повідомлення від людини має дійти,
 * тривога має розбудити; змішувати ці два рівні означає, що оператор глушить
 * канал і тоді не дійде ніщо.
 *
 * Замкнений екран за замовчуванням не показує ані імені, ані тексту. Замок
 * застосунку сюди не дістає: шторка малюється до нього, тож телефон, що лежить
 * екраном догори, віддавав розмову екіпажу кожному, хто пройшов повз.
 */
object PeerNotifications {

    const val CHANNEL = "phantom_peer_message_v1"
    const val GROUP = "phantom_peers"
    const val SUMMARY_ID = 4399

    // Спільні з MainActivity і маніфестом: розійдуться назви — тап нікуди не веде.
    const val HOST_PEER = "peer"
    const val HOST_GROUP = "peergroup"

    private val defaultRosterOf: (Context) -> PeerRosterStore? = { context ->
        runCatching {
            (context.applicationContext as? PhantomApp)?.container?.peers
                ?: PeerRosterStore(context.applicationContext)
        }.getOrNull()
    }

    @Volatile
    internal var rosterOf: (Context) -> PeerRosterStore? = defaultRosterOf

    /** null — замок не прочитався: імʼя показати можна, текст — ні (D1). */
    internal fun hidesContent(read: () -> Boolean): Boolean? = runCatching(read)
        .onFailure { runCatching { android.util.Log.w("PeerNotifications", "app lock unreadable", it) } }
        .getOrNull()

    private val defaultLockHides: (Context) -> Boolean? = { context ->
        hidesContent { AppLockStore.of(context).hidesNotificationContent() }
    }

    @Volatile
    internal var lockHides: (Context) -> Boolean? = defaultLockHides

    private val defaultMirrorOf: (Context) -> ProfilesMirror? = { context ->
        runCatching { (context.applicationContext as? PhantomApp)?.container?.profilesMirror }.getOrNull()
    }

    @Volatile
    internal var mirrorOf: (Context) -> ProfilesMirror? = defaultMirrorOf

    private val defaultMinuteOfDay: () -> Int = {
        val now = java.time.LocalTime.now()
        now.hour * 60 + now.minute
    }

    @Volatile
    internal var minuteOfDayNow: () -> Int = defaultMinuteOfDay

    private val defaultHushOf: (Context) -> ChatHushStore? = { context ->
        runCatching {
            (context.applicationContext as? PhantomApp)?.container?.chatHush
                ?: ChatHushStore(context.applicationContext)
        }.getOrNull()
    }

    /** The per-chat mute the web sets over the bridge; the shade obeys the same store. */
    @Volatile
    internal var hushOf: (Context) -> ChatHushStore? = defaultHushOf

    internal fun resetRoster() {
        rosterOf = defaultRosterOf
        hushOf = defaultHushOf
        lockHides = defaultLockHides
        mirrorOf = defaultMirrorOf
        minuteOfDayNow = defaultMinuteOfDay
    }

    internal fun quietAt(q: Quiet, minuteOfDay: Int): Boolean {
        val from = QuietHours.parse(q.from)?.let { it.hour * 60 + it.minute } ?: return false
        val to = QuietHours.parse(q.to)?.let { it.hour * 60 + it.minute } ?: return false
        if (from == to) return false
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return if (from < to) m in from until to else m >= from || m < to
    }

    internal fun channelIdForRing(ring: String?): String =
        if (ring != null && ring in PeerCircle.ALL) "phantom_peer_message_${ring}_v1" else CHANNEL

    @StringRes
    internal fun ringNameRes(ring: String): Int = when (ring) {
        "personal" -> R.string.circle_personal
        "close" -> R.string.circle_close
        "friends" -> R.string.circle_friends
        "social" -> R.string.circle_social
        "work" -> R.string.circle_work
        else -> R.string.peer_channel
    }

    internal fun vibrationForRing(ring: String): LongArray = when (ring) {
        "personal" -> longArrayOf(0L, 120L, 60L, 120L, 60L, 240L)
        "close" -> longArrayOf(0L, 140L, 80L, 140L)
        "friends" -> longArrayOf(0L, 180L)
        "social" -> longArrayOf(0L, 90L, 70L, 90L, 70L, 90L)
        "work" -> longArrayOf(0L, 260L, 120L, 100L)
        else -> longArrayOf(0L, 180L)
    }

    fun registerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.peer_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.peer_channel_desc)
                enableVibration(true)
                setShowBadge(true)
                // Свідомо БЕЗ setLockscreenVisibility: канал незмінний після
                // створення, і рівень, вкручений сюди, не можна було б ані
                // підняти, ані опустити на одну розмову — лише новим id, тобто
                // втратою всього, що оператор налаштував собі на цьому каналі.
            },
        )
    }

    private fun ensureChannel(context: Context, ring: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (ring != null && ring in PeerCircle.ALL) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelIdForRing(ring),
                    context.getString(ringNameRes(ring)),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.peer_channel_desc)
                    enableVibration(true)
                    vibrationPattern = vibrationForRing(ring)
                    setShowBadge(true)
                },
            )
            return
        }
        registerChannel(context)
    }

    internal fun buzzOf(channel: String): String = channel.removeSuffix("_v1") + "_buzz_v1"

    // Звук каналу після створення не змінити, тож «лише вібрація» — окремий канал-близнюк.
    private fun ensureBuzz(context: Context, ring: String?): String {
        val known = ring != null && ring in PeerCircle.ALL
        val id = buzzOf(channelIdForRing(ring))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val nm = context.getSystemService(NotificationManager::class.java) ?: return id
        if (nm.getNotificationChannel(id) != null) return id
        val name = context.getString(if (known) ringNameRes(ring!!) else R.string.peer_channel)
        nm.createNotificationChannel(
            NotificationChannel(id, context.getString(R.string.peer_channel_buzz, name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.peer_channel_desc)
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = vibrationForRing(if (known) ring!! else "")
                setShowBadge(true)
            },
        )
        return id
    }

    private fun channelFor(context: Context, peerId: String, buzz: Boolean = false): String {
        val circles = runCatching { rosterOf(context)?.circlesOf(peerId) }.getOrNull().orEmpty()
        val ring = PeerCircle.closestRing(circles)
        if (buzz) return ensureBuzz(context, ring)
        ensureChannel(context, ring)
        return channelIdForRing(ring)
    }

    /**
     * Одна людина — одне сповіщення, яке доростає новими репліками. Окремий
     * рядок на кожне повідомлення перетворив би розмову на десять плашок.
     */
    fun notifyMessage(
        context: Context,
        notice: PeerAnnounce.Notice,
        senderName: String,
        body: String,
        atMs: Long,
        quiet: Boolean = false,
    ) {
        val breaks = mentionBreaks(context, notice)
        if (!breaks && runCatching { hushOf(context)?.hushesNow(notice.groupId ?: notice.fromId) }.getOrNull() == true) return
        val rule = if (breaks) HushRule.Verdict.RING else runCatching {
            hushOf(context)?.ruleVerdict(notice.groupId ?: notice.fromId, notice.groupId == null, notice.text)
        }.getOrNull() ?: HushRule.Verdict.RING
        if (rule == HushRule.Verdict.DROP) return
        val body = MarkerLines.sticker(body)?.let { "$it ${context.getString(local.phantom.companion.R.string.peer_marker_sticker)}" }
            ?: MarkerLines.strip(body)
        val mirror = runCatching { mirrorOf(context) }.getOrNull()
        val active = runCatching { mirror?.liveActive() }.getOrNull()
        val owner = runCatching { ownerOfConversation(mirror, notice) }.getOrNull()
        val circles = runCatching { rosterOf(context)?.circlesOf(notice.fromId) }.getOrNull().orEmpty()
        val effectiveQuiet = quiet || rule == HushRule.Verdict.SILENT ||
            !breaks && (owner?.alert == "silent" || hushed(owner, notice.groupId != null, circles, legacy = (mirror?.state?.value?.v ?: 1) < 2))
        val buzz = owner?.alert == "vibrate"
        val elsewhere = owner?.takeIf { active != null && it.id != active.id }?.name?.trim()?.takeIf { it.isNotEmpty() }
        registerChannel(context)
        val id = idOf(notice.key)
        // Замок застосунку або «ховати текст»: шторка малюється ДО замка, тож
        // плашка не каже ні хто, ні що — інакше замок був би декорацією.
        val hides = lockHides(context)
        val photo = runCatching { rosterOf(context)?.peers?.value?.firstOrNull { it.id == notice.fromId }?.avatarThumbB64 }.getOrNull()
        if (hides == null) {
            postHidden(context, notice, atMs, effectiveQuiet, senderName, NotificationLook.face(context, notice.fromId, senderName, photo, adaptive = false), buzz)
            return
        }
        if (shown(hides, senderName, body).hidden) {
            postHidden(context, notice, atMs, effectiveQuiet, buzz = buzz)
            return
        }
        val sender = Person.Builder().setName(senderName).setKey(notice.fromId)
            .setIcon(NotificationLook.face(context, notice.fromId, senderName, photo, adaptive = false))
            .build()
        val style = (extractStyle(context, id) ?: freshStyle(context))
            .setGroupConversation(notice.groupId != null)
            .setConversationTitle(
                elsewhere?.let { context.getString(R.string.peer_in_profile, notice.groupName ?: senderName, it) } ?: notice.groupName,
            )
            .addMessage(body, atMs, sender)

        pushShortcut(context, notice, senderName, photo)
        post(context, notice, style, atMs, effectiveQuiet, buzz)
    }

    /** P10: @ мене в групі проходить крізь «без звуку» і тишу, поки людина не вимкнула згадки. */
    fun mentionBreaks(context: Context, notice: PeerAnnounce.Notice): Boolean =
        notice.mentionsMe && runCatching { hushOf(context)?.mentionsThrough?.value }.getOrNull() != false

    internal fun ownerOfConversation(mirror: ProfilesMirror?, notice: PeerAnnounce.Notice): ProfilesMirror.Profile? {
        mirror ?: return null
        val active = mirror.liveActive()
        val m = mirror.state.value
        if (m.v < 2) return active
        return m.profile(m.chatProfile[notice.groupId ?: notice.fromId]) ?: mirror.ownerOf(notice.fromId)
    }

    // Never drop: a hidden message read later from another profile looked like a lost one.
    internal fun hushed(owner: ProfilesMirror.Profile?, group: Boolean, circles: Set<String>, legacy: Boolean): Boolean {
        owner ?: return false
        if (owner.quiet?.let { quietAt(it, minuteOfDayNow()) } == true) return true
        return legacy && !group && !owner.passes(circles) && circles.none { it in BREAK_THROUGH }
    }

    private val BREAK_THROUGH = setOf("personal", "close")

    /** Що плашка має право сказати. [hidden] — нічого, крім «нове повідомлення». */
    internal data class Shown(val hidden: Boolean, val sender: String?, val text: String?, val canReply: Boolean)

    internal fun shown(hideContent: Boolean, senderName: String, body: String): Shown =
        if (hideContent) Shown(hidden = true, sender = null, text = null, canReply = false)
        else Shown(hidden = false, sender = senderName, text = body, canReply = true)

    /**
     * Плашка без вмісту: ні імені, ні тексту, ні ярлика розмови (ярлик несе імʼя
     * людини в лаунчер). Відповісти наосліп — не відповідь, тож лишається тільки
     * «Прочитано». Одна плашка на розмову — щоб «Прочитано» знало, що гасити.
     */
    private fun postHidden(
        context: Context,
        notice: PeerAnnounce.Notice,
        atMs: Long,
        quiet: Boolean,
        who: String? = null,
        face: IconCompat? = null,
        buzz: Boolean = false,
    ) {
        if (!allowed(context)) return
        registerChannel(context)
        val bare = hiddenBuilder(context, notice, atMs, quiet, buzz).build()
        val notification = if (who == null) bare else hiddenBuilder(context, notice, atMs, quiet, buzz)
            .setContentTitle(who)
            .setLargeIcon(face?.toIcon(context))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(bare)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(idOf(notice.key), notification)
            summarise(context)
        }
    }

    private fun hiddenBuilder(context: Context, notice: PeerAnnounce.Notice, atMs: Long, quiet: Boolean, buzz: Boolean = false) =
        NotificationCompat.Builder(context, if (buzz) ensureBuzz(context, null) else CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(context.getString(R.string.peer_channel))
            .setContentText(context.getString(R.string.peer_hidden_message))
            .setWhen(atMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP)
            .setSilent(quiet)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAllowSystemGeneratedContextualActions(false)
            .setContentIntent(openChat(context, notice))
            .addAction(markReadAction(context, notice))

    /**
     * Відповідь зі шторки вже стала в чергу — вона має зʼявитись у тій самій
     * розмові. Без цього оператор бачить, що написав, тільки відкривши чат, і
     * не знає, чи взагалі відправилось.
     */
    fun noteOwnReply(context: Context, conversationId: String, text: String) {
        val id = idOf(conversationId)
        val style = extractStyle(context, id) ?: return
        val at = System.currentTimeMillis()
        style.addMessage(text, at, null as Person?)
        post(context, noticeOf(conversationId, style), style, at)
    }

    /** Черга не прийняла лист. Мовчати тут — брехати про відправлене. */
    fun noteReplyFailed(context: Context, conversationId: String) {
        val id = idOf(conversationId)
        val style = extractStyle(context, id) ?: return
        val at = System.currentTimeMillis()
        style.addMessage(context.getString(R.string.peer_reply_failed), at, null as Person?)
        post(context, noticeOf(conversationId, style), style, at)
    }

    /**
     * Не «не вдалось», а «не поїде»: рядок починався як кадр пристрою. Причина
     * інша, і людині треба назвати саме її — інакше вона тиснутиме «ще раз».
     */
    fun noteReplyRefused(context: Context, conversationId: String) {
        val id = idOf(conversationId)
        val style = extractStyle(context, id) ?: return
        val at = System.currentTimeMillis()
        style.addMessage(context.getString(R.string.peer_reply_refused), at, null as Person?)
        post(context, noticeOf(conversationId, style), style, at)
    }

    /** Ключ звіреної людини змінився — лист чекає на рішення в чаті, не на «ще раз». */
    fun noteReplyHeld(context: Context, conversationId: String) {
        val id = idOf(conversationId)
        val style = extractStyle(context, id) ?: return
        val at = System.currentTimeMillis()
        style.addMessage(context.getString(R.string.peer_reply_key_hold), at, null as Person?)
        post(context, noticeOf(conversationId, style), style, at)
    }

    private fun post(
        context: Context,
        notice: PeerAnnounce.Notice,
        style: NotificationCompat.MessagingStyle,
        atMs: Long,
        quiet: Boolean = false,
        buzz: Boolean = false,
    ) {
        // Замок стоїть тут, а не у викликачів: до post() ведуть ТРИ входи —
        // notifyMessage, noteOwnReply і noteReplyFailed, — і перевірка стояла
        // лише в першому. Два інші публікували без дозволу (лінт 30.08).
        if (!allowed(context)) return
        if (lockHides(context) != false) {
            postHidden(context, notice, atMs, quiet, buzz = buzz)
            return
        }
        val id = idOf(notice.key)
        val channel = when {
            notice.groupId == null -> channelFor(context, notice.fromId, buzz)
            buzz -> ensureBuzz(context, null)
            else -> CHANNEL.also { registerChannel(context) }
        }
        val reveal = LockScreenRevealStore.of(context).forConversation(notice.key)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setStyle(style)
            .setShortcutId(notice.key)
            // Той самий ключ, що й у ярлика: система вʼяже плашку з розмовою і
            // ранжує її серед людей, а не серед застосунків.
            .setLocusId(LocusIdCompat(notice.key))
            .setWhen(atMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP)
            // Канал HIGH з вібрацією незмінний; тишу дає лише сама плашка.
            .setSilent(quiet)
            .setVisibility(reveal.visibility())
            .setPublicVersion(publicVersion(context, reveal, notice.groupName ?: style.messages.lastOrNull { it.person != null }?.person?.name?.toString()))
            // Системні «розумні відповіді» й контекстні дії читають текст
            // розмови і пропонують слова від імені оператора. Ні того, ні
            // іншого цей продукт не віддає.
            .setAllowSystemGeneratedContextualActions(false)
            .setContentIntent(openChat(context, notice))
            // Шторка малює рівно три дії — четверта зникає мовчки. «Виїжджаю»
            // поступилось «Прочитано»: перше — це заява про рух, яку випадковий
            // дотик у кишені робить брехнею, друге — єдиний спосіб погасити
            // розмову, не відкриваючи її.
            .addAction(replyAction(context, notice))
            .addAction(cannedAction(context, notice, R.string.peer_quick_ack))
            .addAction(markReadAction(context, notice))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            summarise(context)
        }
    }

    /**
     * Замкнений екран бачить лише це.
     *
     * [LockScreenReveal.REDACTED] навмисно не називає ані людину, ані групу,
     * ані їх кількість: на блокпосту імʼя — це вже звʼязок, який шукали, а три
     * рядки за дві хвилини — це «зараз щось координують». Лишається час: він і
     * так чутний по вібрації, і саме він каже, чи це можна відкласти.
     *
     * Без цієї плашки система намалювала б власне «Вміст приховано» — однаково
     * марне для оператора й однаково красномовне для того, хто дивиться.
     */
    private fun publicVersion(context: Context, reveal: LockScreenReveal, who: String? = null): Notification? {
        if (reveal != LockScreenReveal.REDACTED && reveal != LockScreenReveal.SYSTEM) return null
        val named = reveal == LockScreenReveal.SYSTEM && !who.isNullOrBlank()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(if (named) who else context.getString(R.string.peer_lock_redacted))
            .apply { if (named) setContentText(context.getString(R.string.peer_hidden_message)) }
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * Двадцять повідомлень — це двадцять рядків без цієї плашки. Рівень
     * розкриття зведення — найсуворіший серед розмов: інакше розмова, яку
     * оператор сховав, лишила б слід у зведенні над нею.
     */
    internal fun grouped(ids: List<Int>, gone: Int?): List<Int> = ids.filter { it != SUMMARY_ID && it != gone }

    private fun summarise(context: Context, gone: Int? = null) {
        if (!allowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val live = runCatching {
            nm.activeNotifications.filter { it.notification.group == GROUP }
                .let { all -> grouped(all.map { it.id }, gone).toSet().let { keep -> all.filter { it.id in keep } } }
        }.getOrNull() ?: return
        if (live.size < 2) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
            return
        }
        val strictest = live.minOf { it.notification.visibility }
        val summary = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(context.getString(R.string.peer_channel))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.peer_summary_chats,
                    live.size,
                    live.size,
                ),
            )
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(strictest)
            .setPublicVersion(publicVersion(context, LockScreenReveal.REDACTED))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_ID, summary) }
    }

    /**
     * Дія вимагає розблокування (API 31+). Швидкість у рукавицях лишається
     * там, де телефон уже в руках оператора; телефон у чужих руках не пише
     * від його імені.
     */
    private fun replyAction(
        context: Context,
        notice: PeerAnnounce.Notice,
    ): NotificationCompat.Action {
        val remote = RemoteInput.Builder(PeerReplyReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.peer_reply_hint))
            .build()
        return NotificationCompat.Action.Builder(
            NotificationLook.smallIcon,
            context.getString(R.string.peer_reply),
            replyIntent(context, notice, canned = null),
        )
            .addRemoteInput(remote)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * Прочитано, не відкриваючи. Межа прочитаного зсувається у сховищах —
     * див. [PeerMarkReadReceiver]; без цього плашка зникала, а лічильник
     * непрочитаного в застосунку лишався й вимагав відкрити розмову ще раз.
     */
    private fun markReadAction(
        context: Context,
        notice: PeerAnnounce.Notice,
    ): NotificationCompat.Action {
        val intent = Intent(context, PeerMarkReadReceiver::class.java).apply {
            action = "phantom.read.${notice.key}"
            putExtra(PeerMarkReadReceiver.EXTRA_CONVERSATION, notice.key)
            putExtra(PeerMarkReadReceiver.EXTRA_GROUP_ID, notice.groupId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            idOf(intent.action!!),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            NotificationLook.smallIcon,
            context.getString(R.string.peer_mark_read),
            pending,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    private fun cannedAction(
        context: Context,
        notice: PeerAnnounce.Notice,
        label: Int,
    ): NotificationCompat.Action {
        val text = context.getString(label)
        return NotificationCompat.Action.Builder(
            NotificationLook.smallIcon,
            text,
            replyIntent(context, notice, canned = text),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()
    }

    private fun replyIntent(
        context: Context,
        notice: PeerAnnounce.Notice,
        canned: String?,
    ): PendingIntent {
        val intent = Intent(context, PeerReplyReceiver::class.java).apply {
            action = "phantom.reply.${notice.key}.${canned ?: "open"}"
            putExtra(PeerReplyReceiver.EXTRA_CONVERSATION, notice.groupId ?: notice.fromId)
            putExtra(PeerReplyReceiver.EXTRA_GROUP_ID, notice.groupId)
            canned?.let { putExtra(PeerReplyReceiver.EXTRA_CANNED, it) }
        }
        // MUTABLE — інакше RemoteInput не має куди покласти набраний текст.
        val flags = if (canned == null) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, idOf(intent.action!!), intent, flags)
    }

    /**
     * Без ярлика розмови сучасний Android не вважає плашку розмовою взагалі:
     * ні окремої секції в шторці, ні пріоритету, ні власного звуку на людину.
     */
    private fun pushShortcut(context: Context, notice: PeerAnnounce.Notice, senderName: String, photo: String?) {
        // Порожній ярлик — виняток усередині системи, а не тихий пропуск.
        val label = NoticeLabels.shortcutLabel(notice.groupName, senderName, context.getString(R.string.peer_unknown_sender))
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(linkFor(notice))
        }
        val shortcut = ShortcutInfoCompat.Builder(context, notice.key)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIntent(intent)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(notice.key))
            // Тон людини з літерою, як аватар у чаті. Тут стояв монохромний шар
            // іконки — біла «P» на білій картці шторки, тобто порожнє місце.
            .setIcon(
                notice.groupId?.let { NotificationLook.avatar(context, it, label) }
                    ?: NotificationLook.face(context, notice.fromId, label, photo),
            )
            .setPerson(Person.Builder().setName(senderName).setKey(notice.fromId).build())
            // Літерал, а не ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION: та
            // константа зʼявилась на API 30, а тут minSdk 29.
            .setCategories(setOf("android.shortcut.conversation"))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    private fun extractStyle(
        context: Context,
        id: Int,
    ): NotificationCompat.MessagingStyle? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        val live = runCatching { nm.activeNotifications.firstOrNull { it.id == id } }.getOrNull()
            ?: return null
        return NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(live.notification)
    }

    private fun freshStyle(context: Context) = NotificationCompat.MessagingStyle(
        Person.Builder().setName(context.getString(R.string.peer_me)).build(),
    )

    /** Живе сповіщення вже знає, чия це розмова — відповідь не має його губити. */
    private fun noticeOf(
        conversationId: String,
        style: NotificationCompat.MessagingStyle,
    ): PeerAnnounce.Notice {
        val last = style.messages.lastOrNull { it.person != null }
        return PeerAnnounce.Notice(
            key = conversationId,
            fromId = last?.person?.key ?: conversationId,
            groupId = if (style.isGroupConversation) conversationId else null,
            groupName = style.conversationTitle?.toString(),
            text = "",
        )
    }

    /** Прочитано — прибрати. Плашка про відкриту розмову це вже сміття. */
    fun clear(context: Context, peerId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(idOf(peerId))
            summarise(context, gone = idOf(peerId))
        }
    }

    /**
     * Той самий id для тієї самої людини й у наступному запуску процесу:
     * hashCode рядка в Kotlin стабільний за специфікацією, тож розмова не
     * роздвоїться після перезапуску.
     *
     * 27 біт, а не 16: на шістнадцяти вже пів тисячі контактів дають за
     * парадоксом днів народження пару збігів, а збіг тут — це повідомлення від
     * одного друга, яке мовчки затерло повідомлення від іншого.
     */
    internal fun idOf(peerId: String): Int = BASE_ID + (peerId.hashCode() and 0x7FFFFFF)

    /**
     * Група має власну адресу: її ключ — id групи, і за адресою розмови на
     * двох її шукали серед контактів, не знаходили й закривали екран.
     */
    internal fun linkFor(notice: PeerAnnounce.Notice): String =
        notice.groupId?.let { "phantom://$HOST_GROUP/$it" } ?: "phantom://$HOST_PEER/${notice.key}"

    private fun openChat(
        context: Context,
        notice: PeerAnnounce.Notice,
    ): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(linkFor(notice))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return runCatching {
            PendingIntent.getActivity(
                context,
                idOf(notice.key),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }

    private fun openApp(context: Context): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private const val BASE_ID = 4400
}
