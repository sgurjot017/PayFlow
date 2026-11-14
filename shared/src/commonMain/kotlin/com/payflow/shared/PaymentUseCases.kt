package com.payflow.shared

/** Starts an attempt using an order ID; the trusted backend determines its amount and currency. */
class InitiatePaymentUseCase(
    private val paymentProvider: PaymentProvider,
) {
    suspend operator fun invoke(orderId: String, idempotencyKey: String): PaymentResult =
        paymentProvider.initiatePayment(
            PaymentRequest(
                orderId = orderId,
                idempotencyKey = idempotencyKey,
            ),
        )
}

/** Restores the latest order attempt, including a terminal result, without creating a duplicate. */
class RestoreLatestPaymentForOrderUseCase(
    private val paymentProvider: PaymentProvider,
) {
    suspend operator fun invoke(orderId: String): PaymentResult? {
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        return paymentProvider.restoreLatestPaymentForOrder(orderId)
    }
}

/** Reconciles an existing attempt with the provider-backed source of truth. */
class RefreshPaymentUseCase(
    private val paymentProvider: PaymentProvider,
) {
    suspend operator fun invoke(attemptId: String): PaymentResult {
        require(attemptId.isNotBlank()) { "attemptId must not be blank" }
        return paymentProvider.retrievePayment(attemptId)
    }
}
