package com.payflow.backend.data

import com.payflow.backend.domain.OrderStatus
import com.payflow.backend.domain.PayableOrder
import com.payflow.backend.domain.PaymentAttemptRecord
import com.payflow.shared.PaymentLifecycle
import com.payflow.shared.PaymentStatus
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

sealed interface ReserveAttemptResult {
    data class Reserved(val order: PayableOrder, val attempt: PaymentAttemptRecord) : ReserveAttemptResult
    data class Existing(val order: PayableOrder, val attempt: PaymentAttemptRecord) : ReserveAttemptResult
    data object OrderNotFound : ReserveAttemptResult
    data object OrderNotPayable : ReserveAttemptResult
    data object ActiveAttemptExists : ReserveAttemptResult
    data object IdempotencyConflict : ReserveAttemptResult
}

class PaymentAttemptRepository(private val dataSource: HikariDataSource) {
    fun reserveAttempt(
        ownerId: String,
        orderId: String,
        idempotencyKey: String,
        attemptId: String,
        nowEpochMillis: Long,
    ): ReserveAttemptResult {
        var uniqueViolation = false
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                findByIdempotency(connection, ownerId, idempotencyKey)?.let { existing ->
                    connection.commit()
                    if (existing.orderId != orderId) return ReserveAttemptResult.IdempotencyConflict
                    val existingOrder = findOrder(connection, ownerId, orderId)
                        ?: return ReserveAttemptResult.OrderNotFound
                    return ReserveAttemptResult.Existing(existingOrder, existing)
                }

                val order = findOrderForUpdate(connection, ownerId, orderId)
                    ?: run {
                        connection.rollback()
                        return ReserveAttemptResult.OrderNotFound
                    }
                if (order.status != OrderStatus.PAYABLE) {
                    connection.rollback()
                    return ReserveAttemptResult.OrderNotPayable
                }

                if (hasActiveAttempt(connection, ownerId, orderId)) {
                    connection.rollback()
                    return ReserveAttemptResult.ActiveAttemptExists
                }

                val attempt = PaymentAttemptRecord(
                    id = attemptId,
                    ownerId = ownerId,
                    orderId = orderId,
                    idempotencyKey = idempotencyKey,
                    stripeIntentId = null,
                    amountMinor = order.amountMinor,
                    currency = order.currency,
                    provider = "stripe",
                    status = PaymentStatus.INITIATING,
                    createdAtEpochMillis = nowEpochMillis,
                    updatedAtEpochMillis = nowEpochMillis,
                )
                connection.prepareStatement(
                    """INSERT INTO payment_attempts
                        (id, owner_id, order_id, idempotency_key, amount_minor, currency, provider, status,
                         created_at_epoch_ms, updated_at_epoch_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                ).use { statement ->
                    statement.setObject(1, java.util.UUID.fromString(attempt.id))
                    statement.setString(2, attempt.ownerId)
                    statement.setString(3, attempt.orderId)
                    statement.setString(4, attempt.idempotencyKey)
                    statement.setLong(5, attempt.amountMinor)
                    statement.setString(6, attempt.currency)
                    statement.setString(7, attempt.provider)
                    statement.setString(8, attempt.status.name)
                    statement.setLong(9, attempt.createdAtEpochMillis)
                    statement.setLong(10, attempt.updatedAtEpochMillis)
                    statement.executeUpdate()
                }
                connection.commit()
                return ReserveAttemptResult.Reserved(order, attempt)
            } catch (failure: SQLException) {
                connection.rollback()
                if (failure.sqlState != "23505") throw failure
                uniqueViolation = true
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }

        if (uniqueViolation) {
            val existing = findByIdempotency(ownerId, idempotencyKey)
            if (existing != null && existing.orderId != orderId) return ReserveAttemptResult.IdempotencyConflict
            if (existing != null) {
                val existingOrder = findOrder(ownerId, orderId) ?: return ReserveAttemptResult.OrderNotFound
                return ReserveAttemptResult.Existing(existingOrder, existing)
            }
            return ReserveAttemptResult.ActiveAttemptExists
        }
        error("Unreachable reservation state")
    }

    fun findByIdempotency(ownerId: String, idempotencyKey: String): PaymentAttemptRecord? =
        dataSource.connection.use { findByIdempotency(it, ownerId, idempotencyKey) }

    fun findActiveAttempt(ownerId: String, orderId: String): PaymentAttemptRecord? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT * FROM payment_attempts
                   WHERE owner_id = ? AND order_id = ?
                     AND status IN ('INITIATING', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING', 'TIMED_OUT', 'UNKNOWN')
                   ORDER BY created_at_epoch_ms DESC LIMIT 1""".trimIndent(),
            ).use { statement ->
                statement.setString(1, ownerId)
                statement.setString(2, orderId)
                statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
            }
        }

