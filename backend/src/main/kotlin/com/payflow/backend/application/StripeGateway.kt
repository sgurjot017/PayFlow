package com.payflow.backend.application

import com.payflow.backend.domain.PaymentAttemptRecord
import com.payflow.backend.domain.PayableOrder
import com.payflow.backend.domain.StripeEventSnapshot
import com.payflow.backend.domain.StripeIntentSnapshot
import com.payflow.backend.domain.stripeStatusToDomain
import com.payflow.shared.PaymentStatus
import com.stripe.StripeClient
import com.stripe.exception.ApiConnectionException
import com.stripe.exception.StripeException
import com.stripe.model.PaymentIntent
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentCreateParams

interface StripeGateway {
    fun createPaymentIntent(
        order: PayableOrder,
        attempt: PaymentAttemptRecord,
        providerIdempotencyKey: String,
    ): StripeIntentSnapshot

    fun retrievePaymentIntent(intentId: String): StripeIntentSnapshot

    fun verifyWebhook(payload: String, signature: String): StripeEventSnapshot?
}

class StripeSdkGateway(
    secretKey: String,
    private val webhookSecret: String,
) : StripeGateway {
    private val client = StripeClient.builder()
        .setApiKey(secretKey)
        .setConnectTimeout(10_000)
        .setReadTimeout(45_000)
        .setMaxNetworkRetries(2)
        .build()

    override fun createPaymentIntent(
        order: PayableOrder,
        attempt: PaymentAttemptRecord,
        providerIdempotencyKey: String,
    ): StripeIntentSnapshot {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(order.amountMinor)
            .setCurrency(order.currency.lowercase())
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .build(),
            )
            .putMetadata("payflow_attempt_id", attempt.id)
            .putMetadata("order_id", order.id)
            .build()
        val options = RequestOptions.builder()
            .setIdempotencyKey(providerIdempotencyKey)
            .setMaxNetworkRetries(2)
            .build()
        return client.v1().paymentIntents().create(params, options).toSnapshot()
    }

    override fun retrievePaymentIntent(intentId: String): StripeIntentSnapshot =
        client.v1().paymentIntents().retrieve(intentId).toSnapshot()

    override fun verifyWebhook(payload: String, signature: String): StripeEventSnapshot? {
        val verifiedEvent = com.stripe.net.Webhook.constructEvent(payload, signature, webhookSecret)
        if (!verifiedEvent.type.startsWith("payment_intent.")) return null
        val paymentIntent = verifiedEvent.dataObjectDeserializer.`object`
            .orElse(null) as? PaymentIntent
            ?: throw InvalidStripeWebhookException()
        return StripeEventSnapshot(
            eventId = verifiedEvent.id,
            eventType = verifiedEvent.type,
            eventCreatedAtEpochSeconds = verifiedEvent.created,
            paymentIntentId = paymentIntent.id,
            status = stripeStatusToDomain(paymentIntent.status),
        )
    }

    private fun PaymentIntent.toSnapshot() = StripeIntentSnapshot(
        id = id,
        clientSecret = clientSecret,
        status = stripeStatusToDomain(status),
    )
}

class InvalidStripeWebhookException : RuntimeException()
class StripeUnavailableException(cause: ApiConnectionException) : RuntimeException(cause)
class StripeOperationException(cause: StripeException) : RuntimeException(cause)

fun StripeGateway.createPaymentIntentSafely(
    order: PayableOrder,
    attempt: PaymentAttemptRecord,
    providerIdempotencyKey: String,
): StripeIntentSnapshot = try {
    createPaymentIntent(order, attempt, providerIdempotencyKey)
} catch (failure: ApiConnectionException) {
    throw StripeUnavailableException(failure)
} catch (failure: StripeException) {
    throw StripeOperationException(failure)
}

fun stripeEventTypeToStatus(eventType: String, intentStatus: PaymentStatus): PaymentStatus? = when (eventType) {
    "payment_intent.succeeded" -> PaymentStatus.SUCCEEDED
    "payment_intent.canceled" -> PaymentStatus.CANCELLED
    "payment_intent.processing" -> PaymentStatus.PROCESSING
    "payment_intent.payment_failed" -> intentStatus
    "payment_intent.requires_action" -> PaymentStatus.REQUIRES_ACTION
    else -> null
}
