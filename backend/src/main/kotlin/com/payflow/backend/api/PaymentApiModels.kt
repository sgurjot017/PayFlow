package com.payflow.backend.api

import com.payflow.backend.domain.PaymentAttemptRecord
import com.payflow.backend.domain.PayableOrder
import com.payflow.shared.PaymentStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreatePaymentAttemptBody(
    val orderId: String,
)

@Serializable
data class DemoOrderApiResponse(
    val id: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
) {
    companion object {
        fun from(order: PayableOrder) = DemoOrderApiResponse(
            id = order.id,
            amountMinor = order.amountMinor,
            currency = order.currency,
        )
    }
}

@Serializable
data class PaymentAttemptApiResponse(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    val provider: String,
    val status: String,
    @SerialName("created_at_epoch_millis") val createdAtEpochMillis: Long,
    @SerialName("updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @SerialName("payment_intent_client_secret") val paymentIntentClientSecret: String? = null,
) {
    companion object {
        fun from(record: PaymentAttemptRecord, clientSecret: String? = null) = PaymentAttemptApiResponse(
            id = record.id,
            orderId = record.orderId,
            amountMinor = record.amountMinor,
            currency = record.currency,
            provider = record.provider,
            status = record.status.name,
            createdAtEpochMillis = record.createdAtEpochMillis,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
            paymentIntentClientSecret = clientSecret,
        )
    }
}

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
)

internal fun isPaymentTerminal(status: PaymentStatus): Boolean = status in setOf(
    PaymentStatus.SUCCEEDED,
    PaymentStatus.FAILED,
    PaymentStatus.CANCELLED,
)
