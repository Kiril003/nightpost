package local.phantom.companion.core.net.peer

/**
 * What a message can be besides words.
 *
 * A mesh built for people who may be jammed, lost or moving needs to carry
 * «I am here» as a first-class thing, not as a paragraph of coordinates the
 * other person retypes into a map. It rides inside the same signed body, so it
 * inherits the signature, the outbox and the retries — a separate channel for
 * attachments would need all three built again, and would be the one that
 * silently drops things.
 *
 * The wire form is deliberately human-readable and deliberately ugly:
 * `PH-LOC:50.4501,30.5234;12` — a peer running an older build shows the raw
 * line rather than nothing, which is the difference between a degraded
 * conversation and a broken one.
 */
object PeerPayload {

    internal const val LOC = "PH-LOC:"

    data class Place(val lat: Double, val lon: Double, val accuracyM: Int?)

    fun encodePlace(lat: Double, lon: Double, accuracyM: Int? = null): String {
        val body = "%.5f,%.5f".format(java.util.Locale.US, lat, lon)
        return if (accuracyM != null && accuracyM > 0) "$LOC$body;$accuracyM" else "$LOC$body"
    }

    /**
     * Null for anything that is not exactly a place. Coordinates out of range
     * are refused rather than clamped: a marker at a plausible-looking wrong
     * spot is worse than no marker.
     */
    fun decodePlace(body: String): Place? {
        val raw = body.trim()
        if (!raw.startsWith(LOC)) return null
        val rest = raw.removePrefix(LOC)
        val coords = rest.substringBefore(';')
        val parts = coords.split(',')
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // Терпимо до ХВОСТА, і це рішення, а не послаблення.
        //
        // Розбір був строгий на весь залишок: `PH-LOC:50.45,30.52;12;будь-що`
        // давав точність `null`, бо `toIntOrNull` судив `"12;будь-що"` цілком.
        // Тобто поля, яке нова збірка могла б дописати, а стара пропустити повз
        // себе, не існувало — і формат був нерозширюваний ціною точності
        // (виміряно 12.09). Тепер точність читається до першої `;`, тож хвіст,
        // якого ця збірка не знає, коштує рівно нічого.
        //
        // Саму мітку сюди ще НЕ додано свідомо: вже випущені збірки читають
        // строго й точність на хвості втратять. Дописувати поля має сенс аж
        // тоді, коли терпимих збірок стане більшість — інакше ми платимо
        // точністю в кожного, хто не оновився.
        val accuracy = rest.substringAfter(';', "").substringBefore(';').trim()
            .toIntOrNull()?.takeIf { it > 0 }
        return Place(lat, lon, accuracy)
    }

    fun isPlace(body: String): Boolean = decodePlace(body) != null

    /**
     * Чи тіло ПОЧИНАЄТЬСЯ як кадр місця — без розбору.
     *
     * Окремо від [isPlace]: той відповідає «це справді місце», і для суду про
     * походження цього замало. `PH-LOC:` зі сміттям після нього не розбирається
     * в жодну точку, але вдає кадр пристрою рівно так само.
     */
    fun isPlaceFrame(body: String): Boolean = body.trim().startsWith(LOC)
}
