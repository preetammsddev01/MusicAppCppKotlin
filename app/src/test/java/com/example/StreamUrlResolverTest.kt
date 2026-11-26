package com.example

import com.example.data.remote.AudioStreamGuardInterceptor
import com.example.data.remote.InvalidAudioStreamException
import com.example.data.remote.StreamCheckResult
import com.example.data.remote.StreamUrlResolver
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlResolverTest {

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
    }

    private fun response(
        request: Request,
        code: Int,
        contentType: String,
        bodyBytes: ByteArray = ByteArray(0)
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "ERR")
            .header("Content-Type", contentType)
            .body(bodyBytes.toResponseBody(contentType.toMediaType()))
            .build()
    }

    @Test
    fun `HEAD returning audio mime succeeds immediately`() {
        val client = fakeClient { req ->
            response(req, 200, "audio/flac")
        }
        val result = StreamUrlResolver.verifyOrResolveAudioUrl(client, "https://deepstorage.dev/testappuser/song.flac")
        assertTrue(result is StreamCheckResult.Playable)
        assertEquals("https://deepstorage.dev/testappuser/song.flac", (result as StreamCheckResult.Playable).resolvedUrl)
    }

    @Test
    fun `falls back to ranged GET and unwraps HTML viewer audio tag`() {
        val client = fakeClient { req ->
            val url = req.url.toString()
            when {
                req.method == "HEAD" -> response(req, 405, "text/plain")
                url == "https://deepstorage.dev/testappuser/song.flac" -> {
                    val html = """<html><body><audio src="/raw/song.flac"></audio></body></html>"""
                    response(req, 200, "text/html", html.toByteArray())
                }
                url == "https://deepstorage.dev/raw/song.flac" -> {
                    val flacMagic = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0, 0, 0, 34)
                    response(req, 206, "application/octet-stream", flacMagic)
                }
                else -> response(req, 404, "text/html", "<html>Not Found</html>".toByteArray())
            }
        }
        val result = StreamUrlResolver.verifyOrResolveAudioUrl(client, "https://deepstorage.dev/testappuser/song.flac")
        assertTrue(result is StreamCheckResult.Playable)
        assertEquals("https://deepstorage.dev/raw/song.flac", (result as StreamCheckResult.Playable).resolvedUrl)
    }

    @Test
    fun `rejects unresolvable HTML SPA page instead of passing HTML to ExoPlayer`() {
        val client = fakeClient { req ->
            response(req, 200, "text/html", "<!DOCTYPE html><html><body>SPA</body></html>".toByteArray())
        }
        val result = StreamUrlResolver.verifyOrResolveAudioUrl(client, "https://deepstorage.dev/testappuser/missing.flac")
        assertTrue(result is StreamCheckResult.Invalid)
    }

    @Test(expected = InvalidAudioStreamException::class)
    fun `AudioStreamGuardInterceptor rejects HTML body with InvalidAudioStreamException`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(AudioStreamGuardInterceptor())
            .addInterceptor(Interceptor { chain ->
                response(chain.request(), 200, "application/octet-stream", "<!DOCTYPE html><html></html>".toByteArray())
            })
            .build()

        client.newCall(Request.Builder().url("https://deepstorage.dev/testappuser/bad.flac").build()).execute()
    }

    @Test
    fun `AudioStreamGuardInterceptor passes valid binary FLAC header through`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(AudioStreamGuardInterceptor())
            .addInterceptor(Interceptor { chain ->
                val flacMagic = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
                response(chain.request(), 200, "application/octet-stream", flacMagic)
            })
            .build()

        client.newCall(Request.Builder().url("https://deepstorage.dev/testappuser/good.flac").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("fLaC", resp.body?.string())
        }
    }

    @Test
    fun `unwraps JSON downloadUrl inside HTML viewer page`() {
        val client = fakeClient { req ->
            val url = req.url.toString()
            when {
                req.method == "HEAD" -> response(req, 405, "text/plain")
                url == "https://share.deepstorage.dev/testappuser/track.flac" -> {
                    val html = """<html><script>{"downloadUrl":"https://cdn.deepstorage.dev/raw/blob_99"}</script></html>"""
                    response(req, 200, "text/html; charset=utf-8", html.toByteArray())
                }
                url == "https://cdn.deepstorage.dev/raw/blob_99" -> {
                    val flacMagic = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
                    response(req, 206, "audio/flac", flacMagic)
                }
                else -> response(req, 404, "text/html")
            }
        }
        val result = StreamUrlResolver.verifyOrResolveAudioUrl(client, "https://share.deepstorage.dev/testappuser/track.flac")
        assertTrue(result is StreamCheckResult.Playable)
        assertEquals("https://cdn.deepstorage.dev/raw/blob_99", (result as StreamCheckResult.Playable).resolvedUrl)
    }

    @Test
    fun `tries raw=1 on originalUrl when share page redirects back to folder URL`() {
        val client = fakeClient { req ->
            val url = req.url.toString()
            when {
                req.method == "HEAD" -> response(req, 405, "text/plain")
                url == "https://share.deepstorage.dev/testappuser/track.flac" -> {
                    val redirectedReq = req.newBuilder()
                        .url("https://share.deepstorage.dev/testappuser")
                        .build()
                    response(redirectedReq, 200, "text/html; charset=utf-8", "<html><body>Share</body></html>".toByteArray())
                }
                url == "https://share.deepstorage.dev/testappuser/track.flac?raw=1" -> {
                    val flacMagic = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
                    response(req, 206, "audio/flac", flacMagic)
                }
                else -> response(req, 404, "text/html", "<html>Not Found</html>".toByteArray())
            }
        }
        val result = StreamUrlResolver.verifyOrResolveAudioUrl(client, "https://share.deepstorage.dev/testappuser/track.flac")
        assertTrue(result is StreamCheckResult.Playable)
        assertEquals("https://share.deepstorage.dev/testappuser/track.flac?raw=1", (result as StreamCheckResult.Playable).resolvedUrl)
    }
}
