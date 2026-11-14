package com.payflow.android.data.remote

import com.payflow.shared.PaymentNextAction
import com.payflow.shared.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentAttemptResponseTest {
    @Test
    fun mapsEphemeralClientSecretToAuthenticationAction() {
        val result = response(status = "requires_action", clientSecret = "pi_test_secret").toDomain()

        assertEquals(PaymentStatus.REQUIRES_ACTION, result.attempt.status)
        assertTrue(result.nextAction is PaymentNextAction.Authenticate)
        assertEquals("pi_test_secret", (result.nextAction as PaymentNextAction.Authenticate).clientSecret)
    }

    @Test
    fun mapsUnknownBackendStatusToUnknownAndDoesNotInventAction() {
        val result = response(status = "A_NEW_SERVER_STATE", clientSecret = null).toDomain()

        assertEquals(PaymentStatus.UNKNOWN, result.attempt.status)
        assertNull(result.nextAction)
    }

    private fun response(status: String, clientSecret: String?) = PaymentAttemptResponse(
        id = "attempt-7",
        orderId = "order-42",
        amountMinor = 4999,
        currency = "USD",
        provider = "stripe",
        status = status,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
        paymentIntentClientSecret = clientSecret,
    )
}
