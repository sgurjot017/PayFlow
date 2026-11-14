package com.payflow.shared

/** A platform-neutral record for one attempt to pay an order. */
data class PaymentAttempt(
    val id: String,
    val orderId: String,
    val amountMinor: Long,
    val currency: String,
    val provider: String,
    val status: PaymentStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        require(amountMinor >= 0) { "amountMinor must not be negative" }
        require(currency.length == 3 && currency.all { it in 'A'..'Z' }) {
            "currency must be a three-letter uppercase ISO code"
        }
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "updatedAtEpochMillis must not precede createdAtEpochMillis"
        }
    }
}
