package com.payflow.backend

import com.payflow.backend.domain.stripeStatusToDomain
import com.payflow.shared.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StripeStatusMappingTest {
    @Test
    fun keepsRequiresPaymentMethodRetryableInsteadOfTreatingItAsTerminalFailure() {
        assertEquals(PaymentStatus.REQUIRES_PAYMENT_METHOD, stripeStatusToDomain("requires_payment_method"))
        assertEquals(PaymentStatus.REQUIRES_ACTION, stripeStatusToDomain("requires_action"))
        assertEquals(PaymentStatus.SUCCEEDED, stripeStatusToDomain("succeeded"))
    }

    @Test
    fun unknownProviderStatusRemainsReconciliable() {
        assertEquals(PaymentStatus.UNKNOWN, stripeStatusToDomain("future_provider_state"))
    }
}
