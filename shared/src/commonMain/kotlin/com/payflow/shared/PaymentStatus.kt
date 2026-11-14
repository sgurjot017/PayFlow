package com.payflow.shared

enum class PaymentStatus {
    CREATED,
    INITIATING,
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    UNKNOWN,
}
