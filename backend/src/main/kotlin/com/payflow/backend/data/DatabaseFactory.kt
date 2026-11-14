package com.payflow.backend.data

import com.payflow.backend.config.BackendConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

object DatabaseFactory {
    fun create(config: BackendConfig): HikariDataSource {
        val poolConfig = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 1
            connectionTimeout = 5_000
            validationTimeout = 2_000
            poolName = "payflow-postgres"
            isAutoCommit = true
        }
        val dataSource = HikariDataSource(poolConfig)
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load()
                .migrate()
            if (config.environment == "local") seedLocalDemoOrder(dataSource)
            return dataSource
        } catch (failure: Exception) {
            dataSource.close()
            throw failure
        }
    }

    private fun seedLocalDemoOrder(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO orders(id, owner_id, amount_minor, currency, status, created_at_epoch_ms)
                   VALUES ('demo-order-001', 'local-demo-user', 2499, 'USD', 'PAYABLE', ?)
                   ON CONFLICT (id) DO NOTHING""".trimIndent(),
            ).use { statement ->
                statement.setLong(1, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }
}
