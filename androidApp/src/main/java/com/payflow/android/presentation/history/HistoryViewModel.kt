package com.payflow.android.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payflow.android.data.history.TransactionHistoryRepository
import com.payflow.shared.PaymentAttempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val isLoading: Boolean = false,
    val transactions: List<PaymentAttempt> = emptyList(),
    val hasLoaded: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: TransactionHistoryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state = mutableState.asStateFlow()

    fun load(force: Boolean = false) {
        if (mutableState.value.isLoading || (mutableState.value.hasLoaded && !force)) return
        mutableState.value = mutableState.value.copy(isLoading = true, message = null)
        viewModelScope.launch {
            try {
                mutableState.value = HistoryUiState(
                    transactions = repository.getRecentTransactions(),
                    hasLoaded = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    hasLoaded = true,
                    message = "Transaction history couldn't load. Check your connection and try again.",
                )
            }
        }
    }
}
