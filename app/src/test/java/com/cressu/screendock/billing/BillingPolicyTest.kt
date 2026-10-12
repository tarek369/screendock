package com.cressu.screendock.billing

import com.android.billingclient.api.BillingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingPolicyTest {

    private val pid = BillingManager.PRODUCT_ID

    private fun purchase(
        products: List<String> = listOf(pid),
        purchased: Boolean = true,
        pending: Boolean = false,
        acknowledged: Boolean = false,
    ) = PurchaseSnapshot(products, purchased, pending, acknowledged)

    // --- backoff ---------------------------------------------------------

    @Test
    fun backoff_doublesFromOneSecond() {
        assertEquals(1_000L, BillingPolicy.backoffDelayMs(1))
        assertEquals(2_000L, BillingPolicy.backoffDelayMs(2))
        assertEquals(4_000L, BillingPolicy.backoffDelayMs(3))
        assertEquals(8_000L, BillingPolicy.backoffDelayMs(4))
    }

    @Test
    fun backoff_capsAtSixtySeconds() {
        assertEquals(32_000L, BillingPolicy.backoffDelayMs(6))
        assertEquals(60_000L, BillingPolicy.backoffDelayMs(7))
        assertEquals(60_000L, BillingPolicy.backoffDelayMs(100))
        assertEquals(60_000L, BillingPolicy.backoffDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun backoff_nonPositiveAttemptUsesBase() {
        assertEquals(1_000L, BillingPolicy.backoffDelayMs(0))
        assertEquals(1_000L, BillingPolicy.backoffDelayMs(-3))
    }

    @Test
    fun shouldRetrySetup_transientOnly() {
        assertTrue(BillingPolicy.shouldRetrySetup(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED))
        assertTrue(BillingPolicy.shouldRetrySetup(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
        assertTrue(BillingPolicy.shouldRetrySetup(BillingClient.BillingResponseCode.NETWORK_ERROR))
        assertFalse(BillingPolicy.shouldRetrySetup(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
        assertFalse(BillingPolicy.shouldRetrySetup(BillingClient.BillingResponseCode.DEVELOPER_ERROR))
        assertFalse(BillingPolicy.shouldRetrySetup(BillingClient.BillingResponseCode.OK))
    }

    // --- entitlement -----------------------------------------------------

    @Test
    fun resolveEntitlement_failedQueryIsUnknownNotRevoked() {
        assertNull(BillingPolicy.resolveEntitlement(false, listOf(purchase()), pid))
        assertNull(BillingPolicy.resolveEntitlement(false, emptyList(), pid))
    }

    @Test
    fun resolveEntitlement_successWithPurchasedGrants() {
        assertEquals(true, BillingPolicy.resolveEntitlement(true, listOf(purchase()), pid))
    }

    @Test
    fun resolveEntitlement_successWithoutPurchasedRevokes() {
        assertEquals(false, BillingPolicy.resolveEntitlement(true, emptyList(), pid))
        assertEquals(false, BillingPolicy.resolveEntitlement(true, listOf(purchase(products = listOf("other"))), pid))
    }

    @Test
    fun resolveEntitlement_pendingOnlyIsNotOwned() {
        val pending = purchase(purchased = false, pending = true)
        assertEquals(false, BillingPolicy.resolveEntitlement(true, listOf(pending), pid))
    }

    // --- purchase update classification ---------------------------------

    @Test
    fun classify_okWithPurchasedGrants() {
        assertEquals(
            PurchaseAction.GRANT,
            BillingPolicy.classifyPurchaseUpdate(BillingClient.BillingResponseCode.OK, listOf(purchase()), pid),
        )
    }

    @Test
    fun classify_okWithPendingIsPending() {
        val pending = purchase(purchased = false, pending = true)
        assertEquals(
            PurchaseAction.PENDING,
            BillingPolicy.classifyPurchaseUpdate(BillingClient.BillingResponseCode.OK, listOf(pending), pid),
        )
    }

    @Test
    fun classify_okWithNothingUsefulFails() {
        assertEquals(
            PurchaseAction.FAILED,
            BillingPolicy.classifyPurchaseUpdate(BillingClient.BillingResponseCode.OK, emptyList(), pid),
        )
    }

    @Test
    fun classify_userCanceled() {
        assertEquals(
            PurchaseAction.CANCELED,
            BillingPolicy.classifyPurchaseUpdate(BillingClient.BillingResponseCode.USER_CANCELED, emptyList(), pid),
        )
    }

    @Test
    fun classify_itemAlreadyOwnedRefreshes() {
        assertEquals(
            PurchaseAction.REFRESH_OWNED,
            BillingPolicy.classifyPurchaseUpdate(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED, emptyList(), pid),
        )
    }

    @Test
    fun classify_otherErrorFails() {
        assertEquals(
            PurchaseAction.FAILED,
            BillingPolicy.classifyPurchaseUpdate(BillingClient.BillingResponseCode.ERROR, emptyList(), pid),
        )
    }

    // --- acknowledgement -------------------------------------------------

    @Test
    fun needsAcknowledge_onlyPurchasedAndUnacknowledged() {
        assertTrue(BillingPolicy.needsAcknowledge(purchase(), pid))
        assertFalse(BillingPolicy.needsAcknowledge(purchase(acknowledged = true), pid))
        assertFalse(BillingPolicy.needsAcknowledge(purchase(purchased = false, pending = true), pid))
        assertFalse(BillingPolicy.needsAcknowledge(purchase(products = listOf("other")), pid))
    }

    // --- user messages ---------------------------------------------------

    @Test
    fun userMessage_neverEmptyAndSpecificWhereKnown() {
        assertTrue(BillingPolicy.userMessage(BillingClient.BillingResponseCode.NETWORK_ERROR).contains("Google Play"))
        assertTrue(BillingPolicy.userMessage(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE).contains("device"))
        assertTrue(BillingPolicy.userMessage(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE).contains("region"))
        assertTrue(BillingPolicy.userMessage(BillingClient.BillingResponseCode.DEVELOPER_ERROR).isNotBlank())
    }
}
