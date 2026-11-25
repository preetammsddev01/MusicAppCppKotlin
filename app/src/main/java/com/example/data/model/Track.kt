package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String? = null,
    val streamUrl: String,
    val format: String = "FLAC",
    val sampleRateHz: Int = 44100,
    val bitDepth: Int = 16,
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val genre: String = "",
    val isLiked: Boolean = false,
    val likedAt: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val metadataEnriched: Boolean = false
) {
    val isLossless: Boolean
        get() = format.uppercase() in setOf("FLAC", "ALAC", "WAV", "AIFF", "DSD", "APE", "PCM")

    val isHiRes: Boolean
        get() = isLossless && (sampleRateHz > 44100 || bitDepth >= 24)

    val shortFormatBadge: String
        get() {
            val fmt = format.uppercase().ifBlank { "AUDIO" }
            val khz = if (sampleRateHz > 0) {
                val value = sampleRateHz / 1000.0
                if (value % 1.0 == 0.0) "${value.toInt()}" else String.format("%.1f", value)
            } else ""
            return when {
                bitDepth > 0 && khz.isNotEmpty() -> "$fmt $bitDepth/$khz"
                khz.isNotEmpty() -> "$fmt ${khz}k"
                else -> fmt
            }
        }

    val detailedSignalLabel: String
        get() {
            val parts = mutableListOf<String>()
            parts.add(format.uppercase().ifBlank { "AUDIO" })
            if (bitDepth > 0 && sampleRateHz > 0) {
                val khz = if (sampleRateHz % 1000 == 0) {
                    "${sampleRateHz / 1000} kHz"
                } else {
                    String.format("%.1f kHz", sampleRateHz / 1000.0)
                }
                parts.add("${bitDepth}-bit / $khz")
            } else if (sampleRateHz > 0) {
                parts.add(String.format("%.1f kHz", sampleRateHz / 1000.0))
            }
            if (bitrateKbps > 0) {
                parts.add("$bitrateKbps kbps")
            }
            return parts.joinToString(" · ")
        }
}
