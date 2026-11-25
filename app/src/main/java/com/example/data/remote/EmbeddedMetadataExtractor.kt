package com.example.data.remote

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import com.example.data.model.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class EnrichedTrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val albumArtUrl: String? = null,
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val trackNumber: Int = 0
)

class EmbeddedMetadataExtractor(context: Context) {

    private val artCacheDir: File = File(context.cacheDir, "embedded_art").apply {
        if (!exists()) mkdirs()
    }

    suspend fun extractFromStream(track: TrackEntity): EnrichedTrackMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val headers = AdminServerConfig.authHeadersForUrl(track.streamUrl)
            retriever.setDataSource(track.streamUrl, headers)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                ?.trim()?.takeIf { it.isNotEmpty() }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val bitrateKbps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.let { if (it > 0) it / 1000 else 0 } ?: 0
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0

            var sampleRateHz = 0
            var bitDepth = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            }

            val cachedArtUri = if (track.albumArtUrl.isNullOrBlank()) {
                retriever.embeddedPicture?.let { bytes -> saveArtworkBytes(track.id, bytes) }
            } else {
                null
            }

            EnrichedTrackMetadata(
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                albumArtUrl = cachedArtUri,
                durationMs = durationMs,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                bitrateKbps = bitrateKbps,
                trackNumber = trackNumber
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun saveArtworkBytes(trackId: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1").digest(trackId.toByteArray(Charsets.UTF_8))
            val key = digest.joinToString("") { "%02x".format(it) }
            val outFile = File(artCacheDir, "$key.img")
            if (!outFile.exists() || outFile.length() != bytes.size.toLong()) {
                outFile.writeBytes(bytes)
            }
            outFile.toURI().toString()
        }.getOrNull()
    }
}
