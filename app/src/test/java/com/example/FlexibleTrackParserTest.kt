package com.example

import com.example.data.remote.FlexibleTrackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleTrackParserTest {

    @Test
    fun `resolves subpath URLs cleanly without stripping testappuser path`() {
        val base = "https://deepstorage.dev/testappuser"
        assertEquals(
            "https://deepstorage.dev/testappuser/catalog.json",
            FlexibleTrackParser.resolveUrl(base, "/catalog.json")
        )
        assertEquals(
            "https://deepstorage.dev/testappuser/song.flac",
            FlexibleTrackParser.resolveUrl(base, "song.flac")
        )
        assertEquals(
            "https://deepstorage.dev/testappuser/Album/01%20-%20So%20What.flac",
            FlexibleTrackParser.resolveUrl(base, "Album/01 - So What.flac")
        )
        assertEquals(
            "https://cdn.example.com/other.flac",
            FlexibleTrackParser.resolveUrl(base, "https://cdn.example.com/other.flac")
        )
    }

    @Test
    fun `parses filename and subfolder metadata from direct deepstorage URLs`() {
        val track1 = FlexibleTrackParser.buildTrackFromUrl(
            rawUrlOrFilename = "01 - Miles Davis - So What (24bit 96kHz).flac",
            baseUrl = "https://deepstorage.dev/testappuser",
            index = 0
        )!!
        assertEquals("https://deepstorage.dev/testappuser/01%20-%20Miles%20Davis%20-%20So%20What%20(24bit%2096kHz).flac", track1.streamUrl)
        assertEquals(1, track1.trackNumber)
        assertEquals("Miles Davis", track1.artist)
        assertEquals("So What", track1.title)
        assertEquals("FLAC", track1.format)
        assertEquals(24, track1.bitDepth)
        assertEquals(96000, track1.sampleRateHz)
        assertTrue(track1.isHiRes)

        val track2 = FlexibleTrackParser.buildTrackFromUrl(
            rawUrlOrFilename = "Daft Punk/Random Access Memories/08 - Get Lucky.flac",
            baseUrl = "https://deepstorage.dev/testappuser",
            index = 1
        )!!
        assertEquals("Daft Punk", track2.artist)
        assertEquals("Random Access Memories", track2.album)
        assertEquals(8, track2.trackNumber)
        assertEquals("Get Lucky", track2.title)
    }

    @Test
    fun `parses string array JSON, M3U playlists, plain text lists, and HTML directory links`() {
        val base = "https://deepstorage.dev/testappuser"

        val jsonList = """
            {
              "tracks": [
                "01 - Steely Dan - Aja.flac",
                "https://deepstorage.dev/testappuser/02 - Pink Floyd - Time.flac"
              ]
            }
        """.trimIndent()
        val jsonTracks = FlexibleTrackParser.parseCatalogPayload(jsonList, "application/json", base)
        assertEquals(2, jsonTracks.size)
        assertEquals("Steely Dan", jsonTracks[0].artist)
        assertEquals("Aja", jsonTracks[0].title)
        assertEquals("Pink Floyd", jsonTracks[1].artist)

        val m3u = """
            #EXTM3U
            #EXTINF:325,Hans Zimmer - Time
            01_time.flac
        """.trimIndent()
        val m3uTracks = FlexibleTrackParser.parseCatalogPayload(m3u, "audio/x-mpegurl", base)
        assertEquals(1, m3uTracks.size)
        assertEquals("Hans Zimmer", m3uTracks[0].artist)
        assertEquals("Time", m3uTracks[0].title)
        assertEquals(325000L, m3uTracks[0].durationMs)

        val txt = """
            # Public Hi-Res Catalog
            track_one.flac
            track_two.mp3
            readme.txt
        """.trimIndent()
        val txtTracks = FlexibleTrackParser.parseCatalogPayload(txt, "text/plain", base)
        assertEquals(2, txtTracks.size)
        assertEquals("FLAC", txtTracks[0].format)
        assertEquals("MP3", txtTracks[1].format)

        val html = """
            <html><body>
              <a href="../">Parent</a>
              <a href="03%20-%20Norah%20Jones%20-%20Come%20Away%20With%20Me.flac">track</a>
            </body></html>
        """.trimIndent()
        val htmlTracks = FlexibleTrackParser.parseCatalogPayload(html, "text/html", base)
        assertEquals(1, htmlTracks.size)
        assertEquals("Norah Jones", htmlTracks[0].artist)
        assertEquals("Come Away With Me", htmlTracks[0].title)
    }

    @Test
    fun `parses wrapped tracks JSON with relative URLs and Hi-Res FLAC metadata`() {
        val json = """
            {
              "tracks": [
                {
                  "id": "flac-1",
                  "title": "So What",
                  "artist": "Miles Davis",
                  "album": "Kind of Blue",
                  "albumArtUrl": "/art/kob.jpg",
                  "streamUrl": "/streams/so_what.flac",
                  "format": "flac",
                  "sampleRateHz": 96000,
                  "bitDepth": 24,
                  "bitrateKbps": 2950,
                  "durationMs": 562000,
                  "trackNumber": 1,
                  "genre": "Jazz"
                },
                {
                  "id": "mp3-2",
                  "title": "Demo Track",
                  "stream_url": "https://cdn.example.com/audio/demo.mp3",
                  "sample_rate": 44100,
                  "duration": 185
                }
              ]
            }
        """.trimIndent()

        val tracks = FlexibleTrackParser.parseTracks(
            rawJson = json,
            baseUrl = "https://music.myserver.dev/"
        )

        assertEquals(2, tracks.size)

        val flac = tracks[0]
        assertEquals("So What", flac.title)
        assertEquals("Jazz", flac.genre)
        assertEquals("https://music.myserver.dev/streams/so_what.flac", flac.streamUrl)
        assertEquals("https://music.myserver.dev/art/kob.jpg", flac.albumArtUrl)
        assertEquals("FLAC", flac.format)
        assertEquals(96000, flac.sampleRateHz)
        assertEquals(24, flac.bitDepth)
        assertTrue(flac.isHiRes)
        assertEquals("FLAC 24/96", flac.shortFormatBadge)

        val mp3 = tracks[1]
        assertEquals("MP3", mp3.format)
        assertEquals(185000L, mp3.durationMs)
        assertFalse(mp3.isHiRes)
    }

    @Test
    fun `parses root array payload cleanly`() {
        val json = """
            [
              {
                "id": "10",
                "title": "Aja",
                "artist": "Steely Dan",
                "album": "Aja",
                "url": "/files/aja.flac",
                "sampleRate": 192,
                "bitDepth": 24
              }
            ]
        """.trimIndent()

        val tracks = FlexibleTrackParser.parseTracks(json, "http://192.168.1.50:8080")
        assertEquals(1, tracks.size)
        assertEquals(192000, tracks[0].sampleRateHz)
        assertEquals("http://192.168.1.50:8080/files/aja.flac", tracks[0].streamUrl)
        assertTrue(tracks[0].isHiRes)
    }

    @Test
    fun `does not double subpath and unescapes HTML entities and embedded script audio URLs`() {
        val base = "https://deepstorage.dev/testappuser"
        assertEquals(
            "https://deepstorage.dev/testappuser/song.flac",
            FlexibleTrackParser.resolveUrl(base, "/testappuser/song.flac")
        )

        val htmlWithRootPathsAndEntities = """
            <html><body>
              <a href="/testappuser/01%20-%20Miles%20Davis%20-%20So%20What.flac?download=1&amp;v=2">Track 1</a>
              <a href="/api/raw/track2.mp3">Track 2</a>
              <audio src="/media/track3.ogg"></audio>
              <a href="/testappuser/unsupported.dsf">Unsupported DSD</a>
            </body></html>
        """.trimIndent()
        val htmlTracks = FlexibleTrackParser.parseCatalogPayload(htmlWithRootPathsAndEntities, "text/html", base)
        assertEquals(3, htmlTracks.size)
        assertEquals(
            "https://deepstorage.dev/testappuser/01%20-%20Miles%20Davis%20-%20So%20What.flac?download=1&v=2",
            htmlTracks[0].streamUrl
        )
        assertEquals("https://deepstorage.dev/api/raw/track2.mp3", htmlTracks[1].streamUrl)
        assertEquals("https://deepstorage.dev/media/track3.ogg", htmlTracks[2].streamUrl)

        val spaHtml = """
            <!DOCTYPE html>
            <html><body>
              <div id="__next"></div>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"files":["https:\/\/deepstorage.dev\/testappuser\/embedded_track.flac?token=1\u0026raw=1"]}}
              </script>
            </body></html>
        """.trimIndent()
        val spaTracks = FlexibleTrackParser.parseCatalogPayload(spaHtml, "text/html", base)
        assertEquals(1, spaTracks.size)
        assertEquals(
            "https://deepstorage.dev/testappuser/embedded_track.flac?token=1&raw=1",
            spaTracks[0].streamUrl
        )

        val xmlError = """<?xml version="1.0"?><Error><Code>AccessDenied</Code><Message>bad.flac</Message></Error>"""
        val xmlTracks = FlexibleTrackParser.parseCatalogPayload(xmlError, "application/xml", base)
        assertTrue(xmlTracks.isEmpty())
    }

    @Test
    fun `ignores SPA HTML when allowHtmlFallback is false and extracts download-attr or script JSON tracks`() {
        val base = "https://share.deepstorage.dev/testappuser"
        val spaShell = """<!DOCTYPE html><html><body><a href="/testappuser/fake.flac">fake</a></body></html>"""

        assertTrue(
            FlexibleTrackParser.parseCatalogPayload(
                rawPayload = spaShell,
                contentType = "text/html; charset=utf-8",
                baseUrl = base,
                allowHtmlFallback = false
            ).isEmpty()
        )

        val downloadAttrHtml = """
            <html><body>
              <a href="/api/v1/download/abc123" download="01 - Massive Attack - Teardrop.flac">Download</a>
            </body></html>
        """.trimIndent()
        val attrTracks = FlexibleTrackParser.parseCatalogPayload(downloadAttrHtml, "text/html", base)
        assertEquals(1, attrTracks.size)
        assertEquals("https://share.deepstorage.dev/api/v1/download/abc123", attrTracks[0].streamUrl)
        assertEquals("Massive Attack", attrTracks[0].artist)
        assertEquals("Teardrop", attrTracks[0].title)
        assertEquals("FLAC", attrTracks[0].format)

        val hydratedJsonHtml = """
            <html><body>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"files":[{"id":"f1","name":"Miles Davis - Blue in Green.flac","downloadUrl":"https://cdn.deepstorage.dev/blob/f1"}]}}
              </script>
            </body></html>
        """.trimIndent()
        val jsonTracks = FlexibleTrackParser.parseCatalogPayload(hydratedJsonHtml, "text/html", base)
        assertEquals(1, jsonTracks.size)
        assertEquals("https://cdn.deepstorage.dev/blob/f1", jsonTracks[0].streamUrl)
        assertEquals("FLAC", jsonTracks[0].format)
    }

    @Test
    fun `detects DeepStorage public share HTML and parses files, folders, and pagination`() {
        val shareHtml = """
            <!DOCTYPE html>
            <html><body>
              <div id="fileGrid"></div>
              <script>const PAGE_USERNAME = "testappuser";</script>
              <script src="/assets/public_page.js?v=5"></script>
            </body></html>
        """.trimIndent()

        val shareInfo = FlexibleTrackParser.extractDeepStoragePublicShareInfo(
            html = shareHtml,
            pageUrl = "https://share.deepstorage.dev/testappuser"
        )
        assertEquals("https://share.deepstorage.dev", shareInfo?.apiOrigin)
        assertEquals("testappuser", shareInfo?.username)

        val apiJson = """
            {
              "files": [
                {
                  "_id": "6b07351a6e490c34bb1b8ee3",
                  "username": "testappuser",
                  "file_link": "https://share.deepstorage.dev/2faa2511/Tera%20Mera%20Rishta%20-%20Rain%20Version%20%5B320%5D.mp3",
                  "folder_path": "/Shareable Folder/",
                  "file_name": "Tera Mera Rishta - Rain Version [320].mp3",
                  "file_size": 15034751
                },
                {
                  "_id": "keep-file",
                  "file_link": "https://share.deepstorage.dev/abc/.keep",
                  "folder_path": "/Shareable Folder/",
                  "file_name": ".keep"
                },
                {
                  "_id": "cover-img",
                  "file_link": "https://share.deepstorage.dev/def/cover.jpg",
                  "folder_path": "/Shareable Folder/Live Sessions/",
                  "file_name": "cover.jpg"
                },
                {
                  "_id": "flac-2",
                  "file_link": "https://share.deepstorage.dev/ghi/01%20-%20So%20What.flac",
                  "folder_path": "/Shareable Folder/Live Sessions/",
                  "file_name": "01 - So What.flac"
                }
              ],
              "folders": ["Live Sessions", "HiRes"],
              "next_offset": 4,
              "has_more": true
            }
        """.trimIndent()

        val page = FlexibleTrackParser.parseDeepStorageFilesPage(apiJson, "https://share.deepstorage.dev")
        assertEquals(2, page.tracks.size)
        assertEquals("MP3", page.tracks[0].format)
        assertEquals(
            "https://share.deepstorage.dev/2faa2511/Tera%20Mera%20Rishta%20-%20Rain%20Version%20%5B320%5D.mp3",
            page.tracks[0].streamUrl
        )
        assertEquals("FLAC", page.tracks[1].format)
        assertEquals("Live Sessions", page.tracks[1].album)
        assertEquals(listOf("Live Sessions", "HiRes"), page.folders)
        assertTrue(page.hasMore)
        assertEquals(4, page.nextOffset)
    }
}

