package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY album ASC, trackNumber ASC, title ASC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE metadataEnriched = 0")
    suspend fun getUnenrichedTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun observeLikedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Query("DELETE FROM tracks")
    suspend fun deleteAllTracks()

    @Query("UPDATE tracks SET isLiked = :liked, likedAt = :likedAt WHERE id = :trackId")
    suspend fun setLiked(trackId: String, liked: Boolean, likedAt: Long)

    @Query("UPDATE tracks SET lastPlayedAt = :playedAt WHERE id = :trackId")
    suspend fun updateLastPlayed(trackId: String, playedAt: Long)

    @Query(
        """
        UPDATE tracks SET
            title = COALESCE(:title, title),
            artist = COALESCE(:artist, artist),
            album = COALESCE(:album, album),
            genre = CASE WHEN :genre IS NOT NULL AND :genre != '' THEN :genre ELSE genre END,
            albumArtUrl = COALESCE(:albumArtUrl, albumArtUrl),
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
            sampleRateHz = CASE WHEN :sampleRateHz > 0 THEN :sampleRateHz ELSE sampleRateHz END,
            bitDepth = CASE WHEN :bitDepth > 0 THEN :bitDepth ELSE bitDepth END,
            bitrateKbps = CASE WHEN :bitrateKbps > 0 THEN :bitrateKbps ELSE bitrateKbps END,
            trackNumber = CASE WHEN :trackNumber > 0 THEN :trackNumber ELSE trackNumber END,
            metadataEnriched = 1
        WHERE id = :trackId
        """
    )
    suspend fun updateEnrichedMetadata(
        trackId: String,
        title: String?,
        artist: String?,
        album: String?,
        genre: String?,
        albumArtUrl: String?,
        durationMs: Long,
        sampleRateHz: Int,
        bitDepth: Int,
        bitrateKbps: Int,
        trackNumber: Int
    )

    @Transaction
    suspend fun replaceCatalogPreservingUserState(incoming: List<TrackEntity>) {
        val existingById = getAllTracksOnce().associateBy { it.id }
        val merged = incoming.map { track ->
            val prev = existingById[track.id]
            if (prev != null) {
                val keepEnriched = prev.metadataEnriched && prev.streamUrl == track.streamUrl
                track.copy(
                    title = if (keepEnriched && prev.title.isNotBlank()) prev.title else track.title,
                    artist = if (keepEnriched && prev.artist != "Unknown Artist") prev.artist else track.artist,
                    album = if (keepEnriched && prev.album != "Unknown Album") prev.album else track.album,
                    genre = if (keepEnriched && prev.genre.isNotBlank()) prev.genre else track.genre,
                    albumArtUrl = track.albumArtUrl ?: if (keepEnriched) prev.albumArtUrl else null,
                    durationMs = if (track.durationMs > 0L) track.durationMs else prev.durationMs,
                    sampleRateHz = if (keepEnriched && prev.sampleRateHz > 0) prev.sampleRateHz else track.sampleRateHz,
                    bitDepth = if (keepEnriched && prev.bitDepth > 0) prev.bitDepth else track.bitDepth,
                    bitrateKbps = if (keepEnriched && prev.bitrateKbps > 0) prev.bitrateKbps else track.bitrateKbps,
                    trackNumber = if (track.trackNumber > 0) track.trackNumber else prev.trackNumber,
                    metadataEnriched = prev.metadataEnriched && prev.streamUrl == track.streamUrl,
                    isLiked = prev.isLiked,
                    likedAt = prev.likedAt,
                    lastPlayedAt = prev.lastPlayedAt
                )
            } else {
                track
            }
        }
        deleteAllTracks()
        insertTracks(merged)
    }
}
