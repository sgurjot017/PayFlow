package com.payflow.shared

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PaymentAttemptTest {
    @Test
    fun rejectsNegativeAmountsFromAnUntrustedResponse() {
        assertFailsWith<IllegalArgumentException> {
            attempt(amountMinor = -1)
        }
    }

    @Test
    fun rejectsMalformedCurrencyFromAnUntrustedResponse() {
        assertFailsWith<IllegalArgumentException> {
            attempt(currency = "usd")
        }
    }

    @Test
    fun rejectsUpdatedTimestampBeforeCreation() {
        assertFailsWith<IllegalArgumentException> {
            attempt(createdAtEpochMillis = 20, updatedAtEpochMillis = 19)
        }
    }

    private fun attempt(
        amountMinor: Long = 2499,
        currency: String = "USD",
        createdAtEpochMillis: Long = 100,
        updatedAtEpochMillis: Long = createdAtEpochMillis,
    ) = PaymentAttempt(
        id = "attempt-1",
        orderId = "order-1",
        amountMinor = amountMinor,
        currency = currency,
        provider = "stripe",
        status = PaymentStatus.CREATED,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
