package com.example.audio

import com.example.data.model.TrackEntity
import com.example.data.remote.InvalidAudioStreamException
import com.example.data.remote.StreamUrlResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class TempPrepStatus {
    IDLE,
    DOWNLOADING,
    CONVERTING,
    READY,
    FAILED
}

data class TempTrackState(
    val trackId: String,
    val status: TempPrepStatus = TempPrepStatus.IDLE,
    val progress: Float = 0f,
    val localUri: String? = null,
    val convertedToWav: Boolean = false,
    val errorMessage: String? = null
)

class EphemeralAudioCacheManager(
    private val tempDir: File,
    private val okHttpClient: OkHttpClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<TempTrackState>>()
    private val convertedFlags = ConcurrentHashMap<String, Boolean>()
    private val readyUriByStreamUrl = ConcurrentHashMap<String, String>()
    private var backgroundQueueJob: Job? = null

    private val _states = MutableStateFlow<Map<String, TempTrackState>>(emptyMap())
    val states: StateFlow<Map<String, TempTrackState>> = _states.asStateFlow()

    init {
        ensureTempDirReady()
    }

    fun getReadyUri(trackId: String): String? {
        val state = _states.value[trackId]
        if (state?.status == TempPrepStatus.READY && !state.localUri.isNullOrBlank()) {
            val file = File(java.net.URI(state.localUri))
            if (file.exists() && file.length() > 0L) {
                return state.localUri
            }
        }
        return null
    }

    fun getReadyLocalUriForUrl(streamUrl: String): String? {
        val uriStr = readyUriByStreamUrl[streamUrl] ?: return null
        val file = runCatching { File(java.net.URI(uriStr)) }.getOrNull() ?: return null
        return if (file.exists() && file.length() > 0L) uriStr else {
            readyUriByStreamUrl.remove(streamUrl)
            null
        }
    }

    suspend fun prepareTrack(track: TrackEntity): TempTrackState {
        ensureTempDirReady()
        val finalFile = finalTempFileFor(track)
        if (finalFile.exists() && finalFile.length() > 0L) {
            val localUri = fileUri(finalFile)
            readyUriByStreamUrl[track.streamUrl] = localUri
            val ready = TempTrackState(
                trackId = track.id,
                status = TempPrepStatus.READY,
                progress = 1f,
                localUri = localUri,
                convertedToWav = convertedFlags[track.id] == true
            )
            updateTrackState(ready)
            return ready
        }

        val deferred = inFlight.computeIfAbsent(track.id) {
            scope.async {
                try {
                    downloadAndPrepareInternal(track, finalFile)
                } finally {
                    inFlight.remove(track.id)
                }
            }
        }

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = TempTrackState(
                trackId = track.id,
                status = TempPrepStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Failed to prepare audio."
            )
            updateTrackState(failed)
            failed
        }
    }

    fun prepareQueueInBackground(
        tracks: List<TrackEntity>,
        startIndex: Int,
        onTrackReady: (index: Int, track: TrackEntity, state: TempTrackState) -> Unit
    ) {
        backgroundQueueJob?.cancel()
        if (tracks.size <= 1) return

        val orderedIndices = buildList {
            for (i in (startIndex + 1) until tracks.size) add(i)
            for (i in 0 until startIndex) add(i)
        }

        backgroundQueueJob = scope.launch {
            val semaphore = Semaphore(1)
            coroutineScope {
                orderedIndices.map { idx ->
                    async {
                        ensureActive()
                        semaphore.withPermit {
                            ensureActive()
                            val track = tracks[idx]
                            val result = prepareTrack(track)
                            if (result.status == TempPrepStatus.READY) {
                                onTrackReady(idx, track, result)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun cancelQueuePreparation() {
        backgroundQueueJob?.cancel()
        backgroundQueueJob = null
    }

    fun clearAll() {
        backgroundQueueJob?.cancel()
        backgroundQueueJob = null

        val activeDeferreds = inFlight.values.toList()
        inFlight.clear()
        activeDeferreds.forEach { it.cancel() }

        convertedFlags.clear()
        readyUriByStreamUrl.clear()
        _states.value = emptyMap()

        deleteTempContents()
    }

    private suspend fun downloadAndPrepareInternal(
        track: TrackEntity,
        finalFile: File
    ): TempTrackState {
        ensureTempDirReady()
        val partFile = File(tempDir, "${opaqueKey(track)}.part")
        partFile.delete()

        updateTrackState(
            TempTrackState(
                trackId = track.id,
                status = TempPrepStatus.DOWNLOADING,
                progress = 0f
            )
        )

        return try {
            downloadStreamToFile(track, partFile)
            val container = AudioByteConverter.sniffContainer(partFile)

            when {
                container == AudioContainerKind.INVALID_MARKUP_OR_EMPTY -> {
                    partFile.delete()
                    val failed = TempTrackState(
                        trackId = track.id,
                        status = TempPrepStatus.FAILED,
                        errorMessage = "Remote URL returned a web page or empty payload instead of audio bytes."
                    )
                    updateTrackState(failed)
                    failed
                }
                container.isDirectlySupportedByExoPlayer -> {
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    convertedFlags[track.id] = false
                    val localUri = fileUri(finalFile)
                    readyUriByStreamUrl[track.streamUrl] = localUri
                    val ready = TempTrackState(
                        trackId = track.id,
                        status = TempPrepStatus.READY,
                        progress = 1f,
                        localUri = localUri,
                        convertedToWav = false
                    )
                    updateTrackState(ready)
                    ready
                }
                else -> {
                    updateTrackState(
                        TempTrackState(
                            trackId = track.id,
                            status = TempPrepStatus.CONVERTING,
                            progress = 1f
                        )
                    )
                    val convertingFile = File(tempDir, "${opaqueKey(track)}.conv")
                    convertingFile.delete()
                    try {
                        AudioByteConverter.convertToWavPcm(partFile, convertingFile, container)
                        partFile.delete()
                        if (finalFile.exists()) finalFile.delete()
                        if (!convertingFile.renameTo(finalFile)) {
                            convertingFile.copyTo(finalFile, overwrite = true)
                            convertingFile.delete()
                        }
                        convertedFlags[track.id] = true
                        val localUri = fileUri(finalFile)
                        readyUriByStreamUrl[track.streamUrl] = localUri
                        val ready = TempTrackState(
                            trackId = track.id,
                            status = TempPrepStatus.READY,
                            progress = 1f,
                            localUri = localUri,
                            convertedToWav = true
                        )
                        updateTrackState(ready)
                        ready
                    } catch (e: Exception) {
                        convertingFile.delete()
                        partFile.delete()
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            partFile.delete()
            throw e
        } catch (e: Exception) {
            partFile.delete()
            val failed = TempTrackState(
                trackId = track.id,
                status = TempPrepStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Unable to download and prepare track."
            )
            updateTrackState(failed)
            failed
        }
    }

    private suspend fun downloadStreamToFile(track: TrackEntity, partFile: File) {
        val prevPriority = runCatching {
            val p = android.os.Process.getThreadPriority(android.os.Process.myTid())
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            p
        }.getOrNull()

        try {
            var targetUrl = track.streamUrl
            repeat(2) { attempt ->
                coroutineContext.ensureActive()
                val request = Request.Builder().url(targetUrl).get().build()
                val call = okHttpClient.newCall(request)
                val cancelJob = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Streaming server returned HTTP ${response.code}.")
                        }

                        val contentType = response.header("Content-Type").orEmpty()
                        val finalUrl = response.request.url.toString()
                        val peekBytes = runCatching { response.peekBody(512).bytes() }.getOrDefault(ByteArray(0))
                        val isMarkup = !StreamUrlResolver.isAudioMimeType(contentType) &&
                            (StreamUrlResolver.isNonAudioMime(contentType) ||
                                StreamUrlResolver.looksLikeTextMarkupOrJson(peekBytes))

                        if (isMarkup) {
                            val isHtml = contentType.contains("html", ignoreCase = true) ||
                                StreamUrlResolver.looksLikeTextMarkupOrJson(peekBytes)
                            if (attempt == 0 && isHtml) {
                                val html = runCatching { response.peekBody(65536).string() }.getOrDefault("")
                                val unwrapped = StreamUrlResolver.extractDirectAudioUrlFromHtml(html, finalUrl)
                                if (unwrapped != null && unwrapped != finalUrl) {
                                    targetUrl = unwrapped
                                    return@use
                                }
                            }
                            val typeLabel = contentType.ifBlank { "HTML/text data" }
                            throw InvalidAudioStreamException(
                                "Stream returned $typeLabel instead of audio bytes ($finalUrl)."
                            )
                        }

                        val body = response.body ?: throw IOException("Empty audio response body.")
                        val totalBytes = body.contentLength()
                        var downloadedBytes = 0L
                        var lastReportedBytes = 0L
                        var lastReportedProgress = -1f

                        body.byteStream().use { input ->
                            BufferedOutputStream(FileOutputStream(partFile), 64 * 1024).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!coroutineContext.isActive) {
                                        throw CancellationException("Download cancelled")
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    val progress = if (totalBytes > 0L) {
                                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    val bytesDelta = downloadedBytes - lastReportedBytes
                                    val pctDelta = progress - lastReportedProgress
                                    if (bytesDelta >= 512 * 1024 || (totalBytes > 0L && pctDelta >= 0.05f)) {
                                        lastReportedBytes = downloadedBytes
                                        lastReportedProgress = progress
                                        updateTrackState(
                                            TempTrackState(
                                                trackId = track.id,
                                                status = TempPrepStatus.DOWNLOADING,
                                                progress = progress
                                            )
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }
                        return
                    }
                } finally {
                    cancelJob?.dispose()
                }
            }
            throw InvalidAudioStreamException("Unable to resolve raw audio stream from ${track.streamUrl}.")
        } finally {
            if (prevPriority != null) {
                runCatching { android.os.Process.setThreadPriority(prevPriority) }
            }
        }
    }

    private fun updateTrackState(state: TempTrackState) {
        _states.update { current ->
            current + (state.trackId to state)
        }
    }

    private fun ensureTempDirReady() {
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        val noMedia = File(tempDir, ".nomedia")
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
        }
    }

    private fun deleteTempContents() {
        runCatching {
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { child ->
                    if (child.name != ".nomedia") {
                        child.deleteRecursively()
                    }
                }
            }
        }
    }

    private fun finalTempFileFor(track: TrackEntity): File {
        return File(tempDir, "${opaqueKey(track)}.tmp")
    }

    private fun opaqueKey(track: TrackEntity): String {
        val raw = "${track.id}|${track.streamUrl}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "aet_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun fileUri(file: File): String = "file://${file.absolutePath}"
}
