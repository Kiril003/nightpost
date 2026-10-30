package local.phantom.companion.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.MomentWire
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerMoment
import local.phantom.companion.data.PeerCircle

/** Що малює обличчя Профілю: лише те, що справді лежить на телефоні. */
internal object ProfileFace {

    data class Circle(val key: String, @StringRes val label: Int, val count: Int)

    data class Highlight(val id: String, val thumb: ByteArray?, val media: JsonObject? = null)

    @StringRes
    fun circleLabel(key: String): Int = when (key) {
        "personal" -> R.string.circle_personal
        "close" -> R.string.circle_close
        "family" -> R.string.circle_family
        "friends" -> R.string.circle_friends
        "social" -> R.string.circle_social
        "work" -> R.string.circle_work
        "study" -> R.string.circle_study
        "travel" -> R.string.circle_travel
        else -> R.string.circle_friends
    }

    /** Кола з людьми чи групами, у порядку «Сьогодні»; порожнє коло не малюється. */
    fun circles(assigned: Map<String, String>): List<Circle> {
        val counts = assigned.values.groupingBy { it }.eachCount()
        return PeerCircle.ALL.mapNotNull { key -> counts[key]?.let { Circle(key, circleLabel(key), it) } }
    }

    @JvmName("circlesOfSets")
    fun circles(sets: Map<String, Set<String>>): List<Circle> {
        val counts = HashMap<String, Int>()
        for (memberSet in sets.values) {
            for (key in memberSet) {
                if (key in PeerCircle.ALL) counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return PeerCircle.ALL.mapNotNull { key -> counts[key]?.let { Circle(key, circleLabel(key), it) } }
    }

    /** Залишені й живі свої моменти, свіжі першими; те саме фото — одна мініатюра. */
    fun highlights(
        log: List<PeerMessage>,
        me: String,
        nowMs: Long,
        kept: List<MomentWire.Moment> = emptyList(),
        liveBook: List<MomentWire.Moment> = emptyList(),
    ): List<Highlight> {
        if (me.isBlank()) return emptyList()
        val toHighlight = { m: MomentWire.Moment ->
            val media = m.media.firstOrNull {
                val mime = str(it, "mime")
                mime?.startsWith("image/") == true || mime?.startsWith("video/") == true
            }
            when {
                media != null -> Highlight(m.id, thumbOf(media), media)
                m.text.isNotBlank() -> Highlight(m.id, null, null)
                else -> null
            }
        }
        val saved = kept.filter { it.author == me && !it.deleted }.mapNotNull(toHighlight)
        val bookLive = liveBook.filter { it.author == me && !it.deleted }.mapNotNull(toHighlight)
        val live = PeerMoment.live(log, nowMs).filter { it.fromId == me }.map { m -> Highlight(m.id, thumbOf(m.media), m.media) }
        return (saved + bookLive + live).distinctBy { h -> h.media?.let { str(it, "sha256") }?.takeIf { it.isNotBlank() } ?: h.id }
    }

    /** Фото, розкодоване під розмір показу, а не розтягнутий превʼю. */
    fun decodeAt(bytes: ByteArray, edgePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.content

    private fun thumbOf(media: JsonObject): ByteArray? = str(media, "thumb")?.let(::dataUrlBytes)

    fun dataUrlBytes(raw: String): ByteArray? {
        val b64 = if (raw.startsWith("data:")) raw.substringAfter(',', "") else raw
        if (b64.isBlank()) return null
        return runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
    }
}
