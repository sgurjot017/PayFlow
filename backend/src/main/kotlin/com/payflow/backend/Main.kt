package com.payflow.backend

import com.payflow.backend.application.PaymentService
import com.payflow.backend.application.StripeSdkGateway
import com.payflow.backend.config.BackendConfig
import com.payflow.backend.data.DatabaseFactory
import com.payflow.backend.data.PaymentAttemptRepository
import com.payflow.backend.server.payFlowModule
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = BackendConfig.fromEnvironment(System.getenv())
    val dataSource = DatabaseFactory.create(config)
    val paymentRepository = PaymentAttemptRepository(dataSource)
    val stripeGateway = StripeSdkGateway(config.stripeSecretKey, config.stripeWebhookSecret)
    val paymentService = PaymentService(paymentRepository, stripeGateway)

    Runtime.getRuntime().addShutdownHook(Thread { dataSource.close() })
    embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
    ) {
        payFlowModule(config, paymentService)
    }.start(wait = true)
}
