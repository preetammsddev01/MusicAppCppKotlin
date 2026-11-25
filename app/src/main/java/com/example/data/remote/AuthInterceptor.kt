package com.example.data.remote

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = AdminServerConfig.authToken
        val serverHost = AdminServerConfig.serverHost

        if (token.isEmpty() || serverHost == null || !serverHost.equals(original.url.host, ignoreCase = true)) {
            return chain.proceed(original)
        }

        val headerName = AdminServerConfig.authHeaderName
        val builder = original.newBuilder()

        if (headerName.equals("Authorization", ignoreCase = true)) {
            if (original.header("Authorization") == null) {
                val value = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                builder.header("Authorization", value)
            }
        } else {
            if (original.header(headerName) == null) {
                builder.header(headerName, token)
            }
        }

        return chain.proceed(builder.build())
    }
}
