package local.phantom.companion.data

/**
 * Що телефон ВІДДАЄ під підпис ключа заяв ([ClaimSigner], №6.4) для сервера закладів і балів.
 *
 * Закритий перелік замість «дай підпис під цим реченням». Колись міст мав
 * публічний `proveSentence { pub, nonce, at -> текст }`: будь-який виклик у
 * застосунку міг підписати будь-який рядок, бо функція речення вільна ігнорувати
 * свої аргументи. Тепер викликач називає ЗАЯВУ (оцінка, відмітка, частка, мої
 * бали), а речення складає вона сама — з власним доменом (`phantom-venue-rate`,
 * `phantom-points-*`), якого більше ніде в продукті немає. Підпис під листом,
 * вкладенням чи свідченням правди цим шляхом не дістати.
 *
 * Речення — ті самі функції, що звіряються з векторами сервера
 * (`VenueClient.rateSentence`, `PointsClient.*Sentence`); друга копія розійшлася б
 * із першою на першому ж уточненні.
 */
sealed interface DeviceClaim {
    /** Речення під підписом: з відкритого ключа, разового числа й часу заяви. */
    fun sentence(pub: String, nonce: String, issuedMs: Long): String

    data class VenueRate(val venueId: String, val stars: Int) : DeviceClaim {
        override fun sentence(pub: String, nonce: String, issuedMs: Long) =
            VenueClient.rateSentence(pub, nonce, issuedMs, venueId, stars)
    }

    data class CheckIn(
        val meetupId: String,
        val venueId: String,
        val cell: String,
        val meetupAtMs: Long,
    ) : DeviceClaim {
        override fun sentence(pub: String, nonce: String, issuedMs: Long) =
            PointsClient.checkinSentence(pub, nonce, issuedMs, meetupId, venueId, cell, meetupAtMs)
    }

    /**
     * Перша оплачена частка (v2): транзакція, адреса, з якої платили, і id рахунку.
     * Те саме речення підписує й сам гаманець (див. [PointsClient.settled]).
     */
    data class Settled(val cluster: String, val tx: String, val address: String, val ref: String) : DeviceClaim {
        override fun sentence(pub: String, nonce: String, issuedMs: Long) =
            PointsClient.settledSentence(pub, nonce, issuedMs, cluster, tx, address, ref)
    }

    /** Мої власні бали. */
    data object Me : DeviceClaim {
        override fun sentence(pub: String, nonce: String, issuedMs: Long) =
            PointsClient.meSentence(pub, nonce, issuedMs)
    }

    data object Erase : DeviceClaim {
        override fun sentence(pub: String, nonce: String, issuedMs: Long) =
            "NP-ERASE-CLAIM-v1|$pub|$nonce|$issuedMs"
    }
}
