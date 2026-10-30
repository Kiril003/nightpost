package local.phantom.companion.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import local.phantom.companion.R
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.R as DesignR

/**
 * Як Nightpost виглядає ПОЗА своїм вікном: рядок стану, шторка, ярлики розмов.
 *
 * До 03.10 кожне сповіщення фарбувалось старою палітрою (core-design, до v1.2 —
 * бурштин #F4AF25, «корал», «зелень»), а знаком у рядку стану був шар
 * адаптивної іконки на 108 dp, де «P» займає 37,5 % висоти, — тож у рядку
 * стану і в кружку шторки знак виходив удвічі меншим за сусідів. Частина
 * сповіщень (симбіот, офлайн-мапа) і зовсім показувала стокові значки Android.
 * Шторка — перше, що людина бачить від застосунку, і саме там старе
 * стикалося з новим найгостріше.
 *
 * Одне джерело для всіх: барви — з токенів v1.2 ([NightColors]), знак —
 * «Естафета» Nightpost у мікрокрої на 24 dp ([R.drawable.ic_stat_nightpost]:
 * білий на прозорому, форму несе лише альфа, як вимагає рядок стану; до
 * збірки 16 тут стояла «P» старого імені), аватар розмови — тон
 * людини, як у вебі (Avatar.tsx: FNV-1a від id вузла, шість тонів, одна
 * літера). Бурштин — лише на дії й на знак, не на тривогу: тривога — [danger],
 * відбій — [success].
 */
object NotificationLook {

    /** Бурштин дії v1.2. Ним система фарбує знак і назву застосунку в шторці. */
    val accent: Int = NightColors.accent.toArgb()

    /** Тривога, яка вимагає дії (повітряна тривога, збій). */
    val danger: Int = NightColors.danger.toArgb()

    /** Відбій, «доїхало», «все гаразд». */
    val success: Int = NightColors.success.toArgb()

    /**
     * Знак у рядку стану: «Естафета» в мікрокрої (docs/brand, ic_stat_nightpost).
     * Не повний знак вкладки: на 16–20 px крок 4 зникає, і знак читався б як
     * «коло з рискою» — мікрокрій тримає щілину й крок на цьому розмірі.
     */
    @DrawableRes
    val smallIcon: Int = R.drawable.ic_stat_nightpost

    /**
     * Номер тону людини — дзеркало `toneIndex` з
     * phantom-os/src/frontend/src/styles/formTokens.ts (FNV-1a, 32 біти, по
     * одиницях UTF-16, як `charCodeAt`). Розійдуться — та сама людина матиме
     * один колір у чаті й інший у шторці.
     */
    fun toneIndex(key: String, tones: Int = NightColors.person.size): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in key) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return ((h.toLong() and 0xFFFFFFFFL) % tones).toInt()
    }

    /** Одна літера — перша буква або цифра імені; дзеркало `avatarLetters` у вебі. */
    fun letter(name: String): String {
        val first = name.firstOrNull { Character.isLetterOrDigit(it) } ?: return "?"
        return first.toString().uppercase(UKRAINIAN)
    }

    /**
     * Аватар людини: суцільний тон і біла літера.
     *
     * [adaptive] — для ярлика розмови: система сама вирізає з адаптивної карти
     * коло (чи свою форму), тож літера стоїть у безпечних 72 dp зі 108. Без
     * нього — карта для людини в CallStyle (64 dp, бо їде в extras сповіщення
     * через Binder). Її коло малюємо самі: One UI на A52 (04.10) показав
     * квадратну карту квадратом, поруч із круглими аватарами всіх інших.
     */
    fun avatar(context: Context, key: String, name: String, adaptive: Boolean = true): IconCompat {
        val dp = if (adaptive) ADAPTIVE_DP else PERSON_DP
        val side = (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(dp.toInt())
        val visible = if (adaptive) side * SAFE_ZONE else side.toFloat()
        val (fill, ink) = NightColors.person[toneIndex(key)]
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (adaptive) {
            canvas.drawColor(fill.toArgb())
        } else {
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
            canvas.drawCircle(side / 2f, side / 2f, side / 2f, disc)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink.toArgb()
            textAlign = Paint.Align.CENTER
            // Літера на 40 % видимого кола — як у вебі (Avatar.tsx).
            textSize = visible * LETTER_SHARE
            typeface = runCatching { ResourcesCompat.getFont(context, DesignR.font.manrope_semibold) }
                .getOrNull() ?: Typeface.DEFAULT_BOLD
        }
        val baseline = side / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter(name), side / 2f, baseline, paint)
        return if (adaptive) IconCompat.createWithAdaptiveBitmap(bitmap) else IconCompat.createWithBitmap(bitmap)
    }

    fun face(context: Context, key: String, name: String, photoB64: String?, adaptive: Boolean = true): IconCompat =
        photo(context, photoB64, adaptive) ?: avatar(context, key, name, adaptive)

    private fun photo(context: Context, b64: String?, adaptive: Boolean): IconCompat? {
        val bytes = b64?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        val src = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        val dp = if (adaptive) ADAPTIVE_DP else PERSON_DP
        val side = (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(dp.toInt())
        val cut = minOf(src.width, src.height)
        val from = Rect((src.width - cut) / 2, (src.height - cut) / 2, (src.width + cut) / 2, (src.height + cut) / 2)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (!adaptive) {
            canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, from, Rect(0, 0, side, side), paint)
        return if (adaptive) IconCompat.createWithAdaptiveBitmap(bitmap) else IconCompat.createWithBitmap(bitmap)
    }

    private val UKRAINIAN = java.util.Locale.forLanguageTag("uk-UA")
    private const val ADAPTIVE_DP = 108f
    private const val PERSON_DP = 64f
    private const val SAFE_ZONE = 72f / 108f
    private const val LETTER_SHARE = 0.4f
}
