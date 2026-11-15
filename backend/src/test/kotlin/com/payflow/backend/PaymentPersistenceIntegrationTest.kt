package com.payflow.backend

import com.payflow.backend.application.PaymentFailureType
import com.payflow.backend.application.PaymentService
import com.payflow.backend.application.PaymentServiceFailure
import com.payflow.backend.application.StripeGateway
import com.payflow.backend.data.PaymentAttemptRepository
import com.payflow.backend.data.ReserveAttemptResult
import com.payflow.backend.domain.PayableOrder
import com.payflow.backend.domain.PaymentAttemptRecord
import com.payflow.backend.domain.StripeEventSnapshot
import com.payflow.backend.domain.StripeIntentSnapshot
import com.payflow.shared.PaymentStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import com.stripe.exception.ApiConnectionException

/** PostgreSQL integration coverage. Set PAYFLOW_TEST_DATABASE_URL to run locally. */
class PaymentPersistenceIntegrationTest {
    private val repository by lazy { PaymentAttemptRepository(dataSource) }
    private val orderIds = mutableListOf<String>()
    private val eventIds = mutableListOf<String>()

    @Test
    fun concurrentRequestsWithSameIdempotencyKeyReuseOneAttemptAndProviderKey() = runBlocking {
        val order = createOrder("owner-idempotency")
        val started = CountDownLatch(1)
        val bothProviderCalls = CountDownLatch(2)
        val releaseProvider = CountDownLatch(1)
        val providerKeys = Collections.synchronizedSet(mutableSetOf<String>())
        val callCount = AtomicInteger()
        val gateway = gateway { _, _, providerKey ->
            providerKeys += providerKey
            callCount.incrementAndGet()
            started.countDown()
            bothProviderCalls.countDown()
            check(bothProviderCalls.await(10, TimeUnit.SECONDS))
            releaseProvider.countDown()
            check(releaseProvider.await(10, TimeUnit.SECONDS))
            StripeIntentSnapshot("pi-${order.id}", "pi-secret", PaymentStatus.REQUIRES_PAYMENT_METHOD)
        }
        val service = PaymentService(repository, gateway)

        val first = async(Dispatchers.IO) { service.createAttempt(order.ownerId, order.id, "concurrent-key-01") }
        assertTrue("first request should reserve before provider call", started.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { service.createAttempt(order.ownerId, order.id, "concurrent-key-01") }
        val responses = awaitAll(first, second)

        assertEquals(responses[0].id, responses[1].id)
        assertEquals(1, providerKeys.size)
        assertEquals(2, callCount.get())
    }

    @Test
    fun duplicateWebhookEventIsAppliedOnlyOnce() {
        val order = createOrder("owner-webhook-duplicate")
        val attempt = reserve(order, "duplicate-event-key")
        val intentId = "pi-${order.id}"
        repository.attachStripeIntent(attempt.id, intentId, PaymentStatus.REQUIRES_PAYMENT_METHOD, now = 10)
        val eventId = "evt-${UUID.randomUUID()}"
        eventIds += eventId

        assertTrue(repository.applyWebhookEvent(eventId, 100, intentId, PaymentStatus.SUCCEEDED, 101_000))
        assertFalse(repository.applyWebhookEvent(eventId, 100, intentId, PaymentStatus.SUCCEEDED, 102_000))
        assertEquals(PaymentStatus.SUCCEEDED, repository.findByStripeIntent(intentId)?.status)
        assertEquals(com.payflow.backend.domain.OrderStatus.PAID, repository.findOrder(order.ownerId, order.id)?.status)
    }

    @Test
    fun olderWebhookDoesNotRollBackNewerPaymentStatus() {
        val order = createOrder("owner-webhook-ordering")
        val attempt = reserve(order, "ordered-event-key")
        val intentId = "pi-${order.id}"
        repository.attachStripeIntent(attempt.id, intentId, PaymentStatus.REQUIRES_PAYMENT_METHOD, now = 10)
        val successEventId = "evt-${UUID.randomUUID()}"
        val oldEventId = "evt-${UUID.randomUUID()}"
        eventIds += listOf(successEventId, oldEventId)

        repository.applyWebhookEvent(successEventId, 200, intentId, PaymentStatus.SUCCEEDED, 201_000)
        repository.applyWebhookEvent(oldEventId, 199, intentId, PaymentStatus.PROCESSING, 202_000)

        assertEquals(PaymentStatus.SUCCEEDED, repository.findByStripeIntent(intentId)?.status)
        assertEquals(com.payflow.backend.domain.OrderStatus.PAID, repository.findOrder(order.ownerId, order.id)?.status)
    }

    @Test
    fun attemptAndHistoryAreInvisibleToAnotherOwner() {
        val owner = "owner-${UUID.randomUUID()}"
        val otherOwner = "owner-${UUID.randomUUID()}"
        val order = createOrder(owner)
        val attempt = reserve(order, "owner-scope-key")

        assertNull(repository.findAttempt(otherOwner, attempt.id))
        assertTrue(repository.listAttempts(otherOwner, limit = 20, offset = 0).isEmpty())
    }

    @Test
    fun providerConnectionFailureLeavesReconcilableAttemptAndPayableOrder() = runBlocking {
        val order = createOrder("owner-provider-failure")
        val unavailableGateway = gateway { _, _, _ -> throw ApiConnectionException("simulated provider outage") }
        val service = PaymentService(repository, unavailableGateway)

        val failure = try {
            service.createAttempt(order.ownerId, order.id, "provider-failure-key")
            error("Expected the provider outage to be surfaced")
        } catch (expected: PaymentServiceFailure) {
            expected
        }

        assertEquals(PaymentFailureType.PROVIDER_UNAVAILABLE, failure.type)
        val attempt = repository.findByIdempotency(order.ownerId, "provider-failure-key")
        assertEquals(PaymentStatus.UNKNOWN, attempt?.status)
        assertNull(attempt?.stripeIntentId)
        assertEquals(com.payflow.backend.domain.OrderStatus.PAYABLE, repository.findOrder(order.ownerId, order.id)?.status)
    }

    private fun createOrder(ownerId: String): PayableOrder {
        val orderId = "it-${UUID.randomUUID()}"
        orderIds += orderId
        return repository.createLocalDemoOrder(ownerId, orderId, System.currentTimeMillis())
    }

    private fun reserve(order: PayableOrder, key: String): PaymentAttemptRecord =
        when (val result = repository.reserveAttempt(
            ownerId = order.ownerId,
            orderId = order.id,
            idempotencyKey = key,
            attemptId = UUID.randomUUID().toString(),
            nowEpochMillis = System.currentTimeMillis(),
        )) {
            is ReserveAttemptResult.Reserved -> result.attempt
            else -> error("Expected an attempt reservation, got $result")
        }

    private fun gateway(
        create: (PayableOrder, PaymentAttemptRecord, String) -> StripeIntentSnapshot,
    ): StripeGateway = object : StripeGateway {
        override fun createPaymentIntent(
            order: PayableOrder,
            attempt: PaymentAttemptRecord,
            providerIdempotencyKey: String,
        ): StripeIntentSnapshot = create(order, attempt, providerIdempotencyKey)

        override fun retrievePaymentIntent(intentId: String) =
            StripeIntentSnapshot(intentId, "pi-secret", PaymentStatus.REQUIRES_PAYMENT_METHOD)

        override fun verifyWebhook(payload: String, signature: String): StripeEventSnapshot? = null
    }

    @After
    fun removeFixtureRows() {
        dataSource.connection.use { connection ->
            eventIds.forEach { id ->
                connection.prepareStatement("DELETE FROM processed_stripe_events WHERE event_id = ?").use {
                    it.setString(1, id)
                    it.executeUpdate()
                }
            }
            orderIds.forEach { id ->
                connection.prepareStatement("DELETE FROM payment_attempts WHERE order_id = ?").use {
                    it.setString(1, id)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM orders WHERE id = ?").use {
                    it.setString(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    companion object {
        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeClass
        fun connectToPostgres() {
            val databaseUrl = System.getenv("PAYFLOW_TEST_DATABASE_URL")
            assumeTrue("Set PAYFLOW_TEST_DATABASE_URL to run PostgreSQL integration tests", !databaseUrl.isNullOrBlank())
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = requireNotNull(databaseUrl)
                username = System.getenv("PAYFLOW_TEST_DATABASE_USER") ?: "payflow"
                password = System.getenv("PAYFLOW_TEST_DATABASE_PASSWORD") ?: "payflow"
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 8
                poolName = "payflow-integration-test"
            })
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        }

        @JvmStatic
        @AfterClass
        fun closePostgres() {
            if (::dataSource.isInitialized) dataSource.close()
        }
    }
}
