package com.payflow.backend.config

data class BackendConfig(
    val environment: String,
    val authMode: AuthMode,
    val devBearerToken: String?,
    val oidcIssuer: String?,
    val oidcAudience: String?,
    val jwksUrl: String?,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val stripeSecretKey: String,
    val stripeWebhookSecret: String,
    val host: String,
    val port: Int,
) {
    enum class AuthMode { LOCAL_BEARER, OIDC_JWT }

    companion object {
        fun fromEnvironment(env: Map<String, String>): BackendConfig {
            fun required(name: String): String = env[name]?.takeIf(String::isNotBlank)
                ?: error("Required environment variable $name is missing")

            val environment = env["PAYFLOW_ENV"] ?: "production"
            require(environment in setOf("local", "staging", "production")) {
                "PAYFLOW_ENV must be local, staging, or production"
            }
            val authMode = when (env["PAYFLOW_AUTH_MODE"] ?: "oidc") {
                "local" -> AuthMode.LOCAL_BEARER
                "oidc" -> AuthMode.OIDC_JWT
                else -> error("PAYFLOW_AUTH_MODE must be 'local' or 'oidc'")
            }
            val devToken = env["PAYFLOW_DEV_BEARER_TOKEN"]
            val issuer = env["PAYFLOW_OIDC_ISSUER"]
            val audience = env["PAYFLOW_OIDC_AUDIENCE"]
            val jwksUrl = env["PAYFLOW_OIDC_JWKS_URL"]

            when (authMode) {
                AuthMode.LOCAL_BEARER -> {
                    require(environment == "local") { "Local bearer authentication is only allowed in PAYFLOW_ENV=local" }
                    require(devToken != null && devToken.length >= 32) {
                        "PAYFLOW_DEV_BEARER_TOKEN must contain at least 32 characters"
                    }
                }

                AuthMode.OIDC_JWT -> {
                    require(!issuer.isNullOrBlank() && !audience.isNullOrBlank() && !jwksUrl.isNullOrBlank()) {
                        "OIDC issuer, audience, and JWKS URL are required for OIDC authentication"
                    }
                    require(issuer.startsWith("https://") && jwksUrl.startsWith("https://")) {
                        "OIDC issuer and JWKS URL must use HTTPS"
                    }
                }
            }

            val secretKey = required("STRIPE_SECRET_KEY")
            require(secretKey.startsWith("sk_test_") || secretKey.startsWith("sk_live_")) {
                "STRIPE_SECRET_KEY must be a Stripe secret key"
            }
            require(if (environment == "production") secretKey.startsWith("sk_live_") else secretKey.startsWith("sk_test_")) {
                "Use test-mode Stripe keys outside production and live-mode keys only in production"
            }
            val webhookSecret = required("STRIPE_WEBHOOK_SECRET")
            require(webhookSecret.startsWith("whsec_")) { "STRIPE_WEBHOOK_SECRET must be a Stripe webhook signing secret" }
            val databaseUrl = required("PAYFLOW_DATABASE_URL")
            require(databaseUrl.startsWith("jdbc:postgresql://")) { "PAYFLOW_DATABASE_URL must use PostgreSQL JDBC" }
            if (environment != "local") {
                require(databaseUrl.contains("sslmode=verify-full")) {
                    "Non-local PostgreSQL connections must use sslmode=verify-full"
                }
            }

            return BackendConfig(
                environment = environment,
                authMode = authMode,
                devBearerToken = devToken,
                oidcIssuer = issuer,
                oidcAudience = audience,
                jwksUrl = jwksUrl,
                databaseUrl = databaseUrl,
                databaseUser = required("PAYFLOW_DATABASE_USER"),
                databasePassword = required("PAYFLOW_DATABASE_PASSWORD"),
                stripeSecretKey = secretKey,
                stripeWebhookSecret = webhookSecret,
                host = env["PAYFLOW_HOST"] ?: "0.0.0.0",
                port = (env["PAYFLOW_PORT"] ?: "8080").toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: error("PAYFLOW_PORT must be between 1 and 65535"),
            )
        }
    }
}
