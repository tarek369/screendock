package com.cressu.screendock.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cressu.screendock.R
import com.cressu.screendock.billing.BillingManager
import com.cressu.screendock.billing.PurchaseUiState
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.theme.Palette

/** Only used if Play has not (yet) returned localized product details. */
private const val FALLBACK_PRICE = "$9.99"

/** Everything the UI needs to render Pro entitlement + purchase feedback. */
data class ProUiState(
    val unlocked: Boolean,
    val formattedPrice: String?,
    val purchase: PurchaseUiState,
) {
    val priceLabel: String get() = formattedPrice ?: FALLBACK_PRICE
}

/**
 * Process-wide BillingManager. Lives outside composition so configuration
 * changes / activity recreation never build a second BillingClient.
 */
object BillingHolder {
    @Volatile private var instance: BillingManager? = null

    fun get(context: Context, repo: SettingsRepository): BillingManager =
        instance ?: synchronized(this) {
            instance ?: BillingManager(context.applicationContext, repo).also { instance = it }
        }
}

/** Unlock button + Pending / Error feedback, shared by Idle and Settings. */
@Composable
fun ProPurchaseButton(
    pro: ProUiState,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (val p = pro.purchase) {
            is PurchaseUiState.Pending -> {
                GradientButton(
                    text = stringResource(R.string.pro_purchase_pending),
                    icon = Icons.Rounded.Schedule,
                    muted = true,
                    onClick = {},
                    focusRequester = focusRequester,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pro_purchase_pending_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
            else -> {
                GradientButton(
                    text = stringResource(R.string.pro_unlock_price, pro.priceLabel),
                    icon = Icons.Rounded.Star,
                    onClick = onBuy,
                    focusRequester = focusRequester,
                )
                if (p is PurchaseUiState.Error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.error,
                    )
                }
            }
        }
    }
}
