package local.phantom.companion.core.net.geosrc

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// Спільний словник зовнішніх геоджерел (`transit/`, `air/`, `env/`, `sky/`, `sea/`).
//
// Чому окремий пакет: шість клієнтів мають однакову поведінку на межі —
// bbox на вході, TTL-кеш, обмежувач частоти, типізована відмова замість
// винятку. Якби кожен клієнт ніс свою копію цього, розбіжність порогів
// між ними була б питанням часу, а UI не мав би єдиної мови, щоб сказати
// «джерело мовчить». Тут — рівно ця мова, без мережевого коду.

/**
 * Джерело часу, підмінюване в тестах.
 *
 * Кеш і обмежувач частоти без підмінного годинника перевіряються лише
 * реальним `Thread.sleep`, а це секунди на кожен тест і плаваючі падіння
 * на завантаженому CI. Тому час — параметр, а не глобальний виклик.
 */
fun interface GeoClock {
    /** Поточний час у мілісекундах від епохи Unix (UTC). */
    fun nowMs(): Long
}

/** Стандартний годинник — системний настінний час. */
object SystemGeoClock : GeoClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Стабільні ідентифікатори джерел. Використовуються і клієнтами (для звіту
 * спостерігачеві), і реєстром — рядок-літерал у двох місцях розійшовся б.
 */
object GeoSourceIds {
    const val TRANSITLAND: String = "transitland"
    const val AQICN: String = "aqicn"
    const val NASA_FIRMS: String = "nasa_firms"
    const val USGS_QUAKE: String = "usgs_quake"
    const val OPENSKY: String = "opensky"
    const val GFW_AIS: String = "gfw_ais"
}

/**
 * Прямокутник запиту в градусах WGS-84.
 *
 * Усі клієнти приймають саме bbox (або точку+радіус, зведену до bbox через
 * [around]): глобальний запит «дай усе» до цих джерел або коштує квоту в
 * кратному розмірі, або просто відхиляється сервером.
 *
 * Порядок координат у різних API різний (Transitland і GFW чекають довготу
 * першою, OpenSky — окремі іменовані параметри), тому тут зберігається
 * канонічний lat/lon, а перестановку робить кожен клієнт у себе.
 */
data class GeoBBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    /** Прямокутник придатний до запиту: скінченні числа, у межах Землі, min ≤ max. */
    val isValid: Boolean
        get() = minLat.isFinite() && minLon.isFinite() && maxLat.isFinite() && maxLon.isFinite() &&
            minLat >= -90.0 && maxLat <= 90.0 && minLon >= -180.0 && maxLon <= 180.0 &&
            minLat <= maxLat && minLon <= maxLon

    /**
     * Площа в квадратних градусах. Саме ця (а не метрична) величина визначає
     * тарифікацію OpenSky, тому вона тут, а не в клієнті.
     */
    val areaSquareDegrees: Double
        get() = if (!isValid) Double.NaN else (maxLat - minLat) * (maxLon - minLon)

    /** Широта центру. */
    val centerLat: Double get() = (minLat + maxLat) / 2.0

    /** Довгота центру. */
    val centerLon: Double get() = (minLon + maxLon) / 2.0

    /** Чи точка всередині прямокутника (межі включно). */
    fun contains(lat: Double, lon: Double): Boolean =
        isValid && lat in minLat..maxLat && lon in minLon..maxLon

    /** Канонічний рядок «minLat,minLon,maxLat,maxLon» — базис ключа кеша. */
    fun canonical(): String = "$minLat,$minLon,$maxLat,$maxLon"

    companion object {
        /** Метрів на градус широти — сфероїд тут зайвий, похибка < 0.5 %. */
        private const val METERS_PER_DEGREE_LAT = 111_320.0

        /**
         * Прямокутник з точки та радіуса в метрах. `null`, якщо вхід
         * непридатний (NaN, відʼємний радіус) — мовчазне «нуль метрів»
         * дало б порожню відповідь без пояснення.
         */
        fun around(lat: Double, lon: Double, radiusMeters: Double): GeoBBox? {
            if (!lat.isFinite() || !lon.isFinite() || !radiusMeters.isFinite()) return null
            if (radiusMeters <= 0.0) return null
            val dLat = radiusMeters / METERS_PER_DEGREE_LAT
            val cosLat = cos(lat * PI / 180.0)
            // Біля полюса косинус вироджується і крок по довготі йде в
            // нескінченність — там чесніше взяти весь меридіанний діапазон.
            val dLon = if (abs(cosLat) < 1e-6) 180.0 else radiusMeters / (METERS_PER_DEGREE_LAT * abs(cosLat))
            return of(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        }

        /**
         * Прямокутник із обрізанням до меж Землі та впорядкуванням кутів.
         * `null`, якщо вхід містить NaN/нескінченність.
         */
        fun of(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): GeoBBox? {
            if (!minLat.isFinite() || !minLon.isFinite() || !maxLat.isFinite() || !maxLon.isFinite()) return null
            val loLat = max(-90.0, min(minLat, maxLat))
            val hiLat = min(90.0, max(minLat, maxLat))
            val loLon = max(-180.0, min(minLon, maxLon))
            val hiLon = min(180.0, max(minLon, maxLon))
            return GeoBBox(loLat, loLon, hiLat, hiLon)
        }
    }
}

