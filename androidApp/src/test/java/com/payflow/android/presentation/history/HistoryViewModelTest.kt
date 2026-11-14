package com.payflow.android.presentation.history

import com.payflow.android.data.history.TransactionHistoryRepository
import com.payflow.shared.PaymentAttempt
import com.payflow.shared.PaymentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
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
    fun loadsTransactionsAndAvoidsRepeatingNonForcedLoads() = runTest(dispatcher) {
        val repository = FakeHistoryRepository(listOf(transaction()))
        val viewModel = HistoryViewModel(repository)

        viewModel.load()
        viewModel.load()
        advanceUntilIdle()

        assertEquals(1, repository.calls)
        assertEquals("attempt-1", viewModel.state.value.transactions.single().id)
        assertTrue(viewModel.state.value.hasLoaded)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun forceLoadRefreshesAndPreservesFriendlyFailureState() = runTest(dispatcher) {
        val repository = FakeHistoryRepository(emptyList(), failOnCall = 2)
        val viewModel = HistoryViewModel(repository)

        viewModel.load()
        advanceUntilIdle()
        viewModel.load(force = true)
        advanceUntilIdle()

        assertEquals(2, repository.calls)
        assertEquals("Transaction history couldn't load. Check your connection and try again.", viewModel.state.value.message)
        assertFalse(viewModel.state.value.isLoading)
    }

    private class FakeHistoryRepository(
        private val transactions: List<PaymentAttempt>,
        private val failOnCall: Int? = null,
    ) : TransactionHistoryRepository {
        var calls = 0

        override suspend fun getRecentTransactions(): List<PaymentAttempt> {
            calls += 1
            if (calls == failOnCall) error("sensitive provider detail must not escape")
            return transactions
        }
    }

    private fun transaction() = PaymentAttempt(
        id = "attempt-1",
        orderId = "order-1",
        amountMinor = 2499,
        currency = "USD",
        provider = "stripe",
        status = PaymentStatus.SUCCEEDED,
        createdAtEpochMillis = 1_000,
    )
}
