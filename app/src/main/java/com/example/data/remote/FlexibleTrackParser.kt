package com.example.data.remote

import com.example.data.model.TrackEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.security.MessageDigest

object FlexibleTrackParser {

    private val AUDIO_EXTENSIONS = setOf(
        "flac", "wav", "alac", "m4a", "aiff", "aif", "opus", "ogg", "mp3", "aac", "dsf", "dff", "wv", "ape"
    )

    private val HTML_HREF_REGEX = Regex(
        """<a\s+[^>]*href\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val SAMPLE_RATE_HINT_REGEX = Regex(
        """(\d{2,3}(?:\.\d)?)\s*khz""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val BIT_DEPTH_HINT_REGEX = Regex(
        """(16|24|32)\s*-?\s*bit""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val QUALITY_BRACKET_REGEX = Regex(
        """[\(\[]\s*(?:\d{2}\s*-?\s*bit|\d{2,3}(?:\.\d)?\s*khz|hi-?res|flac|lossless)[^\)\]]*[\)\]]""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun isSupportedAudioUrl(urlOrFilename: String): Boolean {
        val cleanPath = urlOrFilename.substringBefore('?').substringBefore('#').trim()
        val ext = cleanPath.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun parseCatalogPayload(
        rawPayload: String,
        contentType: String?,
        baseUrl: String
    ): List<TrackEntity> {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return emptyList()

        val mime = contentType.orEmpty().lowercase()

        // 1. JSON array or object
        if (mime.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return runCatching { parseTracks(trimmed, baseUrl) }.getOrDefault(emptyList())
        }

        // 2. M3U / M3U8 playlist
        if (mime.contains("mpegurl") || trimmed.startsWith("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF:")) {
            return parseM3uPlaylist(trimmed, baseUrl)
        }

        // 3. HTML directory listing fallback
        if (mime.contains("html") || trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<a ", ignoreCase = true)) {
            return parseHtmlDirectoryLinks(trimmed, baseUrl)
        }

        // 4. Plain-text URL/filename list (e.g., tracks.txt)
        return parsePlainTextList(trimmed, baseUrl)
    }

    fun parseTracks(rawJson: String, baseUrl: String): List<TrackEntity> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rootElement: JsonElement = json.parseToJsonElement(trimmed)
        val jsonArray: JsonArray = when (rootElement) {
            is JsonArray -> rootElement
            is JsonObject -> extractArrayFromObject(rootElement)
                ?: throw IllegalArgumentException(
                    "Catalog JSON does not contain a 'tracks', 'data', 'items', 'songs', or 'files' array."
                )
            else -> throw IllegalArgumentException("Response is not a valid JSON array or object.")
        }

        val tracks = mutableListOf<TrackEntity>()
        jsonArray.forEachIndexed { i, element ->
            when (element) {
                is JsonObject -> parseTrackObject(element, baseUrl, i)?.let { tracks.add(it) }
                is JsonPrimitive -> {
                    val urlOrName = element.contentOrNull?.trim()
                    if (!urlOrName.isNullOrEmpty() && isSupportedAudioUrl(urlOrName)) {
                        buildTrackFromUrl(urlOrName, baseUrl, i)?.let { tracks.add(it) }
                    }
                }
                else -> Unit
            }
        }
        return tracks
    }

