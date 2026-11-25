package com.example.data.remote

import android.content.Context
import com.example.data.local.PlaylistDao
import com.example.data.local.TrackDao
import com.example.data.model.PlaylistEntity
import com.example.data.model.PlaylistTrackCrossRef
import com.example.data.model.PlaylistWithTracks
import com.example.data.model.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed class SyncResult {
    data class Success(val trackCount: Int) : SyncResult()
    data class Failure(val message: String) : SyncResult()
    data object NotConfigured : SyncResult()
}

class CloudMusicRepository(
    context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val okHttpClient: OkHttpClient
) {

    private val metadataExtractor = EmbeddedMetadataExtractor(context)
    private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var enrichmentJob: Job? = null

    val isServerConfigured: Boolean
        get() = AdminServerConfig.isConfigured

    fun observeAllTracks(): Flow<List<TrackEntity>> = trackDao.observeAllTracks()

    fun observeLikedTracks(): Flow<List<TrackEntity>> = trackDao.observeLikedTracks()

    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>> =
        trackDao.observeRecentlyPlayed(limit)

    fun observePlaylistsWithTracks(): Flow<List<PlaylistWithTracks>> = combine(
        playlistDao.observePlaylists(),
        playlistDao.observeAllPlaylistTrackRefs(),
        trackDao.observeAllTracks()
    ) { playlists, refs, tracks ->
        val trackMap = tracks.associateBy { it.id }
        val refsByPlaylist = refs.groupBy { it.playlistId }
        playlists.map { playlist ->
            val playlistTracks = refsByPlaylist[playlist.id]
                ?.mapNotNull { trackMap[it.trackId] }
                ?: emptyList()
            PlaylistWithTracks(playlist = playlist, tracks = playlistTracks)
        }
    }

    suspend fun syncCatalog(): SyncResult = withContext(Dispatchers.IO) {
        if (!AdminServerConfig.isConfigured) {
            return@withContext SyncResult.NotConfigured
        }

        val discoveredTracks = LinkedHashMap<String, TrackEntity>()

        // 1. Always include any explicit direct filenames/URLs configured for non-indexed folders
        AdminServerConfig.directTrackEntries.forEachIndexed { idx, entry ->
            FlexibleTrackParser.buildTrackFromUrl(
                rawUrlOrFilename = entry,
                baseUrl = AdminServerConfig.baseUrl,
                index = idx
            )?.let { track ->
                discoveredTracks.putIfAbsent(track.streamUrl, track)
            }
        }

        // 2. Fetch configured catalog endpoint or probe standard manifests under baseUrl
        val candidateUrls = buildCandidateCatalogUrls()
        var lastNetworkError: Exception? = null
        var lastHttpCode: Int? = null

        for (candidateUrl in candidateUrls) {
            try {
                val tracks = fetchTracksFromCandidateUrl(candidateUrl)
                if (tracks.isNotEmpty()) {
                    tracks.forEach { track ->
                        discoveredTracks.putIfAbsent(track.streamUrl, track)
                    }
                    break
                }
            } catch (e: HttpStatusException) {
                lastHttpCode = e.code
            } catch (e: Exception) {
                lastNetworkError = e
            }
        }

        if (discoveredTracks.isNotEmpty()) {
            val finalTracks = discoveredTracks.values.toList()
            trackDao.replaceCatalogPreservingUserState(finalTracks)
            scheduleBackgroundEnrichment()
            return@withContext SyncResult.Success(finalTracks.size)
        }

        if (lastNetworkError != null) {
            return@withContext SyncResult.Failure(formatNetworkError(lastNetworkError))
        }

        val failureMessage = when (lastHttpCode) {
            401, 403 -> "Stream catalog access denied ($lastHttpCode)."
            else -> "No track manifest or direct audio URLs found at ${AdminServerConfig.baseUrl}. Host a catalog.json / tracks.txt file there or set AUDIO_SERVER_DIRECT_TRACKS."
        }
        SyncResult.Failure(failureMessage)
    }

    private fun buildCandidateCatalogUrls(): List<String> {
        val explicit = AdminServerConfig.resolvedCatalogUrl
        if (!explicit.isNullOrBlank()) {
            return listOf(explicit)
        }
        val base = AdminServerConfig.baseUrl
        val manifestNames = listOf(
            "",
            "catalog.json",
            "tracks.json",
            "index.json",
            "tracks.txt",
            "playlist.m3u",
            "playlist.m3u8"
        )
        return manifestNames.mapNotNull { name ->
            if (name.isEmpty()) base else FlexibleTrackParser.resolveUrl(base, name)
        }.distinct()
    }

    private fun fetchTracksFromCandidateUrl(url: String): List<TrackEntity> {
        // Check if the configured URL itself is a direct audio stream URL
        if (FlexibleTrackParser.isSupportedAudioUrl(url)) {
            return listOfNotNull(
                FlexibleTrackParser.buildTrackFromUrl(url, AdminServerConfig.baseUrl, 0)
            )
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json, text/plain, audio/x-mpegurl, text/html, */*")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code)
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.startsWith("audio/", ignoreCase = true)) {
                return listOfNotNull(
                    FlexibleTrackParser.buildTrackFromUrl(url, AdminServerConfig.baseUrl, 0)
                )
            }
            val body = response.body?.string().orEmpty()
            return FlexibleTrackParser.parseCatalogPayload(
                rawPayload = body,
                contentType = contentType,
                baseUrl = AdminServerConfig.baseUrl
            )
        }
    }

    private fun scheduleBackgroundEnrichment() {
        enrichmentJob?.cancel()
        enrichmentJob = enrichmentScope.launch {
            val pending = trackDao.getUnenrichedTracksOnce()
            if (pending.isEmpty()) return@launch
            val semaphore = Semaphore(3)
            coroutineScope {
                pending.map { track ->
                    async {
                        semaphore.withPermit {
                            val enriched = metadataExtractor.extractFromStream(track)
                            if (enriched != null) {
                                trackDao.updateEnrichedMetadata(
                                    trackId = track.id,
                                    title = enriched.title,
                                    artist = enriched.artist,
                                    album = enriched.album,
                                    genre = enriched.genre,
                                    albumArtUrl = enriched.albumArtUrl,
                                    durationMs = enriched.durationMs,
                                    sampleRateHz = enriched.sampleRateHz,
                                    bitDepth = enriched.bitDepth,
                                    bitrateKbps = enriched.bitrateKbps,
                                    trackNumber = enriched.trackNumber
                                )
                            } else {
                                trackDao.updateEnrichedMetadata(
                                    trackId = track.id,
                                    title = null,
                                    artist = null,
                                    album = null,
                                    genre = null,
                                    albumArtUrl = null,
                                    durationMs = 0L,
                                    sampleRateHz = 0,
                                    bitDepth = 0,
                                    bitrateKbps = 0,
                                    trackNumber = 0
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    suspend fun enrichTrackFromPlayback(
        trackId: String,
        title: String?,
        artist: String?,
        album: String?,
        genre: String?,
        artworkBytes: ByteArray?,
        sampleRateHz: Int = 0,
        bitDepth: Int = 0,
        bitrateKbps: Int = 0
    ) = withContext(Dispatchers.IO) {
        if (trackId.isBlank()) return@withContext
        val existing = trackDao.getTrackById(trackId) ?: return@withContext
        val cachedArtUri = if (existing.albumArtUrl.isNullOrBlank() && artworkBytes != null) {
            metadataExtractor.saveArtworkBytes(trackId, artworkBytes)
        } else {
            null
        }
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() }
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
        val cleanGenre = genre?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanTitle != null || cleanArtist != null || cleanAlbum != null ||
            cleanGenre != null || cachedArtUri != null || sampleRateHz > 0 || bitDepth > 0 || bitrateKbps > 0
        ) {
            trackDao.updateEnrichedMetadata(
                trackId = trackId,
                title = cleanTitle,
                artist = cleanArtist,
                album = cleanAlbum,
                genre = cleanGenre,
                albumArtUrl = cachedArtUri,
                durationMs = 0L,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                bitrateKbps = bitrateKbps,
                trackNumber = 0
            )
        }
    }

    suspend fun toggleLike(track: TrackEntity) {
        val nextLiked = !track.isLiked
        val likedAt = if (nextLiked) System.currentTimeMillis() else 0L
        trackDao.setLiked(track.id, nextLiked, likedAt)
    }

    suspend fun recordTrackPlayed(trackId: String) {
        trackDao.updateLastPlayed(trackId, System.currentTimeMillis())
    }

    suspend fun createPlaylist(name: String, initialTrackId: String? = null): PlaylistEntity {
        val playlist = PlaylistEntity(name = name.trim())
        playlistDao.insertPlaylist(playlist)
        if (!initialTrackId.isNullOrBlank()) {
            playlistDao.addTrackToPlaylist(
                PlaylistTrackCrossRef(playlistId = playlist.id, trackId = initialTrackId)
            )
        }
        return playlist
    }

    suspend fun renamePlaylist(playlistId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            playlistDao.renamePlaylist(playlistId, trimmed)
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    private fun formatNetworkError(e: Exception): String = when (e) {
        is UnknownHostException -> "Unable to reach streaming server. Check your internet connection."
        is SocketTimeoutException -> "Streaming server timed out. Please try again."
        is SSLException -> "Secure connection to streaming server failed."
        is IOException -> "Network error while loading music catalog."
        else -> "Unable to load music catalog."
    }

    private class HttpStatusException(val code: Int) : RuntimeException("HTTP $code")
}
