package com.payflow.android.analytics

import android.util.Log
import com.payflow.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Safe analytics boundary. Events deliberately exclude user, order, token, amount and provider secret data. */
interface PaymentEventSink {
    fun record(event: String, status: String? = null)
}

@Singleton
class AndroidPaymentEventSink @Inject constructor() : PaymentEventSink {
    override fun record(event: String, status: String?) {
        if (!BuildConfig.DEBUG) return
        val safeEvent = event.takeIf { it in ALLOWED_EVENTS } ?: "unknown_event"
        val safeStatus = status?.takeIf { it in ALLOWED_STATUSES }
        Log.i(EVENT_TAG, buildString {
            append("event=").append(safeEvent)
            if (safeStatus != null) append(" status=").append(safeStatus)
        })
    }

    private companion object {
        const val EVENT_TAG = "PayFlowAnalytics"
        val ALLOWED_EVENTS = setOf("checkout_started", "attempt_ready", "status_refreshed", "status_refresh_failed", "attempt_start_failed")
        val ALLOWED_STATUSES = setOf("INITIATING", "REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN")
    }
}
