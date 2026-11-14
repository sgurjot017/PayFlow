package com.payflow.android.data.history

import com.payflow.android.data.remote.PaymentBackendApi
import com.payflow.shared.PaymentAttempt
import javax.inject.Inject

interface TransactionHistoryRepository {
    suspend fun getRecentTransactions(): List<PaymentAttempt>
}

class RemoteTransactionHistoryRepository @Inject constructor(
    private val api: PaymentBackendApi,
) : TransactionHistoryRepository {
    override suspend fun getRecentTransactions(): List<PaymentAttempt> =
        api.listPaymentAttempts(limit = HISTORY_PAGE_SIZE, offset = 0).map { it.toDomain().attempt }
}

private const val HISTORY_PAGE_SIZE = 50
