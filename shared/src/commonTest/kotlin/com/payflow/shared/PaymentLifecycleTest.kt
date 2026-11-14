package com.payflow.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PaymentLifecycleTest {
    @Test
    fun allowsAuthenticationAndProcessingPathToSucceed() {
        val created = attempt()

        val initiating = applied(created, PaymentStatus.INITIATING, at = 110)
        val authenticating = applied(initiating, PaymentStatus.REQUIRES_ACTION, at = 120)
        val processing = applied(authenticating, PaymentStatus.PROCESSING, at = 130)
        val succeeded = applied(processing, PaymentStatus.SUCCEEDED, at = 140)

        assertEquals(PaymentStatus.SUCCEEDED, succeeded.status)
        assertEquals(140L, succeeded.updatedAtEpochMillis)
    }

    @Test
    fun allowsRetryablePaymentMethodStateToContinueThroughAuthentication() {
        val initiating = applied(attempt(), PaymentStatus.INITIATING, at = 110)
        val needsMethod = applied(initiating, PaymentStatus.REQUIRES_PAYMENT_METHOD, at = 120)
        val authentication = applied(needsMethod, PaymentStatus.REQUIRES_ACTION, at = 130)

        assertEquals(PaymentStatus.REQUIRES_ACTION, authentication.status)
    }

    @Test
    fun timeoutCanBeReconciledWithoutStartingAnotherAttempt() {
        val timedOut = applied(attempt(PaymentStatus.PROCESSING), PaymentStatus.TIMED_OUT, at = 120)

        val resolved = PaymentLifecycle.transition(timedOut, PaymentStatus.SUCCEEDED, atEpochMillis = 130)

        assertIs<PaymentTransitionResult.Applied>(resolved)
        assertEquals("attempt-1", resolved.attempt.id)
        assertEquals(PaymentStatus.SUCCEEDED, resolved.attempt.status)
    }

    @Test
    fun terminalStatusesRejectFurtherChanges() {
        listOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELLED).forEach { terminal ->
            val result = PaymentLifecycle.transition(
                attempt(terminal),
                PaymentStatus.PROCESSING,
                atEpochMillis = 120,
            )

            assertIs<PaymentTransitionResult.Rejected>(result)
            assertEquals(PaymentTransitionRejection.TRANSITION_NOT_ALLOWED, result.reason)
        }
    }

    @Test
    fun duplicateStatusEventIsIdempotent() {
        val current = attempt(PaymentStatus.PROCESSING)

        val result = PaymentLifecycle.transition(current, PaymentStatus.PROCESSING, atEpochMillis = 120)

        assertEquals(PaymentTransitionResult.Unchanged(current), result)
    }

    @Test
    fun rejectsTransitionWithEarlierTimestamp() {
        val result = PaymentLifecycle.transition(
            attempt(PaymentStatus.PROCESSING),
            PaymentStatus.SUCCEEDED,
            atEpochMillis = 99,
        )

        assertIs<PaymentTransitionResult.Rejected>(result)
        assertEquals(PaymentTransitionRejection.TIMESTAMP_MOVED_BACKWARDS, result.reason)
    }

    private fun applied(
        attempt: PaymentAttempt,
        status: PaymentStatus,
        at: Long,
    ): PaymentAttempt {
        val result = PaymentLifecycle.transition(attempt, status, atEpochMillis = at)
        assertIs<PaymentTransitionResult.Applied>(result)
        return result.attempt
    }

    private fun attempt(status: PaymentStatus = PaymentStatus.CREATED) = PaymentAttempt(
        id = "attempt-1",
        orderId = "order-1",
        amountMinor = 2499,
        currency = "USD",
        provider = "mock",
        status = status,
        createdAtEpochMillis = 100,
        updatedAtEpochMillis = 100,
    )
}
