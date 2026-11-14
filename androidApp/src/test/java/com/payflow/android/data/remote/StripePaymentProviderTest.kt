package com.payflow.android.data.remote

import com.payflow.shared.PaymentNextAction
import com.payflow.shared.PaymentRequest
import com.payflow.shared.PaymentStatus
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripePaymentProviderTest {
    @Test
    fun createForwardsStableIdempotencyKeyAndOrderOnly() = runBlocking {
        val api = FakePaymentBackendApi()
        val provider = StripePaymentProvider(api)

        val result = provider.initiatePayment(
            PaymentRequest(orderId = "order-42", idempotencyKey = "checkout-key-1"),
        )

        assertEquals("checkout-key-1", api.receivedIdempotencyKey)
        assertEquals("order-42", api.receivedRequest?.orderId)
        assertEquals(4999L, result.attempt.amountMinor)
        assertEquals(PaymentStatus.REQUIRES_ACTION, result.attempt.status)
        assertTrue(result.nextAction is PaymentNextAction.Authenticate)
    }

    @Test
    fun activeAttemptCanBeResumedAfterProcessRecreation() = runBlocking {
        val provider = StripePaymentProvider(FakePaymentBackendApi())

        val result = provider.restoreLatestPaymentForOrder("order-42")

        assertEquals("attempt-7", result?.attempt?.id)
        assertEquals(PaymentStatus.REQUIRES_ACTION, result?.attempt?.status)
        assertTrue(result?.nextAction is PaymentNextAction.Authenticate)
    }

    private class FakePaymentBackendApi : PaymentBackendApi {
        var receivedIdempotencyKey: String? = null
        var receivedRequest: CreatePaymentAttemptRequest? = null

        override suspend fun createPaymentAttempt(
            idempotencyKey: String,
            request: CreatePaymentAttemptRequest,
        ): PaymentAttemptResponse {
            receivedIdempotencyKey = idempotencyKey
            receivedRequest = request
            return response(clientSecret = "pi_test_secret")
        }

        override suspend fun createDemoOrder(): DemoOrderResponse = DemoOrderResponse(
            id = "demo-order-002",
            amountMinor = 2499,
            currency = "USD",
        )

        override suspend fun getPaymentAttempt(attemptId: String): PaymentAttemptResponse =
            response(clientSecret = null)

        override suspend fun getLatestPaymentAttemptForOrder(orderId: String): Response<PaymentAttemptResponse> =
            Response.success(response(clientSecret = "pi_test_secret"))

        override suspend fun listPaymentAttempts(limit: Int, offset: Int): List<PaymentAttemptResponse> =
            listOf(response(clientSecret = null))
    }

    private companion object {
        fun response(clientSecret: String?) = PaymentAttemptResponse(
            id = "attempt-7",
            orderId = "order-42",
            amountMinor = 4999,
            currency = "USD",
            provider = "stripe",
            status = "REQUIRES_ACTION",
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
            paymentIntentClientSecret = clientSecret,
        )
    }
}
