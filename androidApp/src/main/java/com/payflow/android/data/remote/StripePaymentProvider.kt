package com.payflow.android.data.remote

import com.payflow.shared.PaymentProvider
import com.payflow.shared.PaymentRequest
import com.payflow.shared.PaymentResult
import retrofit2.HttpException
import javax.inject.Inject

/** Android data adapter. Stripe SDK confirmation is launched by the presentation host. */
class StripePaymentProvider @Inject constructor(
    private val api: PaymentBackendApi,
) : PaymentProvider {
    override suspend fun initiatePayment(request: PaymentRequest): PaymentResult =
        api.createPaymentAttempt(
            idempotencyKey = request.idempotencyKey,
            request = CreatePaymentAttemptRequest(orderId = request.orderId),
        ).toDomain()

    override suspend fun restoreLatestPaymentForOrder(orderId: String): PaymentResult? {
        val response = api.getLatestPaymentAttemptForOrder(orderId)
        if (response.isSuccessful) return response.body()?.toDomain()
        if (response.code() == 404) return null
        throw HttpException(response)
    }

    override suspend fun retrievePayment(attemptId: String): PaymentResult =
        api.getPaymentAttempt(attemptId).toDomain()
}