    fun parseM3uPlaylist(rawM3u: String, baseUrl: String): List<TrackEntity> {
        val tracks = mutableListOf<TrackEntity>()
        var pendingDurationSec = 0L
        var pendingArtist: String? = null
        var pendingTitle: String? = null

        rawM3u.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = line.removePrefix("#EXTINF:").removePrefix("#extinf:")
                val durationPart = info.substringBefore(',').trim().toLongOrNull() ?: 0L
                val labelPart = info.substringAfter(',', "").trim()
                pendingDurationSec = durationPart.coerceAtLeast(0L)
                if (labelPart.contains(" - ")) {
                    pendingArtist = labelPart.substringBefore(" - ").trim().takeIf { it.isNotEmpty() }
                    pendingTitle = labelPart.substringAfter(" - ").trim().takeIf { it.isNotEmpty() }
                } else if (labelPart.isNotEmpty()) {
                    pendingTitle = labelPart
                }
            } else if (!line.startsWith("#")) {
                buildTrackFromUrl(
                    rawUrlOrFilename = line,
                    baseUrl = baseUrl,
                    index = tracks.size,
                    overrideTitle = pendingTitle,
                    overrideArtist = pendingArtist,
                    overrideDurationMs = pendingDurationSec * 1000L
                )?.let { tracks.add(it) }
                pendingDurationSec = 0L
                pendingArtist = null
                pendingTitle = null
            }
        }
        return tracks
    }

    fun parseHtmlDirectoryLinks(html: String, baseUrl: String): List<TrackEntity> {
        val seen = HashSet<String>()
        val tracks = mutableListOf<TrackEntity>()
        HTML_HREF_REGEX.findAll(html).forEach { match ->
            val href = match.groupValues[1].trim()
            if (href.isEmpty() || href == "../" || href.startsWith("?") || href.startsWith("#")) {
                return@forEach
            }
            if (!isSupportedAudioUrl(href)) return@forEach
            val track = buildTrackFromUrl(href, baseUrl, tracks.size) ?: return@forEach
            if (seen.add(track.streamUrl)) {
                tracks.add(track)
            }
        }
        return tracks
    }

    fun parsePlainTextList(text: String, baseUrl: String): List<TrackEntity> {
        val seen = HashSet<String>()
        val tracks = mutableListOf<TrackEntity>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trim('"', '\'')
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (!isSupportedAudioUrl(line)) return@forEach
            val track = buildTrackFromUrl(line, baseUrl, tracks.size) ?: return@forEach
            if (seen.add(track.streamUrl)) {
                tracks.add(track)
            }
        }
        return tracks
    }

    fun buildTrackFromUrl(
        rawUrlOrFilename: String,
        baseUrl: String,
        index: Int = 0,
        overrideTitle: String? = null,
        overrideArtist: String? = null,
        overrideAlbum: String? = null,
        overrideDurationMs: Long = 0L
    ): TrackEntity? {
        val resolvedStreamUrl = resolveUrl(baseUrl, rawUrlOrFilename) ?: return null
        val format = inferFormatFromUrl(resolvedStreamUrl)
        val parsed = parseFilenameAndPathMetadata(resolvedStreamUrl, baseUrl, index)

        val defaultBitDepth = when {
            parsed.bitDepth > 0 -> parsed.bitDepth
            format == "FLAC" || format == "WAV" || format == "ALAC" || format == "AIFF" -> 16
            else -> 0
        }

        return TrackEntity(
            id = stableTrackId(resolvedStreamUrl),
            title = overrideTitle?.takeIf { it.isNotBlank() } ?: parsed.title,
            artist = overrideArtist?.takeIf { it.isNotBlank() } ?: parsed.artist,
            album = overrideAlbum?.takeIf { it.isNotBlank() } ?: parsed.album,
            albumArtUrl = null,
            streamUrl = resolvedStreamUrl,
            format = format,
            sampleRateHz = parsed.sampleRateHz,
            bitDepth = defaultBitDepth,
            bitrateKbps = 0,
            durationMs = overrideDurationMs,
            trackNumber = parsed.trackNumber,
            genre = "",
            metadataEnriched = false
        )
    }

    private data class FilenameMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val trackNumber: Int,
        val sampleRateHz: Int,
        val bitDepth: Int
    )

    private fun parseFilenameAndPathMetadata(
        resolvedUrl: String,
        baseUrl: String,
        index: Int
    ): FilenameMetadata {
        val parsedUrl = resolvedUrl.toHttpUrlOrNull()
        val baseParsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()

        val allSegments = parsedUrl?.pathSegments
            ?.filter { it.isNotEmpty() }
            ?.map { safeUrlDecode(it) }
            ?: listOf(safeUrlDecode(resolvedUrl.substringBefore('?').substringAfterLast('/')))

        val baseSegments = baseParsed?.pathSegments?.filter { it.isNotEmpty() }.orEmpty()
        val relativeSegments = if (
            allSegments.size > baseSegments.size &&
            allSegments.take(baseSegments.size).map { it.lowercase() } == baseSegments.map { it.lowercase() }
        ) {
            allSegments.drop(baseSegments.size)
        } else {
            allSegments
        }

        val rawFilename = relativeSegments.lastOrNull().orEmpty()
        val folderSegments = relativeSegments.dropLast(1)
        val stem = rawFilename.substringBeforeLast('.').trim()

        val bitDepthHint = BIT_DEPTH_HINT_REGEX.find(stem)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sampleRateHint = SAMPLE_RATE_HINT_REGEX.find(stem)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { khz -> (khz * 1000.0).toInt() }
            ?: 44100

        val cleanedStem = QUALITY_BRACKET_REGEX.replace(stem, "")
            .replace("–", "-")
            .trim()

        var trackNumber = 0
        var artist = ""
        var album = ""
        var title = ""

        val dashParts = cleanedStem.split(Regex("""\s+-\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        when {
            dashParts.size >= 4 -> {
                val thirdNum = dashParts[2].toIntOrNull()
                if (thirdNum != null) {
                    artist = dashParts[0]
                    album = dashParts[1]
                    trackNumber = thirdNum
                    title = dashParts.drop(3).joinToString(" - ")
                } else {
                    artist = dashParts[0]
                    album = dashParts[1]
                    title = dashParts.drop(2).joinToString(" - ")
                }
            }
            dashParts.size == 3 -> {
                val firstNum = dashParts[0].trimEnd('.').toIntOrNull()
                if (firstNum != null) {
                    trackNumber = firstNum
                    artist = dashParts[1]
                    title = dashParts[2]
                } else {
                    artist = dashParts[0]
                    album = dashParts[1]
                    title = dashParts[2]
                }
            }
            dashParts.size == 2 -> {
                val firstNum = dashParts[0].trimEnd('.').toIntOrNull()
                if (firstNum != null) {
                    trackNumber = firstNum
                    title = dashParts[1]
                } else {
                    artist = dashParts[0]
                    title = dashParts[1]
                }
            }
            else -> {
                val leadingNumMatch = Regex("""^(\d{1,3})[\.\s_\-]+(.+)$""").find(cleanedStem)
                if (leadingNumMatch != null) {
                    trackNumber = leadingNumMatch.groupValues[1].toIntOrNull() ?: 0
                    title = leadingNumMatch.groupValues[2]
                } else {
                    title = cleanedStem
                }
            }
        }

        if (folderSegments.size >= 2) {
            if (artist.isBlank()) artist = cleanDisplaySegment(folderSegments[folderSegments.size - 2])
            if (album.isBlank()) album = cleanDisplaySegment(folderSegments[folderSegments.size - 1])
        } else if (folderSegments.size == 1 && album.isBlank()) {
            album = cleanDisplaySegment(folderSegments[0])
        }

        val normalizedTitle = cleanDisplaySegment(title).ifBlank { "Track ${index + 1}" }
        val normalizedArtist = cleanDisplaySegment(artist).ifBlank { "Unknown Artist" }
        val normalizedAlbum = cleanDisplaySegment(album).ifBlank { "Public Cloud Collection" }

        return FilenameMetadata(
            title = normalizedTitle,
            artist = normalizedArtist,
            album = normalizedAlbum,
            trackNumber = trackNumber,
            sampleRateHz = sampleRateHint,
            bitDepth = bitDepthHint
        )
    }

    private fun cleanDisplaySegment(value: String): String {
        return value
            .replace('_', ' ')
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun safeUrlDecode(value: String): String {
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(value)
    }

    private fun stableTrackId(streamUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(streamUrl.toByteArray(Charsets.UTF_8))
        return "trk_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun extractArrayFromObject(root: JsonObject): JsonArray? {
        val candidateKeys = listOf("tracks", "data", "items", "songs", "results", "library", "files")
        for (key in candidateKeys) {
            val arr = root[key] as? JsonArray
            if (arr != null) return arr
        }
        for ((_, value) in root) {
            val nested = value as? JsonObject ?: continue
            for (candidate in candidateKeys) {
                val arr = nested[candidate] as? JsonArray
                if (arr != null) return arr
            }
        }
        return null
    }

    private fun parseTrackObject(
        obj: JsonObject,
        baseUrl: String,
        index: Int
    ): TrackEntity? {
        val rawStreamUrl = obj.firstString(
            "streamUrl", "stream_url", "audioUrl", "audio_url", "url", "src", "fileUrl", "file_url", "file", "path"
        ) ?: return null

        val resolvedStreamUrl = resolveUrl(baseUrl, rawStreamUrl) ?: return null
        val fallback = parseFilenameAndPathMetadata(resolvedStreamUrl, baseUrl, index)

        val id = obj.firstString("id", "trackId", "track_id", "_id", "uuid")
            ?: stableTrackId(resolvedStreamUrl)
        val title = obj.firstString("title", "name", "trackTitle", "track_title")
            ?.takeIf { it.isNotBlank() } ?: fallback.title
        val artist = obj.firstString("artist", "artistName", "artist_name", "albumArtist")
            ?.takeIf { it.isNotBlank() } ?: fallback.artist
        val album = obj.firstString("album", "albumTitle", "album_title", "albumName")
            ?.takeIf { it.isNotBlank() } ?: fallback.album
        val rawArtUrl = obj.firstString(
            "albumArtUrl", "album_art_url", "coverUrl", "cover_url", "artworkUrl", "artwork", "cover", "image"
        )
        val resolvedArtUrl = rawArtUrl?.let { resolveUrl(baseUrl, it) }

        val format = obj.firstString("format", "codec", "mimeType", "extension", "fileType")
            ?.uppercase()
            ?.removePrefix("AUDIO/")
            ?.removePrefix(".")
            ?.takeIf { it.isNotBlank() }
            ?: inferFormatFromUrl(resolvedStreamUrl)

        val sampleRateHz = obj.firstInt("sampleRateHz", "sample_rate_hz", "sampleRate", "sample_rate")
            .let { sr ->
                when {
                    sr in 8..384 -> sr * 1000
                    sr > 0 -> sr
                    else -> fallback.sampleRateHz
                }
            }
        val bitDepth = obj.firstInt("bitDepth", "bit_depth", "bitsPerSample", "bits_per_sample")
            .let {
                when {
                    it > 0 -> it
                    fallback.bitDepth > 0 -> fallback.bitDepth
                    format == "FLAC" || format == "WAV" || format == "ALAC" -> 16
                    else -> 0
                }
            }
        val bitrateKbps = obj.firstInt("bitrateKbps", "bitrate_kbps", "bitrate", "kbps")
            .let { if (it > 10000) it / 1000 else it }

        val durationMs = parseDurationMs(obj)
        val trackNumber = obj.firstInt("trackNumber", "track_number", "track", "number")
            .let { if (it > 0) it else fallback.trackNumber }
        val genre = obj.firstString("genre", "style") ?: ""

        return TrackEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumArtUrl = resolvedArtUrl,
            streamUrl = resolvedStreamUrl,
            format = format,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            durationMs = durationMs,
            trackNumber = trackNumber,
            genre = genre,
            metadataEnriched = false
        )
    }

    private fun parseDurationMs(obj: JsonObject): Long {
        val ms = obj.firstLong("durationMs", "duration_ms", "lengthMs")
        if (ms > 0L) return ms
        val sec = obj.firstDouble("durationSeconds", "duration_sec")
        if (sec != null && sec > 0.0) return (sec * 1000.0).toLong()

        val rawDuration = obj.firstDouble("duration")
        if (rawDuration != null && rawDuration > 0.0) {
            return if (rawDuration < 18000.0) (rawDuration * 1000.0).toLong() else rawDuration.toLong()
        }
        return 0L
    }

    fun inferFormatFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".flac") -> "FLAC"
            path.endsWith(".wav") -> "WAV"
            path.endsWith(".alac") || path.endsWith(".m4a") -> "ALAC"
            path.endsWith(".aiff") || path.endsWith(".aif") -> "AIFF"
            path.endsWith(".opus") -> "OPUS"
            path.endsWith(".ogg") -> "OGG"
            path.endsWith(".mp3") -> "MP3"
            path.endsWith(".aac") -> "AAC"
            path.endsWith(".dsf") || path.endsWith(".dff") -> "DSD"
            else -> "FLAC"
        }
    }

    fun resolveUrl(baseUrl: String, pathOrUrl: String): String? {
        val trimmed = pathOrUrl.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        if (trimmed.startsWith("//")) {
            return "${base.scheme}:$trimmed"
        }
        // Preserve any subpath on the base URL (e.g. https://deepstorage.dev/testappuser/)
        val baseWithTrailingSlash = "${base.toString().trimEnd('/')}/".toHttpUrlOrNull() ?: base
        val relative = trimmed.trimStart('/')
        return baseWithTrailingSlash.resolve(relative)?.toString()
    }

    private fun JsonObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            if (prim is JsonNull) continue
            val value = prim.contentOrNull?.trim()
            if (!value.isNullOrEmpty() && value != "null") return value
        }
        return null
    }

    private fun JsonObject.firstInt(vararg keys: String): Int {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            if (prim is JsonNull) continue
            val value = prim.intOrNull ?: prim.doubleOrNull?.toInt()
            if (value != null) return value
        }
        return 0
    }

    private fun JsonObject.firstLong(vararg keys: String): Long {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            if (prim is JsonNull) continue
            val value = prim.longOrNull ?: prim.doubleOrNull?.toLong()
            if (value != null) return value
        }
        return 0L
    }

    private fun JsonObject.firstDouble(vararg keys: String): Double? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            if (prim is JsonNull) continue
            val value = prim.doubleOrNull
            if (value != null) return value
        }
        return null
    }
}
