package com.payflow.backend.server

import com.auth0.jwk.JwkProviderBuilder
import com.payflow.backend.application.PaymentService
import com.payflow.backend.application.PaymentServiceFailure
import com.payflow.backend.config.BackendConfig
import com.payflow.backend.config.BackendConfig.AuthMode
import com.payflow.backend.api.ApiErrorResponse
import com.payflow.backend.api.CreatePaymentAttemptBody
import com.payflow.backend.api.DemoOrderApiResponse
import com.payflow.backend.application.PaymentFailureType.CONFLICT
import com.payflow.backend.application.PaymentFailureType.INVALID_WEBHOOK
import com.payflow.backend.application.PaymentFailureType.NOT_FOUND
import com.payflow.backend.application.PaymentFailureType.PROVIDER_UNAVAILABLE
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

fun Application.payFlowModule(config: BackendConfig, paymentService: PaymentService) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        })
    }
    install(Authentication) {
        when (config.authMode) {
            AuthMode.LOCAL_BEARER -> {
                val expectedToken = checkNotNull(config.devBearerToken)
                bearer("payflow-auth") {
                    realm = "PayFlow local development"
                    authenticate { credential ->
                        if (constantTimeEquals(expectedToken, credential.token)) {
                            UserIdPrincipal("local-demo-user")
                        } else {
                            null
                        }
                    }
                }
            }

            AuthMode.OIDC_JWT -> {
                val issuer = checkNotNull(config.oidcIssuer)
                val audience = checkNotNull(config.oidcAudience)
                val jwkProvider = JwkProviderBuilder(URI(checkNotNull(config.jwksUrl)).toURL())
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build()
                jwt("payflow-auth") {
                    realm = "PayFlow"
                    verifier(jwkProvider, issuer) { acceptLeeway(30) }
                    validate { credential ->
                        val subject = credential.payload.subject
                        val expiration = credential.payload.expiresAt?.time
                        if (!subject.isNullOrBlank() && audience in credential.payload.audience &&
                            expiration != null && expiration > System.currentTimeMillis()
                        ) {
                            JWTPrincipal(credential.payload)
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }
    install(StatusPages) {
        exception<PaymentServiceFailure> { call, failure ->
            val (status, code, message) = failure.toApiError()
            call.respond(status, ApiErrorResponse(code, message))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_request", "Request body is invalid."))
        }
        exception<Throwable> { call, failure ->
            // Deliberately omit exception messages and request bodies from logs.
            this@payFlowModule.environment.log.error("Unhandled PayFlow request failure: ${failure::class.simpleName}")
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse("internal_error", "Request could not be completed."))
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        post("/v1/webhooks/stripe") {
            val declaredSize = call.request.header("Content-Length")?.toLongOrNull()
            if (declaredSize != null && declaredSize > MAX_WEBHOOK_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("webhook_too_large", "Webhook payload exceeds the limit."))
                return@post
            }
            val signature = call.request.header("Stripe-Signature")
            if (signature.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_webhook", "Webhook signature is required."))
                return@post
            }
            val rawPayload = call.receiveText()
            paymentService.processWebhook(rawPayload, signature)
            call.respond(mapOf("received" to true))
        }

        authenticate("payflow-auth") {
            route("/v1/payment-attempts") {
                get {
                    val principal = call.paymentOwnerId()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                    if (limit !in 1..100 || offset !in 0..100_000) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_pagination", "Pagination values are invalid."))
                        return@get
                    }
                    call.respond(paymentService.listAttempts(principal, limit, offset))
                }

                get("/active") {
                    val principal = call.paymentOwnerId()
                    val orderId = call.request.queryParameters["orderId"]
                    if (orderId.isNullOrBlank() || !ORDER_ID.matches(orderId)) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_order_id", "Order ID is invalid."))
                        return@get
                    }
                    call.respond(paymentService.getActiveAttempt(principal, orderId))
                }

                get("/latest") {
                    val principal = call.paymentOwnerId()
                    val orderId = call.request.queryParameters["orderId"]
                    if (orderId.isNullOrBlank() || !ORDER_ID.matches(orderId)) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_order_id", "Order ID is invalid."))
                        return@get
                    }
                    call.respond(paymentService.getLatestAttemptForOrder(principal, orderId))
                }

                post {
                    val principal = call.paymentOwnerId()
                    val idempotencyKey = call.request.header("Idempotency-Key")
                    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matches(idempotencyKey)) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiErrorResponse("invalid_idempotency_key", "A valid Idempotency-Key header is required."),
                        )
                        return@post
                    }
                    val body = call.receive<CreatePaymentAttemptBody>()
                    if (!ORDER_ID.matches(body.orderId)) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_order_id", "Order ID is invalid."))
                        return@post
                    }
                    call.respond(paymentService.createAttempt(principal, body.orderId, idempotencyKey))
                }

                get("/{attemptId}") {
                    val principal = call.paymentOwnerId()
                    val attemptId = call.parameters["attemptId"]
                    if (attemptId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("invalid_attempt_id", "Attempt ID is invalid."))
                        return@get
                    }
                    call.respond(paymentService.getAttempt(principal, attemptId))
                }
            }
        }

        // Demo orders use a fixed server-owned amount and are never exposed outside local mode.
        if (config.environment == "local") {
            authenticate("payflow-auth") {
                post("/v1/demo-orders") {
                    val order = paymentService.createLocalDemoOrder(call.paymentOwnerId())
                    call.respond(HttpStatusCode.Created, DemoOrderApiResponse.from(order))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.paymentOwnerId(): String =
    principal<JWTPrincipal>()?.payload?.subject
        ?: principal<UserIdPrincipal>()?.name
        ?: error("Authenticated principal is missing")

private fun constantTimeEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

private fun PaymentServiceFailure.toApiError(): Triple<HttpStatusCode, String, String> = when (type) {
    NOT_FOUND -> Triple(HttpStatusCode.NotFound, "payment_not_found", "Payment or order was not found.")
    CONFLICT -> Triple(HttpStatusCode.Conflict, "payment_conflict", "The order cannot start another payment attempt yet.")
    PROVIDER_UNAVAILABLE -> Triple(HttpStatusCode.ServiceUnavailable, "payment_provider_unavailable", "Payment service is temporarily unavailable.")
    INVALID_WEBHOOK -> Triple(HttpStatusCode.BadRequest, "invalid_webhook", "Webhook could not be verified.")
}

private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,255}$")
private val ORDER_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")
private const val MAX_WEBHOOK_BYTES = 1_048_576L
