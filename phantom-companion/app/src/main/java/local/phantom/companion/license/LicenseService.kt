package local.phantom.companion.license

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.BuildConfig
import local.phantom.companion.Distribution
import local.phantom.companion.core.license.AvailableBuild
import local.phantom.companion.core.license.BuildPolicy
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.core.license.LicenseGate
import local.phantom.companion.core.license.LicenseKey
import local.phantom.companion.core.license.LicenseReason
import local.phantom.companion.core.license.LicenseVerdict
import local.phantom.companion.core.license.UpdateOffer

class LicenseService(
    context: Context,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Збірка не з кабінету. Читається ТУТ, а не всередині розрахунку: інакше
     * жоден тест не може подати друге значення, і правило про польові збірки
     * лишається твердженням про себе саме.
     */
    val developerBuild: Boolean = BuildConfig.DEBUG,
) {

    private val app = context.applicationContext
    private val vault = LicenseVault(app)
    private val sentry = IntegritySentry(app)
    private val json = Json { ignoreUnknownKeys = true }

    val integrity: IntegritySentry.Report by lazy {
        sentry.inspect(
            expectedSignatureSha256 = BuildConfig.EXPECTED_SIGNATURE,
            storeBuild = BuildConfig.FLAVOR == "store",
        )
    }

    private val http by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 8_000
            }
        }
    }

    private val _state = MutableStateFlow(evaluate())
    val state: StateFlow<LicenseVerdict> = _state.asStateFlow()

    /**
     * Оновлення, про яке варто сказати. Рахується з УЖЕ збереженої політики,
     * тож телефон без мережі не чекає нічого й не гальмує: перевірка оновлень
     * ніде не стоїть на дорозі до застосунку.
     */
    private val _update = MutableStateFlow(availableUpdate())
    val update: StateFlow<AvailableBuild?> = _update.asStateFlow()

    /**
     * Усе, що оголошено новішого за цю збірку, — без огляду на «пізніше» й на
     * ключ. Екран ліцензії питає саме це: відмова від картки не має стирати
     * адресу, а замкнене оновлення чесніше назвати, ніж сказати «у вас
     * найновіше».
     */
    private val _announced = MutableStateFlow(announcedUpdate())
    val announced: StateFlow<AvailableBuild?> = _announced.asStateFlow()

    /**
     * Завантаження й передача файла системному встановлювачу. Лише польова
     * збірка: `store` не має REQUEST_INSTALL_PACKAGES взагалі, і там кнопка
     * веде в кабінет, як раніше.
     */
    val installer: UpdateInstaller by lazy {
        UpdateInstaller(app, scope, link = ::buildLink)
    }

    val canInstallUpdates: Boolean get() = Distribution.selfUpdate

    val deviceFingerprint: String get() = vault.deviceFingerprint

    fun has(feature: LicenseFeature): Boolean = _state.value.has(feature)

    /** «Пізніше» мусить означати «поки не вийде ЩЕ новіше», а не «до перезапуску». */
    fun dismissUpdate() {
        _update.value?.let { vault.dismissedUpdateBuild = it.build }
        _update.value = null
    }

    fun start() {
        scope.launch { checkIn(force = false) }
    }

    suspend fun activate(rawKey: String): Result<LicenseVerdict> {
        val key = LicenseKey.normalize(rawKey)
        if (!LicenseKey.isWellFormed(key)) {
            return Result.failure(LicenseError.Malformed)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val response = http.post("${BuildConfig.LICENSE_BASE_URL}/api/activate") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"license_key":"$key","device_fingerprint":"${vault.deviceFingerprint}",""" +
                            """"device_name":"${deviceName()}"}""",
                    )
                }
                if (response.status != HttpStatusCode.OK) {
                    throw LicenseError.Server(response.status.value, response.bodyAsText())
                }
                val body = json.parseToJsonElement(response.bodyAsText()) as JsonObject
                val certificate = body["certificate"] as? JsonObject
                    ?: throw LicenseError.Server(response.status.value, "no certificate")
                vault.certificate = json.encodeToString(JsonObject.serializer(), certificate)
                vault.licenseKey = key
                vault.lastCheckInMs = nowMs()
                refreshPolicy()
                publish()
            }
        }
    }

    /**
     * Покупку Google Play обміняти на ключ і одразу активувати його.
     *
     * Ключ не народжується на телефоні: сервер сам питає Google, чи гроші
     * справді заплачено, і лише тоді видає ключ нашого формату. Той самий
     * токен завжди дає той самий ключ, тож повтор після обірваної мережі
     * безпечний. Далі — звичайна [activate], з тим самим лімітом пристроїв.
     */
    suspend fun redeemPlayPurchase(
        kind: String,
        productId: String,
        purchaseToken: String,
    ): Result<PlayRedemption> = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.post("${BuildConfig.LICENSE_BASE_URL}/api/play/verify") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", kind)
                            put("product_id", productId)
                            put("purchase_token", purchaseToken)
                        },
                    ),
                )
            }
            val outcome = PlayRedemption.parse(response.status.value, response.bodyAsText())
            if (outcome is PlayRedemption.Key) activate(outcome.licenseKey).getOrThrow()
            outcome
        }
    }

    suspend fun release(): Result<Unit> = withContext(Dispatchers.IO) {
        val key = vault.licenseKey
        runCatching {
            if (key != null) {
                http.post("${BuildConfig.LICENSE_BASE_URL}/api/deactivate") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"license_key":"$key","device_fingerprint":"${vault.deviceFingerprint}"}""",
                    )
                }
            }
            vault.clearCertificate()
            publish()
            Unit
        }
    }

    suspend fun checkIn(force: Boolean): Result<LicenseVerdict> {
        val since = nowMs() - vault.lastCheckInMs
        val certificate = vault.certificate
        if (!force && certificate != null && since < CHECK_IN_INTERVAL_MS) {
            return Result.success(_state.value)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                refreshPolicy()
                if (certificate != null) validateCertificate(certificate)
                publish()
            }
        }
    }

    /**
     * Адреса самих байтів, а не сторінки кабінету. Без ключа й без акаунта:
     * файл однаковий для всіх, ключем відмикаються можливості. Посилання живе
     * години, тож зберігати його нема сенсу — просимо перед завантаженням.
     */
    private suspend fun buildLink(): String? = withContext(Dispatchers.IO) {
        val response = runCatching {
            http.post("${BuildConfig.LICENSE_BASE_URL}/api/download/link") {
                contentType(ContentType.Application.Json)
                setBody("""{"platform":"${AvailableBuild.PLATFORM}"}""")
            }
        }.getOrNull() ?: return@withContext null
        if (response.status != HttpStatusCode.OK) return@withContext null
        val body = runCatching {
            json.parseToJsonElement(response.bodyAsText()) as JsonObject
        }.getOrNull() ?: return@withContext null
        (body["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("https://") }
    }

    private suspend fun refreshPolicy() {
        val response = runCatching {
            http.get("${BuildConfig.LICENSE_BASE_URL}/api/policy")
        }.getOrNull() ?: return
        // 204 (політики не публікували) чи будь-що інше — лишаємо останню бачену.
        if (response.status != HttpStatusCode.OK) return
        val text = runCatching { response.bodyAsText() }.getOrNull() ?: return
        val incoming = BuildPolicy.verified(text, publicKey()) ?: return
        // Пін монотонності: не приймаємо політику, СТАРІШУ за вже збережену.
        // Інакше той, хто керує мережею, переграв би раз захоплену дозвільну
        // копію, скинувши min_build і поіменні блоки. (Стійкий пін через
        // перевстановлення — за core-license; це заслін на рівні звірки.)
        val current = BuildPolicy.verified(vault.policy, publicKey())
        if (current != null && incoming.issuedAtMs < current.issuedAtMs) return
        vault.policy = text
    }

    private suspend fun validateCertificate(certificate: String) {
        val parsed = local.phantom.companion.core.license.LicenseCertificate.parse(certificate)
            ?: return
        val response = runCatching {
            http.post("${BuildConfig.LICENSE_BASE_URL}/api/validate") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"license_id":"${parsed.licenseId}","cert_serial":"${parsed.serial}",""" +
                        """"device_fingerprint":"${vault.deviceFingerprint}"}""",
                )
            }
        }.getOrNull() ?: return
        if (response.status != HttpStatusCode.OK) return
        val body = runCatching {
            json.parseToJsonElement(response.bodyAsText()) as JsonObject
        }.getOrNull() ?: return
        // Пристрій діє ЛИШЕ на ПІДПИСАНУ заяву. Голий JSON /api/validate нічого
        // не руйнує: інакше будь-хто на шляху (перехоплений TLS, ворожий DNS,
        // прострочений домен) відповів би «revoked» і стер би платний пристрій.
        val statement = body["statement"] as? JsonObject ?: return
        if (!local.phantom.companion.core.license.SignedDocument.verify(statement, publicKey())) return
        val fingerprint = (statement["device_fingerprint"] as? JsonPrimitive)?.contentOrNull
        val serial = (statement["serial"] as? JsonPrimitive)?.contentOrNull
        // Заява мусить стосуватися САМЕ цього пристрою й ПОТОЧНОГО сертифіката,
        // інакше стару заяву переграли б проти свіжого ключа.
        if (fingerprint != vault.deviceFingerprint || serial != parsed.serial) return
        when ((statement["status"] as? JsonPrimitive)?.contentOrNull) {
            "deactivated" -> {
                // Власник свідомо звільнив пристрій. Стираємо сертифікат, але
                // ЗБЕРІГАЄМО ключ — переактивація після помилки не має вимагати
                // листа про покупку.
                val savedKey = vault.licenseKey
                vault.clearCertificate()
                vault.licenseKey = savedKey
            }
            "revoked" -> {
                // Відкликання і так їде ПІДПИСАНОЮ політикою (LicenseGate звіряє
                // її з бази), тож сертифікат не чіпаємо: воротар сам опустить до
                // вільного рівня, а ключ і перший запуск лишаються цілими — без
                // цього відкликання у перші 14 днів дарувало б порушнику пробу.
            }
            "active", "unknown" -> {
                // «unknown» — сервер досяжний, але рядка не має (відновлення
                // тому, зміна двигуна): оновлюємо звірку, щоб онлайновий пристрій
                // не згас у пільзі через втрату даних на сервері. Оновлення лише
                // ПРОДОВЖУЄ доступ; відкликання відбирає його через політику.
                vault.lastCheckInMs = nowMs()
            }
        }
    }

    private fun publish(): LicenseVerdict = evaluate().also {
        _state.value = it
        _update.value = availableUpdate(it)
        _announced.value = announcedUpdate()
    }

    private fun storedPolicy(): BuildPolicy? = BuildPolicy.verified(vault.policy, publicKey())

    private fun announcedUpdate(): AvailableBuild? = if (!Distribution.cabinetUpdates) null else
        UpdateOffer.announcedFor(storedPolicy(), BuildConfig.VERSION_CODE, developerBuild)

    private fun availableUpdate(
        verdict: LicenseVerdict = _state.value,
    ): AvailableBuild? = if (!Distribution.cabinetUpdates) null else UpdateOffer.forThisBuild(
        policy = storedPolicy(),
        installedBuild = BuildConfig.VERSION_CODE,
        developerBuild = developerBuild,
        // Саме сертифікат, а не `paid`: у пробі рівень уже «Особистий», але
        // ключа в кабінеті немає, і за файлом там розвернуть.
        licensed = verdict.certificate != null,
        dismissedBuild = vault.dismissedUpdateBuild,
    )

    private fun evaluate(): LicenseVerdict = LicenseGate.decide(
        LicenseGate.Input(
            certificateJson = vault.certificate.takeIf { !integrity.tampered },
            policyJson = vault.policy,
            publicKeyHex = publicKey(),
            deviceFingerprint = vault.deviceFingerprint,
            buildNumber = BuildConfig.VERSION_CODE,
            nowMs = nowMs(),
            lastCheckInMs = vault.lastCheckInMs,
            firstRunMs = vault.firstRunMs(nowMs()),
        ),
    )

    private fun publicKey(): String = BuildConfig.LICENSE_PUBLIC_KEY

    private fun deviceName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(60)
            .replace("\"", "")

    sealed class LicenseError(message: String) : Exception(message) {
        data object Malformed : LicenseError("license key is malformed")
        class Server(val code: Int, body: String) : LicenseError(body)
    }

    companion object {
        const val CHECK_IN_INTERVAL_MS: Long = 20L * 60 * 60 * 1000

        fun explain(reason: LicenseReason): Int = when (reason) {
            LicenseReason.Licensed -> local.phantom.companion.R.string.license_state_active
            LicenseReason.Trial -> local.phantom.companion.R.string.license_state_trial
            LicenseReason.NoCertificate -> local.phantom.companion.R.string.license_state_free
            LicenseReason.BadSignature -> local.phantom.companion.R.string.license_state_broken
            LicenseReason.ForeignDevice -> local.phantom.companion.R.string.license_state_foreign
            LicenseReason.Revoked -> local.phantom.companion.R.string.license_state_revoked
            LicenseReason.Blocked -> local.phantom.companion.R.string.license_state_revoked
            LicenseReason.BuildRetired -> local.phantom.companion.R.string.license_state_outdated
            LicenseReason.GraceExpired -> local.phantom.companion.R.string.license_state_stale
        }
    }
}
