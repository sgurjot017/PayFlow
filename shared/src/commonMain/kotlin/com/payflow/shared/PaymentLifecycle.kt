package com.payflow.shared

/** Pure, platform-neutral rules for payment-attempt status changes. */
object PaymentLifecycle {
    private val allowedTransitions = mapOf(
        PaymentStatus.CREATED to setOf(
            PaymentStatus.INITIATING,
            PaymentStatus.CANCELLED,
        ),
        PaymentStatus.INITIATING to setOf(
            PaymentStatus.REQUIRES_PAYMENT_METHOD,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.TIMED_OUT,
            PaymentStatus.UNKNOWN,
        ),
        PaymentStatus.REQUIRES_PAYMENT_METHOD to setOf(
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.TIMED_OUT,
            PaymentStatus.UNKNOWN,
        ),
        PaymentStatus.REQUIRES_ACTION to setOf(
            PaymentStatus.REQUIRES_PAYMENT_METHOD,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.TIMED_OUT,
            PaymentStatus.UNKNOWN,
        ),
        PaymentStatus.PROCESSING to setOf(
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.TIMED_OUT,
            PaymentStatus.UNKNOWN,
        ),
        // A timeout is an observation, not proof that the provider declined the payment.
        // Reconcile the existing attempt before offering a retry.
        PaymentStatus.TIMED_OUT to setOf(
            PaymentStatus.REQUIRES_PAYMENT_METHOD,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.UNKNOWN,
        ),
        // UNKNOWN can be resolved by a later provider status lookup or webhook.
        PaymentStatus.UNKNOWN to setOf(
            PaymentStatus.REQUIRES_PAYMENT_METHOD,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.TIMED_OUT,
        ),
    )

    fun canTransition(from: PaymentStatus, to: PaymentStatus): Boolean =
        from == to || to in allowedTransitions[from].orEmpty()

    fun transition(
        attempt: PaymentAttempt,
        to: PaymentStatus,
        atEpochMillis: Long,
    ): PaymentTransitionResult {
        if (atEpochMillis < attempt.updatedAtEpochMillis) {
            return PaymentTransitionResult.Rejected(
                attempt = attempt,
                requestedStatus = to,
                reason = PaymentTransitionRejection.TIMESTAMP_MOVED_BACKWARDS,
            )
        }

        if (attempt.status == to) {
            return PaymentTransitionResult.Unchanged(attempt)
        }

        if (!canTransition(attempt.status, to)) {
            return PaymentTransitionResult.Rejected(
                attempt = attempt,
                requestedStatus = to,
                reason = PaymentTransitionRejection.TRANSITION_NOT_ALLOWED,
            )
        }

        return PaymentTransitionResult.Applied(
            attempt.copy(status = to, updatedAtEpochMillis = atEpochMillis),
        )
    }
}

sealed interface PaymentTransitionResult {
    data class Applied(val attempt: PaymentAttempt) : PaymentTransitionResult
    data class Unchanged(val attempt: PaymentAttempt) : PaymentTransitionResult
    data class Rejected(
        val attempt: PaymentAttempt,
        val requestedStatus: PaymentStatus,
        val reason: PaymentTransitionRejection,
    ) : PaymentTransitionResult
}

enum class PaymentTransitionRejection {
    TRANSITION_NOT_ALLOWED,
    TIMESTAMP_MOVED_BACKWARDS,
}
