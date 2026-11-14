package com.payflow.backend

import com.payflow.backend.config.BackendConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class BackendConfigTest {
    @Test
    fun localModeAcceptsOnlyTestKeysAndRequiresHighEntropyBearerToken() {
        val config = BackendConfig.fromEnvironment(localEnvironment())

        assertEquals(BackendConfig.AuthMode.LOCAL_BEARER, config.authMode)
        assertEquals("local", config.environment)
        assertFailsWith<IllegalArgumentException> {
            BackendConfig.fromEnvironment(localEnvironment() + ("PAYFLOW_DEV_BEARER_TOKEN" to "too-short"))
        }
        assertFailsWith<IllegalArgumentException> {
            BackendConfig.fromEnvironment(localEnvironment() + ("STRIPE_SECRET_KEY" to "sk_live_fake"))
        }
    }

    @Test
    fun productionRejectsLocalBearerAuthenticationAndRequiresVerifiedDatabaseTls() {
        val production = localEnvironment() + mapOf(
            "PAYFLOW_ENV" to "production",
            "PAYFLOW_AUTH_MODE" to "oidc",
            "PAYFLOW_OIDC_ISSUER" to "https://identity.example/",
            "PAYFLOW_OIDC_AUDIENCE" to "payflow-api",
            "PAYFLOW_OIDC_JWKS_URL" to "https://identity.example/.well-known/jwks.json",
            "PAYFLOW_DATABASE_URL" to "jdbc:postgresql://db.example/payflow?sslmode=verify-full",
            "STRIPE_SECRET_KEY" to "sk_live_fake",
        ) - "PAYFLOW_DEV_BEARER_TOKEN"

        assertEquals(BackendConfig.AuthMode.OIDC_JWT, BackendConfig.fromEnvironment(production).authMode)
        assertFailsWith<IllegalArgumentException> {
            BackendConfig.fromEnvironment(production + ("PAYFLOW_DATABASE_URL" to "jdbc:postgresql://db.example/payflow"))
        }
    }

    private fun localEnvironment() = mapOf(
        "PAYFLOW_ENV" to "local",
        "PAYFLOW_AUTH_MODE" to "local",
        "PAYFLOW_DEV_BEARER_TOKEN" to "a-local-test-token-with-at-least-32-characters",
        "PAYFLOW_DATABASE_URL" to "jdbc:postgresql://localhost:5432/payflow",
        "PAYFLOW_DATABASE_USER" to "payflow",
        "PAYFLOW_DATABASE_PASSWORD" to "local-only",
        "STRIPE_SECRET_KEY" to "sk_test_fake",
        "STRIPE_WEBHOOK_SECRET" to "whsec_fake",
    )
}
