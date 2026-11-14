package com.payflow.android.data.remote

import com.payflow.shared.DemoOrder
import com.payflow.shared.DemoOrderProvider
import javax.inject.Inject

class RemoteDemoOrderProvider @Inject constructor(
    private val api: PaymentBackendApi,
) : DemoOrderProvider {
    override suspend fun createDemoOrder(): DemoOrder = api.createDemoOrder().toDomain()
}