    fun findLatestAttemptForOrder(ownerId: String, orderId: String): PaymentAttemptRecord? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT * FROM payment_attempts
                   WHERE owner_id = ? AND order_id = ?
                   ORDER BY created_at_epoch_ms DESC, id DESC LIMIT 1""".trimIndent(),
            ).use { statement ->
                statement.setString(1, ownerId)
                statement.setString(2, orderId)
                statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
            }
        }

    fun findAttempt(ownerId: String, attemptId: String): PaymentAttemptRecord? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM payment_attempts WHERE owner_id = ? AND id = ?").use { statement ->
                statement.setString(1, ownerId)
                statement.setObject(2, java.util.UUID.fromString(attemptId))
                statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
            }
        }

    /** Returns only this owner's transactions, newest first. The server caps page size. */
    fun listAttempts(ownerId: String, limit: Int, offset: Int): List<PaymentAttemptRecord> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT * FROM payment_attempts WHERE owner_id = ?
                   ORDER BY created_at_epoch_ms DESC, id DESC LIMIT ? OFFSET ?""".trimIndent(),
            ).use { statement ->
                statement.setString(1, ownerId)
                statement.setInt(2, limit)
                statement.setInt(3, offset)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toAttempt()) }
                }
            }
        }

    fun findByStripeIntent(stripeIntentId: String): PaymentAttemptRecord? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM payment_attempts WHERE stripe_intent_id = ?").use { statement ->
                statement.setString(1, stripeIntentId)
                statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
            }
        }

    fun findOrder(ownerId: String, orderId: String): PayableOrder? =
        dataSource.connection.use { findOrder(it, ownerId, orderId) }

    fun createLocalDemoOrder(ownerId: String, orderId: String, nowEpochMillis: Long): PayableOrder =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO orders (id, owner_id, amount_minor, currency, status, created_at_epoch_ms)
                   VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
            ).use { statement ->
                statement.setString(1, orderId)
                statement.setString(2, ownerId)
                statement.setLong(3, DEMO_ORDER_AMOUNT_MINOR)
                statement.setString(4, DEMO_ORDER_CURRENCY)
                statement.setString(5, OrderStatus.PAYABLE.name)
                statement.setLong(6, nowEpochMillis)
                statement.executeUpdate()
            }
            PayableOrder(
                id = orderId,
                ownerId = ownerId,
                amountMinor = DEMO_ORDER_AMOUNT_MINOR,
                currency = DEMO_ORDER_CURRENCY,
                status = OrderStatus.PAYABLE,
            )
        }

    private fun findOrder(connection: Connection, ownerId: String, orderId: String): PayableOrder? =
        connection.prepareStatement("SELECT * FROM orders WHERE id = ? AND owner_id = ?").use { statement ->
            statement.setString(1, orderId)
            statement.setString(2, ownerId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else PayableOrder(
                    id = result.getString("id"),
                    ownerId = result.getString("owner_id"),
                    amountMinor = result.getLong("amount_minor"),
                    currency = result.getString("currency").trim(),
                    status = OrderStatus.valueOf(result.getString("status")),
                )
            }
        }

    fun attachStripeIntent(attemptId: String, stripeIntentId: String, status: PaymentStatus, now: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE payment_attempts SET stripe_intent_id = ?, status = ?, updated_at_epoch_ms = ?
                   WHERE id = ? AND stripe_intent_id IS NULL""".trimIndent(),
            ).use { statement ->
                statement.setString(1, stripeIntentId)
                statement.setString(2, status.name)
                statement.setLong(3, now)
                statement.setObject(4, java.util.UUID.fromString(attemptId))
                statement.executeUpdate()
            }
        }
    }

    fun updateProviderStatus(attemptId: String, status: PaymentStatus, now: Long) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val current = connection.prepareStatement("SELECT * FROM payment_attempts WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setObject(1, java.util.UUID.fromString(attemptId))
                    statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
                } ?: run {
                    connection.commit()
                    return@use
                }
                val transition = PaymentLifecycle.transition(current.toDomainAttempt(), status, now)
                if (transition is com.payflow.shared.PaymentTransitionResult.Applied) {
                    connection.prepareStatement(
                        "UPDATE payment_attempts SET status = ?, updated_at_epoch_ms = ? WHERE id = ?",
                    ).use { statement ->
                        statement.setString(1, status.name)
                        statement.setLong(2, now)
                        statement.setObject(3, java.util.UUID.fromString(attemptId))
                        statement.executeUpdate()
                    }
                    if (status == PaymentStatus.SUCCEEDED) {
                        connection.prepareStatement("UPDATE orders SET status = 'PAID' WHERE id = ? AND owner_id = ?").use { statement ->
                            statement.setString(1, current.orderId)
                            statement.setString(2, current.ownerId)
                            statement.executeUpdate()
                        }
                    }
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    fun applyWebhookEvent(
        eventId: String,
        eventCreatedAtEpochSeconds: Long,
        stripeIntentId: String,
        status: PaymentStatus,
        nowEpochMillis: Long,
    ): Boolean = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val inserted = connection.prepareStatement(
                "INSERT INTO processed_stripe_events(event_id, processed_at_epoch_ms) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setString(1, eventId)
                statement.setLong(2, nowEpochMillis)
                statement.executeUpdate() == 1
            }
            if (!inserted) {
                connection.commit()
                return@use false
            }

            val current = findByStripeIntent(connection, stripeIntentId, forUpdate = true)
            if (current != null && eventCreatedAtEpochSeconds >= current.lastStripeEventCreatedAtEpochSeconds &&
                PaymentLifecycle.canTransition(current.status, status)
            ) {
                connection.prepareStatement(
                    """UPDATE payment_attempts SET status = ?, updated_at_epoch_ms = ?, last_stripe_event_created_at = ?
                       WHERE id = ?""".trimIndent(),
                ).use { statement ->
                    statement.setString(1, status.name)
                    statement.setLong(2, nowEpochMillis)
                    statement.setLong(3, eventCreatedAtEpochSeconds)
                    statement.setObject(4, java.util.UUID.fromString(current.id))
                    statement.executeUpdate()
                }
                if (status == PaymentStatus.SUCCEEDED) {
                    connection.prepareStatement("UPDATE orders SET status = 'PAID' WHERE id = ? AND owner_id = ?").use { statement ->
                        statement.setString(1, current.orderId)
                        statement.setString(2, current.ownerId)
                        statement.executeUpdate()
                    }
                }
            }
            connection.commit()
            true
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        }
    }

    private fun findByIdempotency(
        connection: Connection,
        ownerId: String,
        idempotencyKey: String,
    ): PaymentAttemptRecord? = connection.prepareStatement(
        "SELECT * FROM payment_attempts WHERE owner_id = ? AND idempotency_key = ? FOR UPDATE",
    ).use { statement ->
        statement.setString(1, ownerId)
        statement.setString(2, idempotencyKey)
        statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
    }

    private fun findOrderForUpdate(connection: Connection, ownerId: String, orderId: String): PayableOrder? =
        connection.prepareStatement("SELECT * FROM orders WHERE id = ? AND owner_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, orderId)
            statement.setString(2, ownerId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else PayableOrder(
                    id = result.getString("id"),
                    ownerId = result.getString("owner_id"),
                    amountMinor = result.getLong("amount_minor"),
                    currency = result.getString("currency").trim(),
                    status = OrderStatus.valueOf(result.getString("status")),
                )
            }
        }

    private fun hasActiveAttempt(connection: Connection, ownerId: String, orderId: String): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM payment_attempts WHERE owner_id = ? AND order_id = ?
               AND status IN ('INITIATING', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING', 'TIMED_OUT', 'UNKNOWN')
               FOR UPDATE""".trimIndent(),
        ).use { statement ->
            statement.setString(1, ownerId)
            statement.setString(2, orderId)
            statement.executeQuery().use { it.next() }
        }

    private fun findByStripeIntent(
        connection: Connection,
        stripeIntentId: String,
        forUpdate: Boolean = false,
    ): PaymentAttemptRecord? = connection.prepareStatement(
        "SELECT * FROM payment_attempts WHERE stripe_intent_id = ?" + if (forUpdate) " FOR UPDATE" else "",
    ).use { statement ->
        statement.setString(1, stripeIntentId)
        statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
    }

    private fun ResultSet.toAttempt() = PaymentAttemptRecord(
        id = getObject("id").toString(),
        ownerId = getString("owner_id"),
        orderId = getString("order_id"),
        idempotencyKey = getString("idempotency_key"),
        stripeIntentId = getString("stripe_intent_id"),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency").trim(),
        provider = getString("provider"),
        status = PaymentStatus.valueOf(getString("status")),
        createdAtEpochMillis = getLong("created_at_epoch_ms"),
        updatedAtEpochMillis = getLong("updated_at_epoch_ms"),
        lastStripeEventCreatedAtEpochSeconds = getLong("last_stripe_event_created_at"),
    )

    private fun PaymentAttemptRecord.toDomainAttempt() = com.payflow.shared.PaymentAttempt(
        id = id,
        orderId = orderId,
        amountMinor = amountMinor,
        currency = currency,
        provider = provider,
        status = status,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private const val DEMO_ORDER_AMOUNT_MINOR = 2_499L
private const val DEMO_ORDER_CURRENCY = "USD"
