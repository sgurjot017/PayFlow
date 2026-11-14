package com.payflow.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaymentRequestTest {
    @Test
    fun acceptsOrderAndIdempotencyKeyWithoutClientPricing() {
        val request = PaymentRequest(
            orderId = "order-123",
            idempotencyKey = "attempt-123",
        )

        assertEquals("order-123", request.orderId)
        assertEquals("attempt-123", request.idempotencyKey)
    }

    @Test
    fun rejectsBlankOrderId() {
        assertFailsWith<IllegalArgumentException> {
            PaymentRequest(orderId = " ", idempotencyKey = "attempt-123")
        }
    }

    @Test
    fun rejectsBlankIdempotencyKey() {
        assertFailsWith<IllegalArgumentException> {
            PaymentRequest(orderId = "order-123", idempotencyKey = " ")
        }
    }
}
