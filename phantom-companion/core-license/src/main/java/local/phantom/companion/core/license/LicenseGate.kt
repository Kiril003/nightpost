package local.phantom.companion.core.license

enum class LicenseReason {
    Licensed,
    Trial,
    NoCertificate,
    BadSignature,
    ForeignDevice,
    Revoked,
    Blocked,
    BuildRetired,
    GraceExpired,
}

data class LicenseVerdict(
    val tier: LicenseTier,
    val features: Set<LicenseFeature>,
    val reason: LicenseReason,
    val certificate: LicenseCertificate?,
    val graceDaysLeft: Int?,
    val trialDaysLeft: Int?,
    val notice: String?,
    /**
     * Що лишається БЕЗ ключа. Під час проби відкрито все, і без цієї множини
     * екран не може чесно сказати, що саме зникне на пʼятнадцятий день —
     * тобто за що людина, власне, платить.
     */
    val freeFeatures: Set<LicenseFeature> = emptySet(),
    // Сервер підписано не визнає цю активацію: людині є що зробити — активувати
    // знову. Просто «немає мережі» цього прапорця не піднімає.
    val reactivateRequired: Boolean = false,
) {
    fun has(feature: LicenseFeature): Boolean = feature in features

    /** Відкрито зараз, але зникне, коли проба скінчиться. */
    fun trialOnly(feature: LicenseFeature): Boolean =
        reason == LicenseReason.Trial && feature in features && feature !in freeFeatures

    val paid: Boolean get() = tier != LicenseTier.Free
}

object LicenseGate {

    data class Input(
        val certificateJson: String?,
        val policyJson: String?,
        val publicKeyHex: String?,
        val deviceFingerprint: String,
        val buildNumber: Int,
        val nowMs: Long,
        val lastCheckInMs: Long,
        val firstRunMs: Long,
        val ledger: LicenseLedger = LicenseLedger.EMPTY,
    )

    fun decide(input: Input): LicenseVerdict {
        val ledger = input.ledger
        // Штамп політики звіряємо лише з найвищим баченим штампом: обидва —
        // серверні, тож переведення годинника на телефоні тут нічого не важить,
        // а раз захоплену стару копію вже не переграти після перевстановлення.
        val fresh = BuildPolicy.verified(input.policyJson, input.publicKeyHex)
            ?.takeIf { it.issuedAtMs >= ledger.policyIssuedAtMs }
        val policy = fresh ?: BuildPolicy.DEFAULT
        // DEFAULT_FREE, не SAFETY_FLOOR: інакше підписана політика могла б забрати
        // чат і голос у кожного, хто до нас достукався, лишивши їх тому, хто ніколи
        // не виходив на звʼязок. Сервер може розширити вільний рівень, звузити нижче
        // цього — ні.
        val freeSet = policy.free + LicenseModel.DEFAULT_FREE

        fun free(
            reason: LicenseReason,
            trialDaysLeft: Int? = null,
            reactivate: Boolean = false,
        ) = LicenseVerdict(
            tier = LicenseTier.Free,
            features = freeSet,
            reason = reason,
            certificate = null,
            graceDaysLeft = null,
            trialDaysLeft = trialDaysLeft,
            notice = policy.notice,
            freeFeatures = freeSet,
            reactivateRequired = reactivate,
        )

        // Відкинута (чи зникла) політика не скасовує того, що вже було сказано
        // підписано: інакше досить відкотити файл, щоб зняти заслін.
        val blocked = fresh?.let { input.deviceFingerprint.lowercase() in it.blocked.map(String::lowercase) }
            ?: ledger.policyBlocksDevice
        if (blocked) return free(LicenseReason.Blocked)
        val minBuild = fresh?.minBuild ?: ledger.policyMinBuild
        val reactivate = ledger.standing == ActivationStatus.Deactivated

        val document = input.certificateJson?.takeIf { it.isNotBlank() }
            ?.let(SignedDocument::parse)
        if (document == null) {
            // Сертифікат стерто, а `first_run` лишився: без цієї перевірки
            // звільнений чи відкликаний пристрій діставав повну пробу.
            if (ledger.gone) return free(LicenseReason.Revoked, reactivate = reactivate)
            val used = daysBetween(input.firstRunMs, input.nowMs)
            val left = LicenseModel.TRIAL_DAYS - used
            return if (input.firstRunMs > 0 && left > 0) {
                LicenseVerdict(
                    tier = LicenseTier.Personal,
                    features = LicenseModel.grants(LicenseTier.Personal) + freeSet,
                    reason = LicenseReason.Trial,
                    certificate = null,
                    graceDaysLeft = null,
                    trialDaysLeft = left,
                    notice = policy.notice,
                    freeFeatures = freeSet,
                )
            } else {
                free(LicenseReason.NoCertificate)
            }
        }

        val key = input.publicKeyHex
        if (key.isNullOrBlank() || !SignedDocument.verify(document, key)) {
            return free(LicenseReason.BadSignature)
        }
        val cert = LicenseCertificate.from(document) ?: return free(LicenseReason.BadSignature)
        if (!cert.deviceFingerprint.equals(input.deviceFingerprint, ignoreCase = true)) {
            return free(LicenseReason.ForeignDevice)
        }
        if (ledger.goneFor(cert.serial)) return free(LicenseReason.Revoked, reactivate = reactivate)
        if (cert.licenseId in policy.revoked) return free(LicenseReason.Revoked)
        if (input.buildNumber < minBuild) return free(LicenseReason.BuildRetired)

        // Два різні мовчання. Сервер, якого ми ДІСТАЛИ і який підписано не знає
        // цієї активації, більше не подовжує пільгу власними відповідями;
        // телефон без мережі дістає ту саму пільгу, що й досі.
        val denied = ledger.denied
        val anchor = if (denied && ledger.affirmedAtMs > 0L) {
            minOf(ledger.affirmedAtMs, input.lastCheckInMs)
        } else {
            input.lastCheckInMs
        }
        val silent = daysBetween(anchor, input.nowMs)
        if (anchor <= 0L || silent > LicenseModel.OFFLINE_GRACE_DAYS) {
            return free(LicenseReason.GraceExpired, reactivate = denied)
        }

        return LicenseVerdict(
            tier = cert.tier,
            features = LicenseModel.grants(cert.tier) + freeSet,
            reason = LicenseReason.Licensed,
            certificate = cert,
            graceDaysLeft = LicenseModel.OFFLINE_GRACE_DAYS - silent,
            trialDaysLeft = null,
            notice = policy.notice,
            freeFeatures = freeSet,
            reactivateRequired = denied,
        )
    }

    private fun daysBetween(fromMs: Long, toMs: Long): Int {
        if (fromMs <= 0L) return Int.MAX_VALUE
        return ((toMs - fromMs).coerceAtLeast(0L) / LicenseModel.DAY_MS).toInt()
    }
}
