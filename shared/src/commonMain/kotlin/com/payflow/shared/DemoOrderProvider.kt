package com.payflow.shared

/** Server-created order for repeating checkout flows in the local sandbox app. */
data class DemoOrder(
    val id: String,
    val amountMinor: Long,
    val currency: String,
)

interface DemoOrderProvider {
    suspend fun createDemoOrder(): DemoOrder
}

class CreateDemoOrderUseCase(
    private val provider: DemoOrderProvider,
) {
    suspend operator fun invoke(): DemoOrder = provider.createDemoOrder()
}
