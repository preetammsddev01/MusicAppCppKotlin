package com.example.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class InvalidAudioStreamException(message: String) : IOException(message)

sealed class StreamCheckResult {
    data class Playable(val resolvedUrl: String, val contentType: String) : StreamCheckResult()
    data class Invalid(val reason: String) : StreamCheckResult()
    data object TransientNetworkError : StreamCheckResult()
}

object StreamUrlResolver {

    private val AUDIO_TAG_SRC_REGEX = Regex(
        """<(?:audio|source)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val OG_AUDIO_REGEX = Regex(
        """<meta\b[^>]*\bproperty\s*=\s*["']og:audio(?::secure_url|:url)?["'][^>]*\bcontent\s*=\s*["']([^"']+)["']|<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\bproperty\s*=\s*["']og:audio(?::secure_url|:url)?["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val DOWNLOAD_LINK_REGEX = Regex(
        """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val DATA_AUDIO_ATTR_REGEX = Regex(
        """\bdata-(?:download-url|audio-url|stream-url|src|url)\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val META_REFRESH_REGEX = Regex(
        """<meta\b[^>]*http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["'][^"']*url=([^"'\s>]+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val JSON_STREAM_URL_REGEX = Regex(
        """["'](?:downloadUrl|download_url|streamUrl|stream_url|presignedUrl|signedUrl|rawUrl|audioUrl|contentUrl)["']\s*:\s*["']((?:https?:\\?/\\?/|\\?/)[^"'\s<>]+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val EMBEDDED_AUDIO_URL_REGEX = Regex(
        """["']((?:https?:\\?/\\?/|\\?/)[^"'\s<>]+\.(?:flac|wav|alac|m4a|mp4|aiff?|opus|ogg|oga|mp3|aac|webm|mka)(?:\?[^"'\s<>]*)?)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun isAudioMimeType(contentType: String): Boolean {
        val normalized = contentType.substringBefore(';').trim().lowercase()
        return normalized.startsWith("audio/") ||
            normalized == "application/ogg" ||
            normalized == "application/flac" ||
            normalized == "application/x-flac" ||
            normalized == "video/mp4" ||
            normalized == "video/webm"
    }

    fun isNonAudioMime(contentType: String): Boolean {
        val normalized = contentType.substringBefore(';').trim().lowercase()
        return normalized.contains("text/html") ||
            normalized.contains("text/plain") ||
            normalized.contains("application/xhtml") ||
            normalized.contains("application/json") ||
            normalized.contains("application/xml") ||
            normalized.contains("text/xml") ||
            normalized.contains("javascript")
    }

    fun looksLikeTextMarkupOrJson(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length <= 0) return false
        val limit = length.coerceAtMost(bytes.size)
        var offset = 0
        // Skip UTF-8 BOM if present
        if (limit >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            offset = 3
        }
        while (offset < limit && bytes[offset].toInt().toChar().isWhitespace()) {
            offset++
        }
        if (offset >= limit) return false

        val snippet = String(bytes, offset, (limit - offset).coerceAtMost(64), Charsets.ISO_8859_1)
            .lowercase()
        return snippet.startsWith("<!doctype") ||
            snippet.startsWith("<html") ||
            snippet.startsWith("<head") ||
            snippet.startsWith("<body") ||
            snippet.startsWith("<?xml") ||
            snippet.startsWith("<error") ||
            snippet.startsWith("{\"") ||
            snippet.startsWith("[{")
    }

    fun extractDirectAudioUrlFromHtml(html: String, pageUrl: String): String? {
        if (html.isBlank()) return null

        OG_AUDIO_REGEX.find(html)?.let { match ->
            val raw = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim()
            val decoded = FlexibleTrackParser.decodeHtmlEntities(raw)
            val resolved = FlexibleTrackParser.resolveUrl(
                baseUrl = pageUrl,
                pathOrUrl = decoded,
                originRootForLeadingSlash = true
            )
            if (resolved != null && resolved != pageUrl) return resolved
        }

        AUDIO_TAG_SRC_REGEX.find(html)?.let { match ->
            val decoded = FlexibleTrackParser.decodeHtmlEntities(match.groupValues[1].trim())
            val resolved = FlexibleTrackParser.resolveUrl(
                baseUrl = pageUrl,
                pathOrUrl = decoded,
                originRootForLeadingSlash = true
            )
            if (resolved != null && resolved != pageUrl) return resolved
        }

        DATA_AUDIO_ATTR_REGEX.find(html)?.let { match ->
            val decoded = FlexibleTrackParser.decodeHtmlEntities(match.groupValues[1].trim())
            val resolved = FlexibleTrackParser.resolveUrl(
                baseUrl = pageUrl,
                pathOrUrl = decoded,
                originRootForLeadingSlash = true
            )
            if (resolved != null && resolved != pageUrl) return resolved
        }

        META_REFRESH_REGEX.find(html)?.let { match ->
            val decoded = FlexibleTrackParser.decodeHtmlEntities(match.groupValues[1].trim())
            val resolved = FlexibleTrackParser.resolveUrl(
                baseUrl = pageUrl,
                pathOrUrl = decoded,
                originRootForLeadingSlash = true
            )
            if (resolved != null && resolved != pageUrl) return resolved
        }

        val pageFilenameStem = pageUrl.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').substringBeforeLast('.').lowercase()

        DOWNLOAD_LINK_REGEX.findAll(html).forEach { match ->
            val fullTag = match.value
            val decoded = FlexibleTrackParser.decodeHtmlEntities(match.groupValues[1].trim())
            if (decoded.isEmpty() || decoded.startsWith("#") || decoded.startsWith("javascript:", ignoreCase = true)) {
                return@forEach
            }
            val lower = decoded.lowercase()
            val looksLikeDownload = fullTag.contains("download", ignoreCase = true) ||
                lower.contains("download=") ||
                lower.contains("raw=1") ||
                lower.contains("/raw/") ||
                lower.contains("/download/")
            val matchesPageFile = pageFilenameStem.isNotEmpty() &&
                FlexibleTrackParser.isSupportedAudioUrl(decoded) &&
                decoded.substringBefore('?').substringBefore('#')
                    .substringAfterLast('/').substringBeforeLast('.').lowercase() == pageFilenameStem
            if (looksLikeDownload || matchesPageFile) {
                val resolved = FlexibleTrackParser.resolveUrl(
                    baseUrl = pageUrl,
                    pathOrUrl = decoded,
                    originRootForLeadingSlash = true
                )
                if (resolved != null && resolved != pageUrl) return resolved
            }
        }

        JSON_STREAM_URL_REGEX.find(html)?.let { match ->
            val unescaped = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u002F", "/", ignoreCase = true)
                .trim()
            val decoded = FlexibleTrackParser.decodeHtmlEntities(unescaped)
            val resolved = FlexibleTrackParser.resolveUrl(
                baseUrl = pageUrl,
                pathOrUrl = decoded,
                originRootForLeadingSlash = true
            )
            if (resolved != null && resolved != pageUrl) return resolved
        }

        EMBEDDED_AUDIO_URL_REGEX.find(html)?.let { match ->
            val unescaped = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u002F", "/", ignoreCase = true)
                .trim()
            val decoded = FlexibleTrackParser.decodeHtmlEntities(unescaped)
            val resolved = FlexibleTrackParser.resolveUrl(
                baseUrl = pageUrl,
                pathOrUrl = decoded,
                originRootForLeadingSlash = true
            )
            if (resolved != null && resolved != pageUrl) return resolved
        }

        return null
    }

    fun verifyOrResolveAudioUrl(client: OkHttpClient, url: String): StreamCheckResult {
        return try {
            val headRequest = Request.Builder().url(url).head().build()
            client.newCall(headRequest).execute().use { headResp ->
                val finalHeadUrl = headResp.request.url.toString()
                val headType = headResp.header("Content-Type").orEmpty()
                if (headResp.isSuccessful && isAudioMimeType(headType)) {
                    return StreamCheckResult.Playable(finalHeadUrl, headType)
                }
                if (headResp.code == 404 || headResp.code == 410) {
                    return StreamCheckResult.Invalid("Audio file not found (HTTP ${headResp.code}) at $url")
                }
                if (headResp.code == 401 || headResp.code == 403) {
                    return StreamCheckResult.Invalid("Access denied (HTTP ${headResp.code}) for $url")
                }
            }

            // Probe first bytes via ranged GET to verify binary audio vs HTML/JSON error page
            probeWithRangedGet(client, url, allowHtmlUnwrap = true)
        } catch (_: IOException) {
            StreamCheckResult.TransientNetworkError
        } catch (_: Exception) {
            StreamCheckResult.TransientNetworkError
        }
    }

    private fun probeWithRangedGet(
        client: OkHttpClient,
        url: String,
        allowHtmlUnwrap: Boolean
    ): StreamCheckResult {
        val rangedReq = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-511")
            .build()

        client.newCall(rangedReq).execute().use { resp ->
            if (resp.code == 416) {
                val plainReq = Request.Builder().url(url).get().build()
                client.newCall(plainReq).execute().use { plainResp ->
                    return inspectGetResponse(client, plainResp, url, allowHtmlUnwrap)
                }
            }
            return inspectGetResponse(client, resp, url, allowHtmlUnwrap)
        }
    }

    private fun inspectGetResponse(
        client: OkHttpClient,
        response: Response,
        originalUrl: String,
        allowHtmlUnwrap: Boolean
    ): StreamCheckResult {
        if (!response.isSuccessful) {
            return when (response.code) {
                404, 410 -> StreamCheckResult.Invalid("Audio file not found (HTTP ${response.code}) at $originalUrl")
                401, 403 -> StreamCheckResult.Invalid("Access denied (HTTP ${response.code}) for $originalUrl")
                else -> StreamCheckResult.TransientNetworkError
            }
        }

        val finalUrl = response.request.url.toString()
        val contentType = response.header("Content-Type").orEmpty()
        if (isAudioMimeType(contentType)) {
            return StreamCheckResult.Playable(finalUrl, contentType)
        }

        val peekBytes = runCatching { response.peekBody(512).bytes() }.getOrDefault(ByteArray(0))
        val looksLikeMarkup = looksLikeTextMarkupOrJson(peekBytes)
        val nonAudioMime = isNonAudioMime(contentType)

        if (!nonAudioMime && !looksLikeMarkup) {
            return StreamCheckResult.Playable(finalUrl, contentType)
        }

        val isHtml = contentType.contains("html", ignoreCase = true) || looksLikeMarkup
        if (allowHtmlUnwrap && isHtml) {
            val html = runCatching { response.peekBody(512L * 1024L).string() }.getOrDefault("")
            val extracted = extractDirectAudioUrlFromHtml(html, finalUrl)
            if (extracted != null) {
                val unwrappedResult = probeWithRangedGet(client, extracted, allowHtmlUnwrap = false)
                if (unwrappedResult is StreamCheckResult.Playable) {
                    return unwrappedResult
                }
            }

            val baseCandidates = listOf(originalUrl, finalUrl).distinct()
            for (baseCandidate in baseCandidates) {
                if (!baseCandidate.contains("raw=1", ignoreCase = true) &&
                    !baseCandidate.contains("download=1", ignoreCase = true)
                ) {
                    val separator = if (baseCandidate.contains('?')) '&' else '?'
                    for (suffix in listOf("${separator}raw=1", "${separator}download=1")) {
                        val candidate = "$baseCandidate$suffix"
                        val candidateResult = probeWithRangedGet(client, candidate, allowHtmlUnwrap = false)
                        if (candidateResult is StreamCheckResult.Playable) {
                            return candidateResult
                        }
                    }
                }
            }
        }

        val typeLabel = contentType.ifBlank { "non-audio HTML/text" }
        return StreamCheckResult.Invalid("URL returned $typeLabel instead of raw audio bytes ($finalUrl).")
    }
}

class AudioStreamGuardInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            return response
        }

        val contentType = response.header("Content-Type").orEmpty()
        if (StreamUrlResolver.isAudioMimeType(contentType)) {
            return response
        }

        val nonAudioMime = StreamUrlResolver.isNonAudioMime(contentType)
        val peekBytes = runCatching { response.peekBody(512).bytes() }.getOrDefault(ByteArray(0))
        val looksLikeMarkup = StreamUrlResolver.looksLikeTextMarkupOrJson(peekBytes)

        if (!nonAudioMime && !looksLikeMarkup) {
            return response
        }

        val isHtml = contentType.contains("html", ignoreCase = true) || looksLikeMarkup
        if (isHtml) {
            val html = runCatching { response.peekBody(512L * 1024L).string() }.getOrDefault("")
            val finalUrl = response.request.url.toString()
            val unwrapped = StreamUrlResolver.extractDirectAudioUrlFromHtml(html, finalUrl)
            if (unwrapped != null && unwrapped != finalUrl) {
                response.close()
                val retryRequest = request.newBuilder().url(unwrapped).build()
                val retryResponse = chain.proceed(retryRequest)
                if (!retryResponse.isSuccessful) {
                    return retryResponse
                }
                val retryType = retryResponse.header("Content-Type").orEmpty()
                if (StreamUrlResolver.isAudioMimeType(retryType)) {
                    return retryResponse
                }
                val retryPeek = runCatching { retryResponse.peekBody(512).bytes() }.getOrDefault(ByteArray(0))
                if (!StreamUrlResolver.isNonAudioMime(retryType) &&
                    !StreamUrlResolver.looksLikeTextMarkupOrJson(retryPeek)
                ) {
                    return retryResponse
                }
                retryResponse.close()
                val label = retryType.ifBlank { "HTML/text data" }
                throw InvalidAudioStreamException("Stream returned $label instead of audio bytes ($unwrapped).")
            }
        }

        response.close()
        val label = contentType.ifBlank { "HTML/text data" }
        throw InvalidAudioStreamException("Stream returned $label instead of raw audio bytes (${request.url}).")
    }
}
