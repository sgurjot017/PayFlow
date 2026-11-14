package com.payflow.android

import android.app.Application
import com.stripe.android.PaymentConfiguration
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PayFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_")) {
            PaymentConfiguration.init(this, BuildConfig.STRIPE_PUBLISHABLE_KEY)
        }
    }
}
