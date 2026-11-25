package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.audio.AudioDspController
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.remote.AuthInterceptor
import com.example.data.remote.CloudMusicRepository
import com.example.playback.PlaybackController
import com.example.playback.PlaybackTelemetryBus
import com.example.ui.util.AlbumArtPaletteExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class AetherAudioApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var okHttpClient: OkHttpClient
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var settingsDataStore: SettingsDataStore
        private set
    lateinit var cloudRepository: CloudMusicRepository
        private set
    lateinit var audioDspController: AudioDspController
        private set
    lateinit var telemetryBus: PlaybackTelemetryBus
        private set
    lateinit var playbackController: PlaybackController
        private set
    lateinit var sharedImageLoader: ImageLoader
        private set
    lateinit var paletteExtractor: AlbumArtPaletteExtractor
        private set

    override fun onCreate() {
        super.onCreate()
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        database = AppDatabase.getInstance(this)
        settingsDataStore = SettingsDataStore(this)
        cloudRepository = CloudMusicRepository(
            context = this,
            trackDao = database.trackDao(),
            playlistDao = database.playlistDao(),
            okHttpClient = okHttpClient
        )

        audioDspController = AudioDspController()
        telemetryBus = PlaybackTelemetryBus()
        playbackController = PlaybackController(this)

        sharedImageLoader = ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()

        paletteExtractor = AlbumArtPaletteExtractor(this, sharedImageLoader)

        appScope.launch {
            val eqState = settingsDataStore.equalizerStateFlow.first()
            audioDspController.applyState(eqState)
        }
    }

    override fun newImageLoader(): ImageLoader = sharedImageLoader
}
