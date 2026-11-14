package com.payflow.android.data.auth

import com.payflow.android.BuildConfig
import javax.inject.Inject

/** Supplies an already-authenticated access token without persisting it in this layer. */
interface AccessTokenProvider {
    fun currentAccessToken(): String?
}

/** Memory-only placeholder for the app's future authentication/session implementation. */
class InMemoryAccessTokenProvider @Inject constructor() : AccessTokenProvider {
    @Volatile
    private var accessToken: String? = BuildConfig.PAYFLOW_DEV_BEARER_TOKEN.takeIf(String::isNotBlank)

    override fun currentAccessToken(): String? = accessToken

    fun update(token: String) {
        require(token.isNotBlank()) { "token must not be blank" }
        accessToken = token
    }

    fun clear() {
        accessToken = null
    }
}
