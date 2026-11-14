package com.payflow.android.presentation.checkout

import androidx.lifecycle.SavedStateHandle
import com.payflow.android.analytics.PaymentEventSink
import com.payflow.shared.InitiatePaymentUseCase
import com.payflow.shared.CreateDemoOrderUseCase
import com.payflow.shared.DemoOrder
import com.payflow.shared.DemoOrderProvider
import com.payflow.shared.PaymentAttempt
import com.payflow.shared.PaymentNextAction
import com.payflow.shared.PaymentProvider
import com.payflow.shared.PaymentRequest
import com.payflow.shared.PaymentResult
import com.payflow.shared.PaymentStatus
import com.payflow.shared.RefreshPaymentUseCase
import com.payflow.shared.RestoreLatestPaymentForOrderUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun transportFailureKeepsIdempotencyKeyAndStripeSecretIsOneShotEffect() = runTest(dispatcher) {
        val provider = FakePaymentProvider(failFirstInitiation = true)
        val viewModel = CheckoutViewModel(
            initiatePayment = InitiatePaymentUseCase(provider),
            createDemoOrder = CreateDemoOrderUseCase(FakeDemoOrderProvider()),
            restorePaymentUseCase = RestoreLatestPaymentForOrderUseCase(provider),
            refreshPayment = RefreshPaymentUseCase(provider),
            eventSink = NoOpPaymentEventSink,
            savedStateHandle = SavedStateHandle(),
        )
        val effect = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

        viewModel.startPayment()
        advanceUntilIdle()
        viewModel.startPayment()
        advanceUntilIdle()

        assertEquals(2, provider.idempotencyKeys.size)
        assertEquals(provider.idempotencyKeys.first(), provider.idempotencyKeys.last())
        assertEquals(PaymentStatus.REQUIRES_ACTION, viewModel.state.value.attempt?.status)
        assertTrue(effect.await() is CheckoutEffect.PresentStripePaymentSheet)
    }

    @Test
    fun restoredSucceededOrderDoesNotStartAnotherAttempt() = runTest(dispatcher) {
        val provider = FakePaymentProvider(failFirstInitiation = false).apply {
            restoredResult = PaymentResult(createAttempt(PaymentStatus.SUCCEEDED))
        }
        val viewModel = CheckoutViewModel(
            initiatePayment = InitiatePaymentUseCase(provider),
            createDemoOrder = CreateDemoOrderUseCase(FakeDemoOrderProvider()),
            restorePaymentUseCase = RestoreLatestPaymentForOrderUseCase(provider),
            refreshPayment = RefreshPaymentUseCase(provider),
            eventSink = NoOpPaymentEventSink,
            savedStateHandle = SavedStateHandle(),
        )

        viewModel.restoreLatestPaymentForOrder()
        advanceUntilIdle()
        viewModel.startPayment()
        advanceUntilIdle()

        assertEquals(PaymentStatus.SUCCEEDED, viewModel.state.value.attempt?.status)
        assertTrue(provider.idempotencyKeys.isEmpty())
    }

    @Test
    fun newDemoOrderReplacesPaidOrderAndStartsCheckoutWithNewId() = runTest(dispatcher) {
        val provider = FakePaymentProvider(failFirstInitiation = false).apply {
            restoredResult = PaymentResult(createAttempt(PaymentStatus.SUCCEEDED))
        }
        val demoOrders = FakeDemoOrderProvider(DemoOrder("demo-order-002", 2_499, "USD"))
        val viewModel = CheckoutViewModel(
            initiatePayment = InitiatePaymentUseCase(provider),
            createDemoOrder = CreateDemoOrderUseCase(demoOrders),
            restorePaymentUseCase = RestoreLatestPaymentForOrderUseCase(provider),
            refreshPayment = RefreshPaymentUseCase(provider),
            eventSink = NoOpPaymentEventSink,
            savedStateHandle = SavedStateHandle(),
        )

        viewModel.restoreLatestPaymentForOrder()
        advanceUntilIdle()
        viewModel.createNewDemoOrder()
        advanceUntilIdle()
        viewModel.startPayment()
        advanceUntilIdle()

        assertEquals("demo-order-002", viewModel.state.value.order.id)
        assertEquals("demo-order-002", provider.orderIds.last())
        assertEquals(PaymentStatus.REQUIRES_ACTION, viewModel.state.value.attempt?.status)
    }

    private class FakePaymentProvider(private val failFirstInitiation: Boolean) : PaymentProvider {
        val idempotencyKeys = mutableListOf<String>()
        val orderIds = mutableListOf<String>()
        private var shouldFail = failFirstInitiation
        var restoredResult: PaymentResult? = null

        override suspend fun initiatePayment(request: PaymentRequest): PaymentResult {
            idempotencyKeys += request.idempotencyKey
            orderIds += request.orderId
            if (shouldFail) {
                shouldFail = false
                error("raw provider failure")
            }
            return PaymentResult(
                attempt = createAttempt(),
                nextAction = PaymentNextAction.Authenticate("transient-client-secret"),
            )
        }

        override suspend fun restoreLatestPaymentForOrder(orderId: String): PaymentResult? = restoredResult
        override suspend fun retrievePayment(attemptId: String): PaymentResult = PaymentResult(createAttempt())
    }

    private class FakeDemoOrderProvider(
        private val order: DemoOrder = DemoOrder("demo-order-002", 2_499, "USD"),
    ) : DemoOrderProvider {
        override suspend fun createDemoOrder(): DemoOrder = order
    }

    private object NoOpPaymentEventSink : PaymentEventSink {
        override fun record(event: String, status: String?) = Unit
    }

}

private fun createAttempt(status: PaymentStatus = PaymentStatus.REQUIRES_ACTION) = PaymentAttempt(
    id = "attempt-1",
    orderId = "order-1",
    amountMinor = 2499,
    currency = "USD",
    provider = "stripe",
    status = status,
    createdAtEpochMillis = 1_000,
)
