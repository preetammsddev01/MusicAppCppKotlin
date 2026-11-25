package com.example.data.remote

import com.example.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AdminServerConfig {

    const val DEFAULT_PUBLIC_BASE_URL = "https://deepstorage.dev/testappuser"
    private const val LEGACY_PLACEHOLDER_URL = "https://your-music-server.example.com"
    private const val PLACEHOLDER_TOKEN = "YOUR_AUDIO_SERVER_TOKEN"
    private const val OPTIONAL_PLACEHOLDER_TOKEN = "OPTIONAL_AUDIO_SERVER_TOKEN"

    private fun readBuildConfigField(name: String): String {
        return runCatching {
            BuildConfig::class.java.getField(name).get(null) as? String
        }.getOrNull()?.trim().orEmpty()
    }

    val rawBaseUrl: String
        get() {
            val configured = readBuildConfigField("AUDIO_SERVER_BASE_URL")
            return if (configured.isEmpty() || configured.equals(LEGACY_PLACEHOLDER_URL, ignoreCase = true)) {
                DEFAULT_PUBLIC_BASE_URL
            } else {
                configured.trimEnd('/')
            }
        }

    val baseUrl: String
        get() = when {
            rawBaseUrl.isEmpty() -> DEFAULT_PUBLIC_BASE_URL
            rawBaseUrl.startsWith("http://", ignoreCase = true) ||
                rawBaseUrl.startsWith("https://", ignoreCase = true) -> rawBaseUrl
            else -> "https://$rawBaseUrl"
        }

    val catalogEndpoint: String
        get() {
            val raw = readBuildConfigField("AUDIO_SERVER_CATALOG_ENDPOINT").trimStart('/')
            return if (raw.equals("AUTO_PROBE", ignoreCase = true)) "" else raw
        }

    val directTrackEntries: List<String>
        get() {
            val raw = readBuildConfigField("AUDIO_SERVER_DIRECT_TRACKS")
            if (raw.isEmpty() || raw.equals("NONE", ignoreCase = true)) return emptyList()
            return raw
                .removePrefix("[")
                .removeSuffix("]")
                .split('\n', '\r', ',', ';')
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }

    val authToken: String
        get() {
            val token = readBuildConfigField("AUDIO_SERVER_AUTH_TOKEN")
            return if (
                token == PLACEHOLDER_TOKEN ||
                token == OPTIONAL_PLACEHOLDER_TOKEN
            ) {
                ""
            } else {
                token
            }
        }

    val authHeaderName: String
        get() = readBuildConfigField("AUDIO_SERVER_AUTH_HEADER").ifEmpty { "Authorization" }

    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty() && baseUrl.toHttpUrlOrNull() != null

    val serverHost: String?
        get() = baseUrl.toHttpUrlOrNull()?.host

    val resolvedCatalogUrl: String?
        get() {
            if (!isConfigured || catalogEndpoint.isEmpty()) return null
            return FlexibleTrackParser.resolveUrl(baseUrl, catalogEndpoint)
        }

    fun authHeadersForUrl(url: String): Map<String, String> {
        val token = authToken
        val host = serverHost
        val requestHost = url.toHttpUrlOrNull()?.host
        if (token.isEmpty() || host == null || requestHost == null || !host.equals(requestHost, ignoreCase = true)) {
            return emptyMap()
        }
        val headerName = authHeaderName
        val headerValue = if (headerName.equals("Authorization", ignoreCase = true)) {
            if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        } else {
            token
        }
        return mapOf(headerName to headerValue)
    }
}
