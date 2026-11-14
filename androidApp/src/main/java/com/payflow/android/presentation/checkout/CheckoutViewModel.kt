package com.payflow.android.presentation.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.payflow.shared.InitiatePaymentUseCase
import com.payflow.shared.CreateDemoOrderUseCase
import com.payflow.shared.DemoOrder
import com.payflow.shared.PaymentAttempt
import com.payflow.shared.PaymentNextAction
import com.payflow.shared.PaymentStatus
import com.payflow.shared.RefreshPaymentUseCase
import com.payflow.shared.RestoreLatestPaymentForOrderUseCase
import com.payflow.android.analytics.PaymentEventSink
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private const val INITIAL_DEMO_ORDER_ID = "demo-order-001"
private const val INITIAL_DEMO_ORDER_AMOUNT_MINOR = 2_499L
private const val INITIAL_DEMO_ORDER_CURRENCY = "USD"

data class CheckoutUiState(
    val isLoading: Boolean = false,
    val order: DemoOrder = DemoOrder(INITIAL_DEMO_ORDER_ID, INITIAL_DEMO_ORDER_AMOUNT_MINOR, INITIAL_DEMO_ORDER_CURRENCY),
    val attempt: PaymentAttempt? = null,
    val message: String? = null,
)

sealed interface CheckoutEffect {
    /** Contains an ephemeral client secret; consume immediately and never retain outside the SDK call. */
    data class PresentStripePaymentSheet(val clientSecret: String) : CheckoutEffect
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val initiatePayment: InitiatePaymentUseCase,
    private val createDemoOrder: CreateDemoOrderUseCase,
    private val restorePaymentUseCase: RestoreLatestPaymentForOrderUseCase,
    private val refreshPayment: RefreshPaymentUseCase,
    private val eventSink: PaymentEventSink,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CheckoutUiState(order = restoredOrder()))
    val state = mutableState.asStateFlow()

    private val mutableEffects = MutableSharedFlow<CheckoutEffect>(extraBufferCapacity = 1)
    val effects = mutableEffects.asSharedFlow()

    // Retain the key through transport failures so an ambiguous retry cannot create a duplicate.
    private var idempotencyKey: String
        get() = savedStateHandle[IDEMPOTENCY_KEY] ?: UUID.randomUUID().toString().also {
            savedStateHandle[IDEMPOTENCY_KEY] = it
        }
        set(value) {
            savedStateHandle[IDEMPOTENCY_KEY] = value
        }

    fun startPayment() {
        if (mutableState.value.isLoading) return

        val currentOrder = mutableState.value.order
        val previousAttempt = mutableState.value.attempt
        if (previousAttempt?.status == PaymentStatus.SUCCEEDED) return
        if (previousAttempt?.status == PaymentStatus.FAILED ||
            previousAttempt?.status == PaymentStatus.CANCELLED
        ) {
            idempotencyKey = UUID.randomUUID().toString()
        }

        mutableState.value = mutableState.value.copy(isLoading = true, message = null)
        eventSink.record("checkout_started")
        viewModelScope.launch {
            try {
                val isResumableAttempt = previousAttempt?.status in setOf(
                    PaymentStatus.REQUIRES_PAYMENT_METHOD,
                    PaymentStatus.REQUIRES_ACTION,
                )
                val result = if (isResumableAttempt) {
                    restorePaymentUseCase(currentOrder.id)
                        ?: initiatePayment(currentOrder.id, idempotencyKey)
                } else {
                    initiatePayment(currentOrder.id, idempotencyKey)
                }
                mutableState.value = CheckoutUiState(order = currentOrder, attempt = result.attempt)
                eventSink.record("attempt_ready", result.attempt.status.name)
                val action = result.nextAction
                if (action is PaymentNextAction.Authenticate) {
                    mutableEffects.emit(CheckoutEffect.PresentStripePaymentSheet(action.clientSecret))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventSink.record("attempt_start_failed")
                // Do not surface raw provider/network text or expose response details.
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    message = "We couldn't start checkout. Check your sign-in and connection, then try again.",
                )
            }
        }
    }

    fun createNewDemoOrder() {
        if (mutableState.value.isLoading) return
        mutableState.value = mutableState.value.copy(isLoading = true, message = null)
        viewModelScope.launch {
            try {
                val order = createDemoOrder()
                savedStateHandle[CURRENT_ORDER_ID_KEY] = order.id
                savedStateHandle[CURRENT_ORDER_AMOUNT_MINOR_KEY] = order.amountMinor
                savedStateHandle[CURRENT_ORDER_CURRENCY_KEY] = order.currency
                idempotencyKey = UUID.randomUUID().toString()
                mutableState.value = CheckoutUiState(order = order, message = "A new demo order is ready.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    message = "We couldn't create a new demo order. Check your connection and try again.",
                )
            }
        }
    }

    /** Restores the server-authoritative latest attempt, including completed payments, after process recreation. */
    fun restoreLatestPaymentForOrder() {
        if (mutableState.value.isLoading || mutableState.value.attempt != null) return
        val currentOrder = mutableState.value.order
        mutableState.value = mutableState.value.copy(isLoading = true, message = null)
        viewModelScope.launch {
            try {
                val result = restorePaymentUseCase(currentOrder.id)
                mutableState.value = CheckoutUiState(order = currentOrder, attempt = result?.attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = CheckoutUiState(
                    order = currentOrder,
                    message = "We couldn't restore the payment status. Check your connection and try again.",
                )
            }
        }
    }

    fun onStripeSheetClosed() {
        refreshCurrentAttempt()
    }

    fun onStripeSheetFailed() {
        mutableState.value = mutableState.value.copy(
            message = "Payment needs attention. We're checking its status.",
        )
        refreshCurrentAttempt()
    }

    fun checkPaymentStatus() {
        refreshCurrentAttempt()
    }

    private fun refreshCurrentAttempt() {
        val attemptId = mutableState.value.attempt?.id ?: return
        if (mutableState.value.isLoading) return
        val currentOrder = mutableState.value.order

        mutableState.value = mutableState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val result = refreshPayment(attemptId)
                eventSink.record("status_refreshed", result.attempt.status.name)
                mutableState.value = CheckoutUiState(
                    order = currentOrder,
                    attempt = result.attempt,
                    message = if (result.attempt.status == PaymentStatus.SUCCEEDED) {
                        "Payment confirmed."
                    } else {
                        "Status updated from the payment service."
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventSink.record("status_refresh_failed")
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    message = "Payment status is pending. Check again before retrying.",
                )
            }
        }
    }

    private fun restoredOrder() = DemoOrder(
        id = savedStateHandle[CURRENT_ORDER_ID_KEY] ?: INITIAL_DEMO_ORDER_ID,
        amountMinor = savedStateHandle[CURRENT_ORDER_AMOUNT_MINOR_KEY] ?: INITIAL_DEMO_ORDER_AMOUNT_MINOR,
        currency = savedStateHandle[CURRENT_ORDER_CURRENCY_KEY] ?: INITIAL_DEMO_ORDER_CURRENCY,
    )
}

private const val IDEMPOTENCY_KEY = "checkout_idempotency_key"
private const val CURRENT_ORDER_ID_KEY = "checkout_order_id"
private const val CURRENT_ORDER_AMOUNT_MINOR_KEY = "checkout_order_amount_minor"
private const val CURRENT_ORDER_CURRENCY_KEY = "checkout_order_currency"
