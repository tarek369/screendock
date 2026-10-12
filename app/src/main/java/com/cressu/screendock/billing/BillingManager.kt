package com.cressu.screendock.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
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
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** UI-facing state of the Pro purchase flow. */
sealed class PurchaseUiState {
    data object Idle : PurchaseUiState()
    data object Pending : PurchaseUiState()
    data object Purchased : PurchaseUiState()
    data class Error(val message: String) : PurchaseUiState()
}

/** Billing-free view of a [Purchase], so the decision logic stays pure JVM code. */
data class PurchaseSnapshot(
    val products: List<String>,
    val isPurchased: Boolean,
    val isPending: Boolean,
    val isAcknowledged: Boolean,
)

/** What to do with a purchase-flow response. */
enum class PurchaseAction { GRANT, PENDING, CANCELED, REFRESH_OWNED, FAILED }

/** Pure decision logic extracted from [BillingManager] so it can be unit tested. */
internal object BillingPolicy {
    const val BASE_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 60_000L
    private const val MAX_SHIFT = 16

    /** Reconnect delay for 1-based [attempt]: 1s, 2s, 4s, ... capped at 60s. */
    fun backoffDelayMs(attempt: Int): Long {
        if (attempt <= 1) return BASE_BACKOFF_MS
        val shift = minOf(attempt - 1, MAX_SHIFT)
        return minOf(BASE_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
    }

    /** Transient setup failures are retried; permanent ones (no Play, bad config) are not. */
    fun shouldRetrySetup(responseCode: Int): Boolean = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.ERROR -> true
        else -> false
    }

    fun owns(purchase: PurchaseSnapshot, productId: String): Boolean =
        productId in purchase.products && purchase.isPurchased

    fun isPendingFor(purchase: PurchaseSnapshot, productId: String): Boolean =
        productId in purchase.products && purchase.isPending

    /**
     * Entitlement from a purchases query. Returns null when the query failed
     * (offline, service unavailable) so the caller keeps the last known state.
     * A successful query with no PURCHASED unlock means revoked (e.g. refund).
     */
    fun resolveEntitlement(queryOk: Boolean, purchases: List<PurchaseSnapshot>, productId: String): Boolean? {
        if (!queryOk) return null
        return purchases.any { owns(it, productId) }
    }

    fun classifyPurchaseUpdate(responseCode: Int, purchases: List<PurchaseSnapshot>, productId: String): PurchaseAction =
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> when {
                purchases.any { owns(it, productId) } -> PurchaseAction.GRANT
                purchases.any { isPendingFor(it, productId) } -> PurchaseAction.PENDING
                else -> PurchaseAction.FAILED
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseAction.CANCELED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseAction.REFRESH_OWNED
            else -> PurchaseAction.FAILED
        }

    /** Acknowledge only PURCHASED, not-yet-acknowledged purchases. Never PENDING. */
    fun needsAcknowledge(purchase: PurchaseSnapshot, productId: String): Boolean =
        owns(purchase, productId) && !purchase.isAcknowledged

    fun userMessage(responseCode: Int): String = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "Google Play is unreachable. Check your connection and try again."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "In-app purchases aren't available on this device."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "ScreenDock Pro isn't available in your region yet."
        else -> "The purchase didn't complete. Please try again."
    }
}

/**
 * Google Play Billing wrapper for the one-time "unlock_pro" purchase
 * (ScreenDock Pro: unlimited mirroring sessions).
 *
 * Entitlement rules:
 *  - Source of truth is Play Billing (queryPurchasesAsync); the local
 *    DataStore flag is a cache so the service can enforce limits offline.
 *  - Only PURCHASED purchases are acknowledged; PENDING ones are surfaced
 *    via [purchaseState] and granted when Play later reports PURCHASED.
 *  - On devices without Play services (some emulators) billing is simply
 *    unavailable and the app runs as the free tier — never crashes.
 *
 * Threading: all state is touched on the main thread (Billing callbacks and
 * Activity calls). Persistence runs on IO.
 */
