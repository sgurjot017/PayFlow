package com.payflow.android.di

import com.payflow.android.BuildConfig
import com.payflow.android.data.auth.AccessTokenProvider
import com.payflow.android.data.auth.InMemoryAccessTokenProvider
import com.payflow.android.data.remote.PaymentBackendApi
import com.payflow.android.data.remote.StripePaymentProvider
import com.payflow.android.data.remote.RemoteDemoOrderProvider
import com.payflow.android.data.history.RemoteTransactionHistoryRepository
import com.payflow.android.data.history.TransactionHistoryRepository
import com.payflow.android.analytics.AndroidPaymentEventSink
import com.payflow.android.analytics.PaymentEventSink
import com.payflow.shared.InitiatePaymentUseCase
import com.payflow.shared.CreateDemoOrderUseCase
import com.payflow.shared.DemoOrderProvider
import com.payflow.shared.PaymentProvider
import com.payflow.shared.RefreshPaymentUseCase
import com.payflow.shared.RestoreLatestPaymentForOrderUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.URI
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    @Singleton
    abstract fun bindAccessTokenProvider(provider: InMemoryAccessTokenProvider): AccessTokenProvider

    @Binds
    @Singleton
    abstract fun bindPaymentProvider(provider: StripePaymentProvider): PaymentProvider

    @Binds
    @Singleton
    abstract fun bindDemoOrderProvider(provider: RemoteDemoOrderProvider): DemoOrderProvider

    @Binds
    @Singleton
    abstract fun bindTransactionHistoryRepository(repository: RemoteTransactionHistoryRepository): TransactionHistoryRepository

    @Binds
    @Singleton
    abstract fun bindPaymentEventSink(sink: AndroidPaymentEventSink): PaymentEventSink
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenProvider: AccessTokenProvider): Interceptor = Interceptor { chain ->
        val token = tokenProvider.currentAccessToken()
            ?: throw IOException("An authenticated session is required")
        if (token.isBlank()) throw IOException("An authenticated session is required")
        val requestBuilder = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
        chain.proceed(requestBuilder.build())
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            // Deliberately no body/header logger: payment secrets and auth tokens are sensitive.
            .build()

    @Provides
    @Singleton
    fun providePaymentBackendApi(client: OkHttpClient): PaymentBackendApi {
        val apiUri = URI(BuildConfig.PAYFLOW_API_BASE_URL)
        val secureRemoteUrl = apiUri.scheme == "https"
        val emulatorLocalHttp = BuildConfig.DEBUG && apiUri.scheme == "http" &&
            apiUri.host == "10.0.2.2" && apiUri.port in 1..65535
        require(secureRemoteUrl || emulatorLocalHttp) {
            "API URL must use HTTPS; cleartext HTTP is allowed only from debug builds to the Android emulator host bridge"
        }
        return Retrofit.Builder()
            .baseUrl(BuildConfig.PAYFLOW_API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PaymentBackendApi::class.java)
    }

    @Provides
    fun provideInitiatePaymentUseCase(provider: PaymentProvider) = InitiatePaymentUseCase(provider)

    @Provides
    fun provideCreateDemoOrderUseCase(provider: DemoOrderProvider) = CreateDemoOrderUseCase(provider)

    @Provides
    fun provideRefreshPaymentUseCase(provider: PaymentProvider) = RefreshPaymentUseCase(provider)

    @Provides
    fun provideRestoreLatestPaymentForOrderUseCase(provider: PaymentProvider) = RestoreLatestPaymentForOrderUseCase(provider)
}