/**
 * Причина, з якої джерело зараз не дало даних.
 *
 * Мережа, таймаут, 4xx/5xx, битий JSON, вичерпана квота — це нормальні
 * стани зовнішнього джерела, а не помилки програми. Виняток назовні змусив
 * би кожного викликача обгортати виклик у try/catch і однаково втратив би
 * причину; тут причина типізована і показувана в UI.
 */
sealed interface SourceUnavailable {
    /** Коротке пояснення для журналу та діагностичного екрана. */
    val detail: String

    /** Ключ джерела не налаштований — запит навіть не виконувався. */
    data object MissingApiKey : SourceUnavailable {
        override val detail: String = "ключ API не налаштований"
    }

    /** Джерело відхилило наш ключ (401/403 або явна відповідь «invalid key»). */
    data class InvalidApiKey(val note: String = "") : SourceUnavailable {
        override val detail: String = "ключ відхилено джерелом $note".trim()
    }

    /**
     * Спрацював обмежувач частоти — наш власний або серверний (429).
     * [retryAfterMs] — через скільки має сенс повторити.
     */
    data class RateLimited(val retryAfterMs: Long) : SourceUnavailable {
        override val detail: String = "обмеження частоти, повтор через ${retryAfterMs} мс"
    }

    /** Добова (або інша) квота вичерпана; [resetAtMs] — час обнулення, якщо відомий. */
    data class QuotaExhausted(val resetAtMs: Long?) : SourceUnavailable {
        override val detail: String = "квоту вичерпано" + (resetAtMs?.let { ", скидання о $it" } ?: "")
    }

    /** Джерело відповіло кодом поза 2xx. */
    data class HttpError(val code: Int) : SourceUnavailable {
        override val detail: String = "HTTP $code"
    }

    /** Транспортний збій: DNS, обрив, таймаут. */
    data class Transport(val cause: String) : SourceUnavailable {
        override val detail: String = "мережа: $cause"
    }

    /** Відповідь прийшла, але розібрати її не вдалося. */
    data class Malformed(val cause: String) : SourceUnavailable {
        override val detail: String = "невалідна відповідь: $cause"
    }

    /**
     * Відповідь прийшла, але не довела, що вона наша: підпису немає, він не
     * сходиться, або якоря довіри у збірці нема. [note] — готове речення для
     * людини, тож префікса тут немає. Повторювати марно, і `isRetryable`
     * саме тому лишає це поза списком.
     */
    data class Unproven(val note: String) : SourceUnavailable {
        override val detail: String = note
    }

    /** Відповідь 2xx із порожнім тілом — для всіх наших джерел це збій, а не «нуль обʼєктів». */
    data object EmptyPayload : SourceUnavailable {
        override val detail: String = "порожнє тіло відповіді"
    }

    /** Запит відхилено на нашому боці: непридатний bbox, надто велика площа. */
    data class RequestRejected(val why: String) : SourceUnavailable {
        override val detail: String = "запит відхилено: $why"
    }
}

/**
 * Результат звернення до зовнішнього джерела.
 *
 * Порожній список усередині [Ok] означає «джерело відповіло, обʼєктів немає» —
 * це принципово інший стан, ніж [Unavailable], і плутати їх не можна:
 * перше малює порожній шар, друге має показати, що шар мертвий.
 */
sealed interface GeoResult<out T> {
    /** Успіх. [fromCache] = дані віддані з локального кеша, мережі не було. */
    data class Ok<T>(val value: T, val fromCache: Boolean = false) : GeoResult<T>

    /** Даних немає; [reason] пояснює, чому саме. */
    data class Unavailable(val reason: SourceUnavailable) : GeoResult<Nothing>
}

/** Значення або `null`, якщо джерело недоступне. */
fun <T> GeoResult<T>.valueOrNull(): T? = (this as? GeoResult.Ok)?.value

/** Причина відмови або `null`, якщо результат успішний. */
fun GeoResult<*>.reasonOrNull(): SourceUnavailable? = (this as? GeoResult.Unavailable)?.reason

/** Чи результат успішний. */
val GeoResult<*>.isOk: Boolean get() = this is GeoResult.Ok

/** Перетворює вміст успішного результату, зберігаючи ознаку [GeoResult.Ok.fromCache]. */
inline fun <T, R> GeoResult<T>.mapValue(transform: (T) -> R): GeoResult<R> = when (this) {
    is GeoResult.Ok -> GeoResult.Ok(transform(value), fromCache)
    is GeoResult.Unavailable -> this
}

/**
 * Спостерігач за станом джерел. Реалізується реєстром
 * (`env/GeoSourceRegistry.kt`); клієнти лише звітують сюди про кожен виклик.
 *
 * Розділення потрібне, щоб клієнт не залежав від реєстру: у тестах джерело
 * піднімається без нього, а реєстр перевіряється без мережі.
 */
fun interface GeoSourceObserver {
    /** Викликається після кожної спроби звернення до джерела [sourceId]. */
    fun onResult(sourceId: String, result: GeoResult<*>)
}

/** Звітує результат спостерігачеві (якщо він є) і повертає його ж — для ланцюжка `return`. */
internal fun <T> GeoResult<T>.reportTo(observer: GeoSourceObserver?, sourceId: String): GeoResult<T> {
    observer?.onResult(sourceId, this)
    return this
}