class BillingManager(
    private val context: Context,
    private val repository: SettingsRepository,
) : PurchasesUpdatedListener {

    companion object {
        const val TAG = "ScreenDockBilling"
        const val PRODUCT_ID = "unlock_pro"
        private const val PRODUCT_TYPE = BillingClient.ProductType.INAPP
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var connecting = false
    private var retryAttempt = 0
    private var retryJob: Job? = null

    /** Consumed exactly once per purchase attempt, so the callback never fires twice. */
    private val purchaseCallback = AtomicReference<((Boolean) -> Unit)?>(null)

    private val _formattedPrice = MutableStateFlow<String?>(null)
    /** Localized price from Play (e.g. "$9.99"), null until product details load. */
    val formattedPrice: StateFlow<String?> = _formattedPrice.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)
    val purchaseState: StateFlow<PurchaseUiState> = _purchaseState.asStateFlow()

    fun start() {
        if (billingClient != null) return
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            // Billing 8: pending purchases must be enabled explicitly; one-time
            // products (our case) require the opt-in.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        connect()
    }

    /**
     * Call from the Activity's ON_RESUME. Starts or reconnects the client if
     * needed, re-queries purchases and product details.
     */
    fun refresh() {
        val client = billingClient
        if (client == null) {
            start()
            return
        }
        if (client.isReady) {
            refreshEntitlement()
            if (productDetails == null) queryProduct()
            return
        }
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        connect()
    }

    /** Tears down the Billing connection. A later [start] or [refresh] reconnects. */
    fun endConnection() {
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        connecting = false
        productDetails = null
        billingClient?.endConnection()
        billingClient = null
    }

    private fun connect() {
        val client = billingClient ?: return
        if (client.isReady || connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                val code = result.responseCode
                if (code == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "billing connected")
                    retryAttempt = 0
                    refreshEntitlement()
                    queryProduct()
                    return
                }
                Log.w(TAG, "billing setup failed: $code ${result.debugMessage}")
                if (BillingPolicy.shouldRetrySetup(code)) scheduleReconnect()
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                Log.w(TAG, "billing disconnected; scheduling reconnect")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        retryJob?.cancel()
        retryAttempt += 1
        val delayMs = BillingPolicy.backoffDelayMs(retryAttempt)
        Log.i(TAG, "billing reconnect in ${delayMs}ms (attempt $retryAttempt)")
        retryJob = mainScope.launch {
            delay(delayMs)
            connect()
        }
    }

    /** Query current entitlement from Play and mirror it into app state. */
    fun refreshEntitlement() {
        queryEntitlement { owned -> owned?.let { setEntitlement(it) } }
    }

    /**
     * Queries purchases and resolves entitlement. [onResolved] gets null when
     * the query failed, meaning "unknown", so the caller must not revoke.
     */
    private fun queryEntitlement(onResolved: (Boolean?) -> Unit) {
        val client = billingClient
        if (client == null || !client.isReady) {
            onResolved(null)
            return
        }
        val params = QueryPurchasesParams.newBuilder().setProductType(PRODUCT_TYPE).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            val queryOk = result.responseCode == BillingClient.BillingResponseCode.OK
            if (!queryOk) {
                Log.w(TAG, "purchase query failed: ${result.responseCode}")
            } else {
                acknowledgeUnacknowledged(client, purchases)
            }
            val snapshots = purchases.map { it.toSnapshot() }
            onResolved(BillingPolicy.resolveEntitlement(queryOk, snapshots, PRODUCT_ID))
        }
    }

    private fun acknowledgeUnacknowledged(client: BillingClient, purchases: List<Purchase>) {
        purchases.forEach { purchase ->
            if (!BillingPolicy.needsAcknowledge(purchase.toSnapshot(), PRODUCT_ID)) return@forEach
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { ack ->
                if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "acknowledge failed: ${ack.responseCode}")
                }
            }
        }
    }

    private fun queryProduct() {
        val client = billingClient ?: return
        if (!client.isReady) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(PRODUCT_TYPE)
                        .build()
                )
            ).build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            val details = queryResult?.productDetailsList ?: emptyList()
            if (result.responseCode == BillingClient.BillingResponseCode.OK && details.isNotEmpty()) {
                val first = details.first()
                productDetails = first
                _formattedPrice.value = first.oneTimePurchaseOfferDetails?.formattedPrice
                Log.i(TAG, "product $PRODUCT_ID available")
            } else {
                Log.w(TAG, "product query: ${result.responseCode} (${details.size} results)")
            }
        }
    }

    /**
     * Launch the Play purchase flow. [onResult] fires exactly once: with true
     * when entitlement is confirmed (immediately if already owned), false on
     * failure or cancel. A PENDING purchase keeps the callback until Play
     * reports it as PURCHASED. Feedback is exposed via [purchaseState].
     */
    fun launchPurchase(activity: Activity, onResult: (Boolean) -> Unit) {
        purchaseCallback.set(onResult)
        if (AirPlayService.proUnlocked.value) {
            finishPurchase(true, PurchaseUiState.Purchased)
            return
        }
        val client = billingClient
        if (client == null || !client.isReady) {
            Log.w(TAG, "purchase launch: store not connected")
            finishPurchase(false, PurchaseUiState.Error(BillingPolicy.userMessage(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)))
            refresh()
            return
        }
        val details = productDetails
        if (details == null) {
            Log.w(TAG, "purchase launch: product details missing")
            finishPurchase(false, PurchaseUiState.Error("The price is still loading. Try again in a moment."))
            queryProduct()
            return
        }
        // One-time purchases need no offer token (that's subscriptions-only).
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        details.subscriptionOfferDetails?.firstOrNull()?.let { productParams.setOfferToken(it.offerToken) }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .build()
        val launchResult = client.launchBillingFlow(activity, flow)
        when (launchResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> Unit // result arrives via onPurchasesUpdated
            else -> handlePurchaseResponse(launchResult.responseCode, emptyList())
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val list: List<Purchase> = purchases.orEmpty()
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            billingClient?.let { acknowledgeUnacknowledged(it, list) }
        }
        handlePurchaseResponse(result.responseCode, list)
    }

    private fun handlePurchaseResponse(responseCode: Int, purchases: List<Purchase>) {
        val snapshots = purchases.map { it.toSnapshot() }
        when (BillingPolicy.classifyPurchaseUpdate(responseCode, snapshots, PRODUCT_ID)) {
            PurchaseAction.GRANT -> {
                setEntitlement(true)
                finishPurchase(true, PurchaseUiState.Purchased)
            }
            PurchaseAction.PENDING -> {
                // Keep the callback: Play will report PURCHASED later.
                Log.i(TAG, "purchase pending; awaiting payment")
                _purchaseState.value = PurchaseUiState.Pending
            }
            PurchaseAction.CANCELED -> {
                Log.i(TAG, "purchase canceled by user")
                finishPurchase(false, PurchaseUiState.Idle)
            }
            PurchaseAction.REFRESH_OWNED -> {
                // Already owned on this Play account: re-sync and grant Pro.
                queryEntitlement { owned ->
                    if (owned == true) {
                        setEntitlement(true)
                        finishPurchase(true, PurchaseUiState.Purchased)
                    } else {
                        finishPurchase(
                            false,
                            PurchaseUiState.Error("We couldn't confirm your purchase. Try again in a moment.")
                        )
                    }
                }
            }
            PurchaseAction.FAILED -> {
                Log.w(TAG, "purchase failed: $responseCode")
                finishPurchase(false, PurchaseUiState.Error(BillingPolicy.userMessage(responseCode)))
            }
        }
    }

    /** Publishes the final state and fires the attempt's callback at most once. */
    private fun finishPurchase(granted: Boolean, state: PurchaseUiState) {
        _purchaseState.value = state
        purchaseCallback.getAndSet(null)?.invoke(granted)
    }

    private fun setEntitlement(owned: Boolean) {
        AirPlayService.proUnlocked.value = owned
        scope.launch {
            runCatching { repository.updateProUnlocked(owned) }
                .onFailure { Log.w(TAG, "persist entitlement failed: ${it.message}") }
        }
    }

    private fun Purchase.toSnapshot() = PurchaseSnapshot(
        products = products,
        isPurchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        isPending = purchaseState == Purchase.PurchaseState.PENDING,
        isAcknowledged = isAcknowledged,
    )
}
