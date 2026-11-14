package com.payflow.shared

/** Domain boundary for starting and reconciling payment attempts. */
interface PaymentProvider {
    suspend fun initiatePayment(request: PaymentRequest): PaymentResult
    suspend fun restoreLatestPaymentForOrder(orderId: String): PaymentResult?
    suspend fun retrievePayment(attemptId: String): PaymentResult
}

/**
 * Client-supplied payment intent input.
 * Amount and currency are deliberately omitted: the backend must resolve them from the order.
 */
data class PaymentRequest(
    val orderId: String,
    val idempotencyKey: String,
) {
    init {
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
    }
}

data class PaymentResult(
    val attempt: PaymentAttempt,
    val nextAction: PaymentNextAction? = null,
)

sealed interface PaymentNextAction {
    /** Stripe PaymentIntent client secret: hand directly to the SDK, then discard; never persist or log. */
    data class Authenticate(val clientSecret: String) : PaymentNextAction {
        init {
            require(clientSecret.isNotBlank()) { "clientSecret must not be blank" }
        }
    }

    data class Redirect(val url: String, val returnUrl: String) : PaymentNextAction
}
