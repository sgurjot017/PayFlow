package com.payflow.android.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

/** Authenticated PayFlow backend contract. Stripe secret-key operations never happen in this client. */
interface PaymentBackendApi {
    @POST("v1/demo-orders")
    suspend fun createDemoOrder(): DemoOrderResponse

    @POST("v1/payment-attempts")
    suspend fun createPaymentAttempt(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreatePaymentAttemptRequest,
    ): PaymentAttemptResponse

    @GET("v1/payment-attempts/latest")
    suspend fun getLatestPaymentAttemptForOrder(@Query("orderId") orderId: String): Response<PaymentAttemptResponse>

    @GET("v1/payment-attempts")
    suspend fun listPaymentAttempts(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): List<PaymentAttemptResponse>

    @GET("v1/payment-attempts/{attemptId}")
    suspend fun getPaymentAttempt(@Path("attemptId") attemptId: String): PaymentAttemptResponse
}

data class CreatePaymentAttemptRequest(
    val orderId: String,
)

data class DemoOrderResponse(
    @SerializedName("id") val id: String,
    @SerializedName("amount_minor") val amountMinor: Long,
    @SerializedName("currency") val currency: String,
) {
    fun toDomain() = com.payflow.shared.DemoOrder(
        id = id,
        amountMinor = amountMinor,
        currency = currency,
    )
}
