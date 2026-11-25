package com.example.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.AetherAudioApp
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        val app = application as AetherAudioApp
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(app.okHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(app.audioDspController.nativeAudioProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                app.telemetryBus.updateAudioSessionId(audioSessionId)
                app.audioDspController.attachToAudioSession(audioSessionId)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val trackId = player.currentMediaItem?.mediaId ?: return
                serviceScope.launch {
                    app.cloudRepository.enrichTrackFromPlayback(
                        trackId = trackId,
                        title = mediaMetadata.title?.toString(),
                        artist = (mediaMetadata.artist ?: mediaMetadata.albumArtist)?.toString(),
                        album = mediaMetadata.albumTitle?.toString(),
                        genre = mediaMetadata.genre?.toString(),
                        artworkBytes = mediaMetadata.artworkData
                    )
                }
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
            ) {
                val bitDepth = pcmEncodingToBitDepth(format.pcmEncoding)
                app.telemetryBus.updateFormat(
                    mimeType = format.sampleMimeType ?: format.containerMimeType,
                    sampleRateHz = format.sampleRate,
                    bitDepth = bitDepth,
                    channelCount = format.channelCount,
                    bitrateBps = format.bitrate
                )
                val trackId = player.currentMediaItem?.mediaId ?: return
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                if (format.sampleRate > 0 || bitDepth > 0 || bitrateKbps > 0) {
                    serviceScope.launch {
                        app.cloudRepository.enrichTrackFromPlayback(
                            trackId = trackId,
                            title = null,
                            artist = null,
                            album = null,
                            genre = null,
                            artworkBytes = null,
                            sampleRateHz = format.sampleRate.coerceAtLeast(0),
                            bitDepth = bitDepth,
                            bitrateKbps = bitrateKbps
                        )
                    }
                }
            }
        })

        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            app.telemetryBus.updateAudioSessionId(player.audioSessionId)
            app.audioDspController.attachToAudioSession(player.audioSessionId)
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel)
                .build()
        )

        exoPlayer = player
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        val app = application as AetherAudioApp
        app.audioDspController.releaseEffects()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.playback_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controls for active Hi-Res FLAC background playback"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun pcmEncodingToBitDepth(pcmEncoding: Int): Int = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "aether_hires_playback"
    }
}
