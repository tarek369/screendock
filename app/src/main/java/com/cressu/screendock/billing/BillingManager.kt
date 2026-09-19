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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Google Play Billing wrapper for the one-time "unlock_pro" purchase
 * (ScreenDock Pro: unlimited mirroring sessions).
 *
 * Entitlement rules:
 *  - Source of truth is Play Billing (queryPurchasesAsync); the local
 *    DataStore flag is a cache so the service can enforce limits offline.
 *  - On devices without Play services (some emulators) billing is simply
 *    unavailable and the app runs as the free tier — never crashes.
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
    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var purchaseCallback: ((Boolean) -> Unit)? = null

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

    private fun connect() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        Log.i(TAG, "billing connected")
                        refreshEntitlement()
                        queryProduct()
                    }
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                        Log.w(TAG, "billing unavailable on this device; free tier only")
                    else -> Log.w(TAG, "billing setup failed: ${result.responseCode} ${result.debugMessage}")
                }
            }
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "billing disconnected; will reconnect on next start()")
                billingClient = null
            }
        })
    }

    /** Query current entitlement from Play and mirror it into app state. */
    fun refreshEntitlement() {
        val client = billingClient ?: return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(PRODUCT_TYPE).build()) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "purchase query failed: ${result.responseCode}")
                return@queryPurchasesAsync
            }
            val owned = purchases.any { p ->
                p.products.contains(PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            purchases.filter { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
                .forEach { p ->
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
                }
            setEntitlement(owned)
        }
    }

    private fun queryProduct() {
        val client = billingClient ?: return
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
                productDetails = details.first()
                Log.i(TAG, "product ${PRODUCT_ID} available")
            } else {
                Log.w(TAG, "product query: ${result.responseCode} (${details.size} results)")
            }
        }
    }

    /**
     * Launch the Play purchase flow. [onResult] fires with true once the
     * entitlement is confirmed (immediately for already-owned).
     */
    fun launchPurchase(activity: Activity, onResult: (Boolean) -> Unit) {
        purchaseCallback = onResult
        val details = productDetails
        val client = billingClient
        if (client == null || !client.isReady || details == null) {
            // Can't purchase here (no Play store, offline, etc.) — report failure.
            Log.w(TAG, "purchase launch unavailable")
            onResult(false)
            return
        }
        // One-time purchases need no offer token (that's subscriptions-only).
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        details.subscriptionOfferDetails?.firstOrNull()?.let { productParams.setOfferToken(it.offerToken) }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .build()
        client.launchBillingFlow(activity, flow)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val owned = purchases?.any { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED } == true
                purchases?.filter { !it.isAcknowledged }?.forEach { p ->
                    billingClient?.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    ) { }
                }
                if (owned) setEntitlement(true)
                purchaseCallback?.invoke(owned)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "purchase canceled by user")
                purchaseCallback?.invoke(false)
            }
            else -> {
                Log.w(TAG, "purchase update: ${result.responseCode} ${result.debugMessage}")
                purchaseCallback?.invoke(false)
            }
        }
    }

    private fun setEntitlement(owned: Boolean) {
        AirPlayService.proUnlocked.value = owned
        purchaseCallback?.let { cb -> if (owned) cb(true) }
        scope.launch {
            runCatching { repository.updateProUnlocked(owned) }
                .onFailure { Log.w(TAG, "persist entitlement failed: ${it.message}") }
        }
    }
}
