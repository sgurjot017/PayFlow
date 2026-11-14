package com.payflow.android.data.remote

import com.google.gson.annotations.SerializedName
import com.payflow.shared.PaymentAttempt
import com.payflow.shared.PaymentNextAction
import com.payflow.shared.PaymentResult
import com.payflow.shared.PaymentStatus

/** API response; paymentIntentClientSecret is transient and must never be logged or persisted. */
data class PaymentAttemptResponse(
    @SerializedName("id") val id: String,
    @SerializedName("order_id") val orderId: String,
    @SerializedName("amount_minor") val amountMinor: Long,
    @SerializedName("currency") val currency: String,
    @SerializedName("provider") val provider: String,
    @SerializedName("status") val status: String,
    @SerializedName("created_at_epoch_millis") val createdAtEpochMillis: Long,
    @SerializedName("updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @SerializedName("payment_intent_client_secret") val paymentIntentClientSecret: String? = null,
) {
    fun toDomain(): PaymentResult {
        val parsedStatus = runCatching { PaymentStatus.valueOf(status.uppercase()) }
            .getOrDefault(PaymentStatus.UNKNOWN)
        return PaymentResult(
            attempt = PaymentAttempt(
                id = id,
                orderId = orderId,
                amountMinor = amountMinor,
                currency = currency,
                provider = provider,
                status = parsedStatus,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ),
            nextAction = paymentIntentClientSecret
                ?.takeIf(String::isNotBlank)
                ?.let { PaymentNextAction.Authenticate(it) },
        )
    }
}
