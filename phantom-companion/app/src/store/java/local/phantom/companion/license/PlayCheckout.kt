package local.phantom.companion.license

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Google Play Billing для store-збірки.
 *
 * Телефон НЕ вирішує, чи покупка справжня, і НЕ підтверджує її сам: токен
 * їде на сервер ліцензій (`/api/play/verify`), той питає Google, видає ключ і
 * підтверджує покупку. Тут лише три речі: показати ціни від Play, запустити
 * системний діалог оплати і передати токен далі.
 *
 * Товари — не витратні (ліцензія купується раз), тож consume тут немає ніколи.
 */
internal class PlayCheckout(
    context: Context,
    private val scope: CoroutineScope,
    private val license: LicenseService,
    private val catalogue: List<PlayCatalogue.Item>,
) : Checkout, PurchasesUpdatedListener {

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        // Оплата готівкою чи переказом приходить не одразу — Play вимагає
        // явно сказати, що ми вміємо чекати.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val details = ConcurrentHashMap<String, ProductDetails>()

    private val _offers = MutableStateFlow<List<CheckoutOffer>>(emptyList())
    override val offers: StateFlow<List<CheckoutOffer>> = _offers.asStateFlow()

    private val _status = MutableStateFlow<CheckoutStatus>(CheckoutStatus.Idle)
    override val status: StateFlow<CheckoutStatus> = _status.asStateFlow()

    override fun start() = connect {
        loadOffers()
        // Покупка, яку оплатили, але не обміняли на ключ (застосунок убили на
        // півдорозі, мережа впала), — доводимо до кінця тихо. Інакше Play за
        // три дні без підтвердження повернув би гроші сам, а людина лишилась
        // би без ключа й без пояснення.
        if (license.state.value.certificate == null) reconcile(announceEmpty = false)
    }

    override fun restore() = connect {
        _status.value = CheckoutStatus.Working
        reconcile(announceEmpty = true)
    }

    override fun buy(activity: Activity, offerId: String) = connect {
        val product = details[offerId]
        if (product == null) {
            _status.value = CheckoutStatus.Failed(0, "product not loaded")
            return@connect
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
            .apply {
                product.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { setOfferToken(it) }
            }
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build(),
        )
        _status.value = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            CheckoutStatus.Working
        } else {
            failure(result)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::redeem)
            BillingClient.BillingResponseCode.USER_CANCELED -> _status.value = CheckoutStatus.Idle
            // Уже куплено (інший пристрій, перевстановлення) — це не помилка,
            // це «віддайте мені мій ключ».
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> reconcile(announceEmpty = true)
            else -> _status.value = failure(result)
        }
    }

    private fun connect(then: () -> Unit) {
        if (client.isReady) {
            then()
            return
        }
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        then()
                    } else {
                        Log.w(TAG, "billing setup: ${result.responseCode} ${result.debugMessage}")
                        _status.value = CheckoutStatus.Unavailable
                    }
                }

                // Повторне з'єднання робить enableAutoServiceReconnection.
                override fun onBillingServiceDisconnected() = Unit
            },
        )
    }

    private fun loadOffers() {
        catalogue.groupBy { it.kind }.forEach { (kind, items) ->
            val products = items.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it.productId)
                    .setProductType(productType(kind))
                    .build()
            }
            client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(products).build(),
            ) { result, found ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "product details: ${result.responseCode} ${result.debugMessage}")
                    return@queryProductDetailsAsync
                }
                found.productDetailsList.forEach { details[it.productId] = it }
                publishOffers()
            }
        }
    }

    private fun publishOffers() {
        // Порядок — як у каталозі збірки, а не як відповів Play.
        _offers.value = catalogue.mapNotNull { item ->
            val product = details[item.productId] ?: return@mapNotNull null
            val price = if (item.kind == "subs") {
                product.subscriptionOfferDetails?.firstOrNull()
                    ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            } else {
                product.oneTimePurchaseOfferDetails?.formattedPrice
            } ?: return@mapNotNull null
            CheckoutOffer(item.productId, item.kind, product.name, price)
        }
    }

    private fun reconcile(announceEmpty: Boolean) {
        val kinds = catalogue.map { it.kind }.distinct()
        val owned = ConcurrentHashMap.newKeySet<Purchase>()
        var answered = 0
        kinds.forEach { kind ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(productType(kind)).build(),
            ) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    owned += purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                }
                synchronized(owned) {
                    answered += 1
                    if (answered < kinds.size) return@queryPurchasesAsync
                }
                if (owned.isEmpty()) {
                    if (announceEmpty) _status.value = CheckoutStatus.NothingToRestore
                } else {
                    owned.forEach(::redeem)
                }
            }
        }
    }

    private fun redeem(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            _status.value = CheckoutStatus.Pending
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val productId = purchase.products.firstOrNull() ?: return
        val kind = catalogue.firstOrNull { it.productId == productId }?.kind ?: return
        _status.value = CheckoutStatus.Working
        scope.launch {
            _status.value = license.redeemPlayPurchase(kind, productId, purchase.purchaseToken).fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is PlayRedemption.Key -> CheckoutStatus.Activated
                        PlayRedemption.Pending -> CheckoutStatus.Pending
                        is PlayRedemption.Refused -> CheckoutStatus.Failed(outcome.code, outcome.message)
                    }
                },
                onFailure = { t ->
                    val code = (t as? LicenseService.LicenseError.Server)?.code ?: 0
                    CheckoutStatus.Failed(code, t.message.orEmpty())
                },
            )
        }
    }

    private fun failure(result: BillingResult) =
        CheckoutStatus.Failed(result.responseCode, result.debugMessage)

    private fun productType(kind: String) =
        if (kind == "subs") BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

    private companion object {
        const val TAG = "PlayCheckout"
    }
}
