package com.mangatranslator.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.core.security.SessionStore
import com.mangatranslator.data.models.BillingVerifyResponse
import com.mangatranslator.data.repositories.AccountRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** One purchasable plan as Google Play sells it (price comes from Play, never hard-coded). */
data class PlayOffer(val planId: String, val productId: String, val formattedPrice: String, val billingPeriod: String, val details: ProductDetails, val offerToken: String)

sealed interface BillingEvent {
    data class Activated(val planId: String) : BillingEvent
    data object Pending : BillingEvent
    data object Cancelled : BillingEvent
    data class Failed(val error: AppException) : BillingEvent
}

/**
 * Google Play Billing. The purchase token is sent to our server
 * (billing-verify), which asks Google for the real state, grants the plan
 * and acknowledges the purchase. The app never decides the plan itself.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SupabaseApi,
    private val sessionStore: SessionStore,
    private val account: AccountRepository,
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connectLock = Mutex()
    private val _offers = MutableStateFlow<List<PlayOffer>>(emptyList())
    val offers: StateFlow<List<PlayOffer>> = _offers
    private val _available = MutableStateFlow<Boolean?>(null)
    /** null = not checked yet, false = Play Billing unavailable (no Play Store / not installed from Play / not configured). */
    val available: StateFlow<Boolean?> = _available
    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BillingEvent> = _events

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return true
        val done = CompletableDeferred<Boolean>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                done.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() {
                if (!done.isCompleted) done.complete(false)
            }
        })
        done.await()
    }

    /** Loads Play prices for the plans that have a Play product id. */
    suspend fun loadOffers(planProducts: Map<String, String>) {
        if (planProducts.isEmpty() || !connect()) {
            _available.value = false
            return
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            planProducts.values.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(ProductType.SUBS).build()
            },
        ).build()
        val result = client.queryProductDetails(params)
        val byProduct = planProducts.entries.associate { (plan, product) -> product to plan }
        val offers = result.productDetailsList.orEmpty().mapNotNull { pd ->
            val plan = byProduct[pd.productId] ?: return@mapNotNull null
            // The base plan (offerId == null), not a promotional offer.
            val offers = pd.subscriptionOfferDetails.orEmpty()
            val offer = offers.firstOrNull { it.offerId == null } ?: offers.lastOrNull() ?: return@mapNotNull null
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
            PlayOffer(plan, pd.productId, phase.formattedPrice, phase.billingPeriod, pd, offer.offerToken)
        }
        _offers.value = offers
        _available.value = result.billingResult.responseCode == BillingClient.BillingResponseCode.OK && offers.isNotEmpty()
    }

    fun launch(activity: Activity, offer: PlayOffer) {
        val userId = sessionStore.session.value?.userId ?: return
        scope.launch {
            val existing = activePurchases().firstOrNull { offer.productId !in it.products }
            val builder = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details).setOfferToken(offer.offerToken).build()),
                )
                // Binds the purchase to this account; the server refuses a token bought by someone else.
                .setObfuscatedAccountId(userId)
            if (existing != null) {
                // Pro <-> Premium switch: Play prorates and links the tokens; the server ends the old one.
                builder.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(existing.purchaseToken)
                        .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE)
                        .build(),
                )
            }
            val r = client.launchBillingFlow(activity, builder.build())
            if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                _events.tryEmit(BillingEvent.Failed(AppException("billing_unavailable", message = r.debugMessage)))
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { purchases.orEmpty().forEach { handle(it, notify = true) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> _events.tryEmit(BillingEvent.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restore() }
            else -> _events.tryEmit(BillingEvent.Failed(AppException("billing_unavailable", message = result.debugMessage)))
        }
    }

    /** Re-sends owned purchases to the server (new device, reinstall, missed verification). */
    suspend fun restore() {
        if (sessionStore.session.value == null || !connect()) return
        activePurchases().forEach { handle(it, notify = false) }
    }

    private suspend fun activePurchases(): List<Purchase> {
        if (!connect()) return emptyList()
        val r = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build())
        return r.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    }

    private suspend fun handle(p: Purchase, notify: Boolean) {
        when (p.purchaseState) {
            Purchase.PurchaseState.PENDING -> if (notify) _events.tryEmit(BillingEvent.Pending)
            Purchase.PurchaseState.PURCHASED -> try {
                val res = api.function("billing-verify", BillingVerifyResponse.serializer(), buildJsonObject {
                    put("product_id", p.products.firstOrNull() ?: "")
                    put("purchase_token", p.purchaseToken)
                })
                runCatching { account.refreshMe() }
                if (notify) _events.tryEmit(BillingEvent.Activated(res.planId))
            } catch (e: Exception) {
                // Not lost: restore() retries on next start, and Play keeps the purchase unacknowledged for 3 days.
                if (notify) _events.tryEmit(BillingEvent.Failed(e.asAppException()))
            }
            else -> Unit
        }
    }
}
