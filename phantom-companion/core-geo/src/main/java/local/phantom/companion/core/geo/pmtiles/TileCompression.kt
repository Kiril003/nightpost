package local.phantom.companion.core.geo.pmtiles

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Алгоритм стиснення з заголовка PMTiles. Коди — з специфікації v3, змінювати не можна.
 *
 * Підтримані реально лише [NONE] і [GZIP]: обидва є в JDK/Android без жодної
 * зовнішньої залежності. [BROTLI] і [ZSTD] вимагали б нативної бібліотеки —
 * відмова чесна й голосна ([UnsupportedCompressionException]), бо мовчазне
 * повернення сміттєвих байтів у рендерер означало б порожню карту без причини.
 */
enum class PmTilesCompression(val code: Int) {
    /** Невідомо — валідний архів такого не містить. */
    UNKNOWN(0),
    NONE(1),
    GZIP(2),
    BROTLI(3),
    ZSTD(4),
    ;

    /** Чи вміє цей клієнт розпакувати такий потік. */
    val supported: Boolean get() = this == NONE || this == GZIP

    /**
     * Розпакувати [bytes] згідно з алгоритмом.
     *
     * @throws UnsupportedCompressionException для brotli/zstd/unknown.
     * @throws PmTilesFormatException якщо потік оголошений gzip, але ним не є.
     */
    fun decode(bytes: ByteArray): ByteArray = when (this) {
        NONE -> bytes
        GZIP -> runCatching {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }.getOrElse { e ->
            throw PmTilesFormatException("потік оголошено gzip, але розпакувати не вдалось: ${e.message}")
        }

        BROTLI, ZSTD, UNKNOWN -> throw UnsupportedCompressionException(
            "стиснення $name (код $code) не підтримується: клієнт свідомо обходиться без " +
                "нативних бібліотек. Перепечіть архів у gzip або none.",
        )
    }

    /**
     * Запакувати [bytes]. Потрібно, щоб пекти власні офлайн-пакети тим самим кодом,
     * яким їх потім читаємо — інакше формат перевіряється лише на чужих файлах.
     */
    fun encode(bytes: ByteArray): ByteArray = when (this) {
        NONE -> bytes
        GZIP -> ByteArrayOutputStream(bytes.size / 2 + 32).also { sink ->
            GZIPOutputStream(sink).use { it.write(bytes) }
        }.toByteArray()

        BROTLI, ZSTD, UNKNOWN -> throw UnsupportedCompressionException(
            "стиснення $name (код $code) не підтримується для запису",
        )
    }

    companion object {
        /** Код із заголовка → значення; невідомий код — [UNKNOWN]. */
        fun fromCode(code: Int): PmTilesCompression =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Тип корисного навантаження тайлу. Впливає лише на те, кому віддавати байти
 * (векторний рендерер чи декодер зображень), тому невідомий тип не є помилкою
 * формату — це просто «нам нема чим це показати».
 */
enum class PmTilesTileType(val code: Int) {
    UNKNOWN(0),

    /** Mapbox Vector Tile — основний випадок для векторної підкладки. */
    MVT(1),
    PNG(2),
    JPEG(3),
    WEBP(4),
    AVIF(5),
    ;

    companion object {
        fun fromCode(code: Int): PmTilesTileType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
