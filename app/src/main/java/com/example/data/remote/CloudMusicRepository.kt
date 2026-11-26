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
import java.net.URLEncoder
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

        val validationErrors = mutableListOf<String>()
        var lastNetworkError: Exception? = null
        var lastHttpCode: Int? = null

        // 1. Validate explicit direct audio filenames/URLs first if configured
        val directTracks = AdminServerConfig.directTrackEntries
            .filter { FlexibleTrackParser.isSupportedAudioUrl(it) }
            .mapIndexedNotNull { idx, entry ->
                FlexibleTrackParser.buildTrackFromUrl(
                    rawUrlOrFilename = entry,
                    baseUrl = AdminServerConfig.baseUrl,
                    index = idx
                )
            }.distinctBy { it.streamUrl }

        if (directTracks.isNotEmpty()) {
            val validatedDirect = validateStreamUrls(directTracks, validationErrors)
            if (validatedDirect.isNotEmpty()) {
                trackDao.replaceCatalogPreservingUserState(validatedDirect)
                scheduleBackgroundEnrichment()
                return@withContext SyncResult.Success(validatedDirect.size)
            }
        }

        // 2. Check configured endpoint or probe structured manifests before HTML page scraping
        val candidateEndpoints = buildCandidateCatalogUrls()
        var htmlFallbackTracks: List<TrackEntity> = emptyList()

        for (candidate in candidateEndpoints) {
            try {
                val result = fetchTracksFromCandidateUrl(
                    url = candidate.url,
                    allowHtmlFallback = candidate.allowHtmlFallback
                )
                if (result.tracks.isEmpty()) continue

                if (result.fromHtml) {
                    if (htmlFallbackTracks.isEmpty()) {
                        htmlFallbackTracks = result.tracks
                    }
                    continue
                }

                val validated = validateStreamUrls(result.tracks, validationErrors)
                if (validated.isNotEmpty()) {
                    trackDao.replaceCatalogPreservingUserState(validated)
                    scheduleBackgroundEnrichment()
                    return@withContext SyncResult.Success(validated.size)
                }
            } catch (e: HttpStatusException) {
                lastHttpCode = e.code
            } catch (e: Exception) {
                lastNetworkError = e
            }
        }

        // 3. Fall back to validating HTML-discovered tracks only if no structured manifest succeeded
        if (htmlFallbackTracks.isNotEmpty()) {
            val validatedHtml = validateStreamUrls(htmlFallbackTracks, validationErrors)
            if (validatedHtml.isNotEmpty()) {
                trackDao.replaceCatalogPreservingUserState(validatedHtml)
                scheduleBackgroundEnrichment()
                return@withContext SyncResult.Success(validatedHtml.size)
            }
        }

        if (validationErrors.isNotEmpty()) {
            return@withContext SyncResult.Failure(formatValidationFailure(validationErrors))
        }

        if (lastNetworkError != null) {
            return@withContext SyncResult.Failure(formatNetworkError(lastNetworkError))
        }

        val failureMessage = when (lastHttpCode) {
            401, 403 -> "Stream catalog access denied ($lastHttpCode)."
            else -> "No playable audio catalog was found at ${AdminServerConfig.baseUrl}. Host a catalog.json / tracks.txt manifest or direct audio links."
        }
        SyncResult.Failure(failureMessage)
    }

    private suspend fun validateStreamUrls(
        tracks: List<TrackEntity>,
        validationErrors: MutableList<String>
    ): List<TrackEntity> = coroutineScope {
        val semaphore = Semaphore(4)
        tracks.map { track ->
            async {
                semaphore.withPermit {
                    when (val result = StreamUrlResolver.verifyOrResolveAudioUrl(okHttpClient, track.streamUrl)) {
                        is StreamCheckResult.Playable -> {
                            if (result.resolvedUrl != track.streamUrl) {
                                track.copy(streamUrl = result.resolvedUrl)
                            } else {
                                track
                            }
                        }
                        is StreamCheckResult.Invalid -> {
                            synchronized(validationErrors) {
                                validationErrors.add(result.reason)
                            }
                            null
                        }
                        is StreamCheckResult.TransientNetworkError -> track
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private data class CandidateEndpoint(
        val url: String,
        val allowHtmlFallback: Boolean
    )

    private data class CandidateFetchResult(
        val tracks: List<TrackEntity>,
        val fromHtml: Boolean
    )

    private fun buildCandidateCatalogUrls(): List<CandidateEndpoint> {
        val base = AdminServerConfig.baseUrl
        val candidates = mutableListOf<CandidateEndpoint>()

        fun addCandidate(url: String?, allowHtmlFallback: Boolean) {
            val clean = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (candidates.none { it.url == clean }) {
                candidates.add(CandidateEndpoint(url = clean, allowHtmlFallback = allowHtmlFallback))
            }
        }

        // Folder / public-share URLs placed in AUDIO_SERVER_DIRECT_TRACKS (e.g. https://deepstorage.dev/testappuser)
        AdminServerConfig.directTrackEntries
            .filterNot { FlexibleTrackParser.isSupportedAudioUrl(it) }
            .forEach { entry ->
                addCandidate(FlexibleTrackParser.resolveUrl(base, entry), allowHtmlFallback = true)
            }

        addCandidate(AdminServerConfig.resolvedCatalogUrl, allowHtmlFallback = true)
        addCandidate(base, allowHtmlFallback = true)

        val manifestNames = listOf(
            "catalog.json",
            "tracks.json",
            "index.json",
            "tracks.txt",
            "playlist.m3u",
            "playlist.m3u8"
        )
        manifestNames.forEach { name ->
            addCandidate(FlexibleTrackParser.resolveUrl(base, name), allowHtmlFallback = false)
        }
        return candidates
    }

    private fun fetchTracksFromCandidateUrl(
        url: String,
        allowHtmlFallback: Boolean
    ): CandidateFetchResult {
        // Check if the configured URL itself is a direct audio stream URL
        if (FlexibleTrackParser.isSupportedAudioUrl(url)) {
            val single = listOfNotNull(
                FlexibleTrackParser.buildTrackFromUrl(url, AdminServerConfig.baseUrl, 0)
            )
            return CandidateFetchResult(tracks = single, fromHtml = false)
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
            val effectiveUrl = response.request.url.toString()
            val contentType = response.header("Content-Type").orEmpty()
            if (StreamUrlResolver.isAudioMimeType(contentType)) {
                val single = listOfNotNull(
                    FlexibleTrackParser.buildTrackFromUrl(effectiveUrl, AdminServerConfig.baseUrl, 0)
                )
                return CandidateFetchResult(tracks = single, fromHtml = false)
            }
            val body = response.body?.string().orEmpty()
            val deepStorageShare = FlexibleTrackParser.extractDeepStoragePublicShareInfo(body, effectiveUrl)
            if (deepStorageShare != null) {
                val shareTracks = fetchDeepStoragePublicShareTracks(deepStorageShare)
                return CandidateFetchResult(tracks = shareTracks, fromHtml = false)
            }

            val parsed = FlexibleTrackParser.parseCatalogPayload(
                rawPayload = body,
                contentType = contentType,
                baseUrl = effectiveUrl,
                allowHtmlFallback = allowHtmlFallback
            )
            val isHtmlResponse = contentType.contains("html", ignoreCase = true) ||
                body.trimStart().startsWith("<!doctype", ignoreCase = true) ||
                body.trimStart().startsWith("<html", ignoreCase = true)
            return CandidateFetchResult(tracks = parsed, fromHtml = isHtmlResponse)
        }
    }

    private fun fetchDeepStoragePublicShareTracks(shareInfo: DeepStorageShareInfo): List<TrackEntity> {
        val collected = LinkedHashMap<String, TrackEntity>()
        val pendingFolders = ArrayDeque<String>()
        val visitedFolders = HashSet<String>()
        pendingFolders.add("")
        visitedFolders.add("")

        val encodedUsername = URLEncoder.encode(shareInfo.username, "UTF-8").replace("+", "%20")

        while (pendingFolders.isNotEmpty() && visitedFolders.size <= 30 && collected.size < 500) {
            val relPath = pendingFolders.removeFirst()
            var offset = 0
            var pageCount = 0

            while (pageCount < 20 && collected.size < 500) {
                val encodedPath = URLEncoder.encode(relPath, "UTF-8").replace("+", "%20")
                val apiUrl = "${shareInfo.apiOrigin}/public/api/files/$encodedUsername?path=$encodedPath&offset=$offset&sort=date_desc"
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .get()
                    .header("Accept", "application/json")
                    .build()

                val pageJson = okHttpClient.newCall(apiReq).execute().use { apiResp ->
                    if (!apiResp.isSuccessful) break
                    apiResp.body?.string().orEmpty()
                }
                if (pageJson.isBlank()) break

                val page = runCatching {
                    FlexibleTrackParser.parseDeepStorageFilesPage(pageJson, shareInfo.apiOrigin)
                }.getOrNull() ?: break

                page.tracks.forEach { track ->
                    collected.putIfAbsent(track.streamUrl, track)
                }

                if (pageCount == 0) {
                    page.folders.forEach { folderName ->
                        val cleanName = folderName.trim('/')
                        if (cleanName.isNotEmpty() && !cleanName.contains("..")) {
                            val childPath = "$relPath$cleanName/"
                            if (visitedFolders.size < 30 && visitedFolders.add(childPath)) {
                                pendingFolders.add(childPath)
                            }
                        }
                    }
                }

                if (!page.hasMore || page.nextOffset <= offset) break
                offset = page.nextOffset
                pageCount++
            }
        }

        return collected.values.toList()
    }

    private fun formatValidationFailure(errors: List<String>): String {
        val first = errors.firstOrNull().orEmpty()
        return when {
            first.contains("text/html", ignoreCase = true) ||
                first.contains("HTML/text", ignoreCase = true) ->
                "The streaming URL opened a web share page rather than direct audio files. Host a catalog.json / tracks.txt manifest or direct audio links."
            first.contains("Access denied", ignoreCase = true) ->
                "Access to the streaming audio files was denied by the server."
            first.contains("not found", ignoreCase = true) ->
                "The listed audio files could not be found on the streaming server."
            first.isNotBlank() -> first
            else -> "No playable audio streams were found at ${AdminServerConfig.baseUrl}."
        }
    }

    private fun scheduleBackgroundEnrichment() {
        enrichmentJob?.cancel()
        enrichmentJob = enrichmentScope.launch {
            val pending = trackDao.getUnenrichedTracksOnce()
            if (pending.isEmpty()) return@launch
            val semaphore = Semaphore(1)
            coroutineScope {
                pending.map { track ->
                    async {
                        semaphore.withPermit {
                            val prevPriority = runCatching {
                                val p = android.os.Process.getThreadPriority(android.os.Process.myTid())
                                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                                p
                            }.getOrNull()
                            val enriched = try {
                                metadataExtractor.extractFromStream(track)
                            } finally {
                                if (prevPriority != null) {
                                    runCatching { android.os.Process.setThreadPriority(prevPriority) }
                                }
                            }
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
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() && it != existing.title }
        val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() && it != existing.artist }
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() && it != existing.album }
        val cleanGenre = genre?.trim()?.takeIf { it.isNotEmpty() && it != existing.genre }
        val newSampleRate = if (sampleRateHz > 0 && sampleRateHz != existing.sampleRateHz) sampleRateHz else 0
        val newBitDepth = if (bitDepth > 0 && bitDepth != existing.bitDepth) bitDepth else 0
        val newBitrate = if (bitrateKbps > 0 && bitrateKbps != existing.bitrateKbps) bitrateKbps else 0

        if (cleanTitle != null || cleanArtist != null || cleanAlbum != null ||
            cleanGenre != null || cachedArtUri != null || newSampleRate > 0 || newBitDepth > 0 || newBitrate > 0
        ) {
            trackDao.updateEnrichedMetadata(
                trackId = trackId,
                title = cleanTitle,
                artist = cleanArtist,
                album = cleanAlbum,
                genre = cleanGenre,
                albumArtUrl = cachedArtUri,
                durationMs = 0L,
                sampleRateHz = newSampleRate,
                bitDepth = newBitDepth,
                bitrateKbps = newBitrate,
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
