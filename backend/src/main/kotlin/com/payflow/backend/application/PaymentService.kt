package com.payflow.backend.application

import com.payflow.backend.api.PaymentAttemptApiResponse
import com.payflow.backend.data.PaymentAttemptRepository
import com.payflow.backend.data.ReserveAttemptResult
import com.payflow.backend.domain.PaymentAttemptRecord
import com.payflow.backend.domain.PayableOrder
import com.payflow.backend.domain.StripeIntentSnapshot
import com.payflow.shared.PaymentStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaymentService(
    private val repository: PaymentAttemptRepository,
    private val stripe: StripeGateway,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun createLocalDemoOrder(ownerId: String): PayableOrder = withContext(Dispatchers.IO) {
        repository.createLocalDemoOrder(
            ownerId = ownerId,
            orderId = "demo-order-${UUID.randomUUID()}",
            nowEpochMillis = clockMillis(),
        )
    }

    suspend fun createAttempt(
        ownerId: String,
        orderId: String,
        idempotencyKey: String,
    ): PaymentAttemptApiResponse = withContext(Dispatchers.IO) {
        when (val reservation = repository.reserveAttempt(
            ownerId = ownerId,
            orderId = orderId,
            idempotencyKey = idempotencyKey,
            attemptId = UUID.randomUUID().toString(),
            nowEpochMillis = clockMillis(),
        )) {
            ReserveAttemptResult.OrderNotFound -> throw PaymentServiceFailure(PaymentFailureType.NOT_FOUND)
            ReserveAttemptResult.OrderNotPayable,
            ReserveAttemptResult.ActiveAttemptExists,
            ReserveAttemptResult.IdempotencyConflict,
            -> throw PaymentServiceFailure(PaymentFailureType.CONFLICT)

            is ReserveAttemptResult.Reserved -> createStripeIntent(reservation.order, reservation.attempt)
            is ReserveAttemptResult.Existing -> resumeIdempotentAttempt(reservation.order, reservation.attempt)
        }
    }

    suspend fun getAttempt(ownerId: String, attemptId: String): PaymentAttemptApiResponse = withContext(Dispatchers.IO) {
        val attempt = repository.findAttempt(ownerId, attemptId)
            ?: throw PaymentServiceFailure(PaymentFailureType.NOT_FOUND)
        val stripeIntentId = attempt.stripeIntentId
        if (stripeIntentId == null || isPaymentTerminal(attempt.status)) {
            return@withContext PaymentAttemptApiResponse.from(attempt)
        }

        val snapshot = retrieveStripeIntent(stripeIntentId)
        repository.updateProviderStatus(attempt.id, snapshot.status, clockMillis())
        val refreshed = repository.findAttempt(ownerId, attemptId) ?: attempt
        PaymentAttemptApiResponse.from(refreshed)
    }

    suspend fun listAttempts(ownerId: String, limit: Int, offset: Int): List<PaymentAttemptApiResponse> =
        withContext(Dispatchers.IO) {
            repository.listAttempts(ownerId, limit.coerceIn(1, 100), offset.coerceAtLeast(0))
                .map(PaymentAttemptApiResponse::from)
        }

    /** Returns an owner's active attempt for safe recovery after app process death. */
    suspend fun getActiveAttempt(ownerId: String, orderId: String): PaymentAttemptApiResponse = withContext(Dispatchers.IO) {
        val attempt = repository.findActiveAttempt(ownerId, orderId)
            ?: throw PaymentServiceFailure(PaymentFailureType.NOT_FOUND)
        val stripeIntentId = attempt.stripeIntentId
        if (stripeIntentId == null) {
            val order = repository.findOrder(ownerId, orderId)
                ?: throw PaymentServiceFailure(PaymentFailureType.NOT_FOUND)
            return@withContext createStripeIntent(order, attempt)
        }

        val snapshot = retrieveStripeIntent(stripeIntentId)
        repository.updateProviderStatus(attempt.id, snapshot.status, clockMillis())
        val refreshed = repository.findAttempt(ownerId, attempt.id) ?: attempt
        PaymentAttemptApiResponse.from(refreshed, snapshot.clientSecret.forPaymentSheet(snapshot.status))
    }

    /** Restores the latest order attempt, including terminal results, after app process recreation. */
    suspend fun getLatestAttemptForOrder(ownerId: String, orderId: String): PaymentAttemptApiResponse =
        withContext(Dispatchers.IO) {
            val attempt = repository.findLatestAttemptForOrder(ownerId, orderId)
                ?: throw PaymentServiceFailure(PaymentFailureType.NOT_FOUND)
            if (isPaymentTerminal(attempt.status)) {
                return@withContext PaymentAttemptApiResponse.from(attempt)
            }

            val stripeIntentId = attempt.stripeIntentId
            if (stripeIntentId == null) {
                val order = repository.findOrder(ownerId, orderId)
                    ?: throw PaymentServiceFailure(PaymentFailureType.NOT_FOUND)
                return@withContext createStripeIntent(order, attempt)
            }

            val snapshot = retrieveStripeIntent(stripeIntentId)
            repository.updateProviderStatus(attempt.id, snapshot.status, clockMillis())
            val refreshed = repository.findAttempt(ownerId, attempt.id) ?: attempt
            PaymentAttemptApiResponse.from(refreshed, snapshot.clientSecret.forPaymentSheet(snapshot.status))
        }

    suspend fun processWebhook(payload: String, signature: String): Boolean = withContext(Dispatchers.IO) {
        val event = try {
            stripe.verifyWebhook(payload, signature)
        } catch (_: Exception) {
            throw PaymentServiceFailure(PaymentFailureType.INVALID_WEBHOOK)
        } ?: return@withContext false

        val status = stripeEventTypeToStatus(event.eventType, event.status) ?: return@withContext false
        repository.applyWebhookEvent(
            eventId = event.eventId,
            eventCreatedAtEpochSeconds = event.eventCreatedAtEpochSeconds,
            stripeIntentId = event.paymentIntentId,
            status = status,
            nowEpochMillis = clockMillis(),
        )
    }

    private fun createStripeIntent(order: PayableOrder, attempt: PaymentAttemptRecord): PaymentAttemptApiResponse {
        val providerKey = providerIdempotencyKey(attempt.ownerId, attempt.idempotencyKey)
        val intent = try {
            stripe.createPaymentIntentSafely(order, attempt, providerKey)
        } catch (_: StripeUnavailableException) {
            repository.updateProviderStatus(attempt.id, PaymentStatus.UNKNOWN, clockMillis())
            throw PaymentServiceFailure(PaymentFailureType.PROVIDER_UNAVAILABLE)
        } catch (_: StripeOperationException) {
            repository.updateProviderStatus(attempt.id, PaymentStatus.FAILED, clockMillis())
            val failedAttempt = repository.findAttempt(attempt.ownerId, attempt.id) ?: attempt
            return PaymentAttemptApiResponse.from(failedAttempt)
        }
        repository.attachStripeIntent(attempt.id, intent.id, intent.status, clockMillis())
        val saved = repository.findAttempt(attempt.ownerId, attempt.id) ?: attempt
        return PaymentAttemptApiResponse.from(saved, intent.clientSecret.forPaymentSheet(intent.status))
    }

    private fun resumeIdempotentAttempt(
        order: PayableOrder,
        attempt: PaymentAttemptRecord,
    ): PaymentAttemptApiResponse {
        val intentId = attempt.stripeIntentId
        if (intentId == null) return createStripeIntent(order, attempt)
        if (isPaymentTerminal(attempt.status)) return PaymentAttemptApiResponse.from(attempt)

        val intent = retrieveStripeIntent(intentId)
        repository.updateProviderStatus(attempt.id, intent.status, clockMillis())
        val saved = repository.findAttempt(attempt.ownerId, attempt.id) ?: attempt
        return PaymentAttemptApiResponse.from(saved, intent.clientSecret.forPaymentSheet(intent.status))
    }

    private fun retrieveStripeIntent(intentId: String): StripeIntentSnapshot = try {
        stripe.retrievePaymentIntent(intentId)
    } catch (_: com.stripe.exception.ApiConnectionException) {
        throw PaymentServiceFailure(PaymentFailureType.PROVIDER_UNAVAILABLE)
    } catch (_: com.stripe.exception.StripeException) {
        throw PaymentServiceFailure(PaymentFailureType.PROVIDER_UNAVAILABLE)
    }

    private fun String?.forPaymentSheet(status: PaymentStatus): String? =
        takeIf { !it.isNullOrBlank() && status in setOf(PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatus.REQUIRES_ACTION) }

    private fun providerIdempotencyKey(ownerId: String, requestKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ownerId\u0000$requestKey".toByteArray(Charsets.UTF_8))
        return "payflow_${digest.joinToString("") { "%02x".format(it) }}"
    }
}

enum class PaymentFailureType { NOT_FOUND, CONFLICT, PROVIDER_UNAVAILABLE, INVALID_WEBHOOK }

class PaymentServiceFailure(val type: PaymentFailureType) : RuntimeException()

private fun isPaymentTerminal(status: PaymentStatus): Boolean = status in setOf(
    PaymentStatus.SUCCEEDED,
    PaymentStatus.FAILED,
    PaymentStatus.CANCELLED,
)
