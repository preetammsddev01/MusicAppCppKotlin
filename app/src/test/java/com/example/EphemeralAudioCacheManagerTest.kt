package com.example

import com.example.audio.AudioByteConverter
import com.example.audio.AudioContainerKind
import com.example.audio.EphemeralAudioCacheManager
import com.example.audio.TempPrepStatus
import com.example.data.model.TrackEntity
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class EphemeralAudioCacheManagerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("aether_cache_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun buildAiffBytes(pcmSamplesBigEndian: ByteArray, sampleRateHz: Int = 44100): ByteArray {
        val commSize = 18
        val ssndSize = 8 + pcmSamplesBigEndian.size
        val formSize = 4 + (8 + commSize) + (8 + ssndSize)
        val out = ByteArrayOutputStream()
        out.write("FORM".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(formSize).array())
        out.write("AIFF".toByteArray(Charsets.US_ASCII))

        out.write("COMM".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(commSize).array())
        out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(2).array()) // channels
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(pcmSamplesBigEndian.size / 4).array())
        out.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(16).array()) // bitsPerSample
        // 80-bit IEEE extended 44100 Hz: 0x400E AC44 0000 0000 0000
        out.write(
            byteArrayOf(
                0x40, 0x0E,
                0xAC.toByte(), 0x44,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
        )

        out.write("SSND".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ssndSize).array())
        out.write(ByteArray(8)) // offset + blockSize = 0
        out.write(pcmSamplesBigEndian)
        return out.toByteArray()
    }

    @Test
    fun `sniffContainerBytes classifies native, AIFF, and HTML payloads accurately`() {
        assertEquals(
            AudioContainerKind.NATIVE_FLAC,
            AudioByteConverter.sniffContainerBytes("fLaC\u0000\u0000\u0000\u0022".toByteArray())
        )
        assertEquals(
            AudioContainerKind.NATIVE_MP3,
            AudioByteConverter.sniffContainerBytes("ID3\u0003\u0000\u0000\u0000\u0000".toByteArray())
        )
        assertEquals(
            AudioContainerKind.UNSUPPORTED_AIFF,
            AudioByteConverter.sniffContainerBytes("FORM\u0000\u0000\u0010\u0000AIFF".toByteArray())
        )
        assertEquals(
            AudioContainerKind.INVALID_MARKUP_OR_EMPTY,
            AudioByteConverter.sniffContainerBytes("<!DOCTYPE html><html></html>".toByteArray())
        )
    }

    @Test
    fun `cache manager keeps native FLAC with opaque temp filename, converts AIFF to WAV PCM, and deletes all on clearAll`() = runTest {
        val flacPayload = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0, 0, 0, 34, 1, 2, 3, 4)
        // Two 16-bit big-endian samples: 0x1234, 0x5678 -> in little-endian WAV: 0x34 0x12, 0x78 0x56
        val aiffPcm = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x01, 0x02, 0x03, 0x04)
        val aiffPayload = buildAiffBytes(aiffPcm)

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val (code, mime, bytes) = when {
                    url.endsWith("native.flac") -> Triple(200, "audio/flac", flacPayload)
                    url.endsWith("track.aiff") -> Triple(200, "application/octet-stream", aiffPayload)
                    else -> Triple(200, "text/html", "<html><body>Not audio</body></html>".toByteArray())
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .header("Content-Type", mime)
                    .body(bytes.toResponseBody(mime.toMediaType()))
                    .build()
            })
            .build()

        val manager = EphemeralAudioCacheManager(tempDir, client)
        assertTrue(File(tempDir, ".nomedia").exists())

        val flacTrack = TrackEntity(
            id = "trk_flac",
            title = "Secret Track Title",
            artist = "Miles Davis",
            album = "Kind of Blue",
            albumArtUrl = null,
            streamUrl = "https://deepstorage.dev/testappuser/native.flac",
            format = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24,
            bitrateKbps = 2500,
            durationMs = 10000L
        )
        val aiffTrack = flacTrack.copy(
            id = "trk_aiff",
            title = "AIFF Studio Master",
            streamUrl = "https://deepstorage.dev/testappuser/track.aiff",
            format = "AIFF"
        )
        val htmlTrack = flacTrack.copy(
            id = "trk_html",
            title = "Broken Web Page",
            streamUrl = "https://deepstorage.dev/testappuser/broken.flac"
        )

        val flacState = manager.prepareTrack(flacTrack)
        assertEquals(TempPrepStatus.READY, flacState.status)
        assertFalse(flacState.convertedToWav)
        assertEquals(flacState.localUri, manager.getReadyLocalUriForUrl(flacTrack.streamUrl))
        val flacFile = File(java.net.URI(flacState.localUri!!))
        assertTrue(flacFile.exists())
        assertTrue(flacFile.name.endsWith(".tmp"))
        assertFalse(flacFile.name.contains("Secret", ignoreCase = true))
        assertArrayEquals(flacPayload, flacFile.readBytes())

        val aiffState = manager.prepareTrack(aiffTrack)
        assertEquals(TempPrepStatus.READY, aiffState.status)
        assertTrue(aiffState.convertedToWav)
        assertEquals(aiffState.localUri, manager.getReadyLocalUriForUrl(aiffTrack.streamUrl))
        val wavFile = File(java.net.URI(aiffState.localUri!!))
        assertTrue(wavFile.exists())
        val wavBytes = wavFile.readBytes()
        assertEquals("RIFF", String(wavBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wavBytes, 8, 4, Charsets.US_ASCII))
        assertEquals(44 + aiffPcm.size, wavBytes.size)
        // Verify big-endian to little-endian sample byte swap
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0x78, 0x56, 0x02, 0x01, 0x04, 0x03),
            wavBytes.copyOfRange(44, 44 + aiffPcm.size)
        )

        val htmlState = manager.prepareTrack(htmlTrack)
        assertEquals(TempPrepStatus.FAILED, htmlState.status)

        // Closing app / clearing cache removes all .tmp audio files while keeping .nomedia
        manager.clearAll()
        assertEquals(null, manager.getReadyLocalUriForUrl(flacTrack.streamUrl))
        assertFalse(flacFile.exists())
        assertFalse(wavFile.exists())
        val remainingAudioFiles = tempDir.listFiles()?.filter { it.name != ".nomedia" }.orEmpty()
        assertTrue(remainingAudioFiles.isEmpty())
    }
}
