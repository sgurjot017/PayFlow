package com.payflow.backend.domain

import com.payflow.shared.PaymentStatus

data class PayableOrder(
    val id: String,
    val ownerId: String,
    val amountMinor: Long,
    val currency: String,
    val status: OrderStatus,
)

enum class OrderStatus { PAYABLE, PAID, CANCELLED }

data class PaymentAttemptRecord(
    val id: String,
    val ownerId: String,
    val orderId: String,
    val idempotencyKey: String,
    val stripeIntentId: String?,
    val amountMinor: Long,
    val currency: String,
    val provider: String,
    val status: PaymentStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastStripeEventCreatedAtEpochSeconds: Long = 0,
)

data class StripeIntentSnapshot(
    val id: String,
    val clientSecret: String?,
    val status: PaymentStatus,
)

data class StripeEventSnapshot(
    val eventId: String,
    val eventType: String,
    val eventCreatedAtEpochSeconds: Long,
    val paymentIntentId: String,
    val status: PaymentStatus,
)

fun stripeStatusToDomain(status: String): PaymentStatus = when (status) {
    "requires_payment_method" -> PaymentStatus.REQUIRES_PAYMENT_METHOD
    "requires_action" -> PaymentStatus.REQUIRES_ACTION
    "requires_confirmation" -> PaymentStatus.INITIATING
    "processing" -> PaymentStatus.PROCESSING
    "succeeded" -> PaymentStatus.SUCCEEDED
    "canceled" -> PaymentStatus.CANCELLED
    else -> PaymentStatus.UNKNOWN
}
