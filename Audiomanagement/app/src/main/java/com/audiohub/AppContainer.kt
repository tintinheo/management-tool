package com.audiohub

import android.app.Application
import androidx.room.Room
import com.audiohub.data.db.AppDatabase
import com.audiohub.data.lyrics.LyricsRepository
import com.audiohub.data.music.MusicRepository
import com.audiohub.data.music.MusicScanner
import com.audiohub.data.music.PlaylistRepository
import com.audiohub.data.music.StatsRepository
import com.audiohub.data.podcast.DownloadProgress
import com.audiohub.data.podcast.EpisodeDownloader
import com.audiohub.data.podcast.HttpClient
import com.audiohub.data.podcast.ItunesApi
import com.audiohub.data.podcast.LocalPodcastScanner
import com.audiohub.data.podcast.MediaStoreEpisodeStore
import com.audiohub.work.DownloadScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import com.audiohub.data.podcast.OkHttpHttpClient
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.playback.PlayerConnection
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired dependency graph; one instance per process, owned by [AudioHubApp]. */
class AppContainer(private val app: Application) {

    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "audiohub.db")
            // v1 only held the track cache (rebuilt by rescanning). From v2 on, user data lives here,
            // so every later version bump needs a real (or auto) migration.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .build()
    }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "http"), 20L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    val http: HttpClient by lazy { OkHttpHttpClient(okHttp) }

    val musicRepository: MusicRepository by lazy {
        MusicRepository(MusicScanner(app), database.trackDao(), settings)
    }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(app, database.playlistDao(), database.trackDao(), settings)
    }

    val statsRepository: StatsRepository by lazy { StatsRepository(database.statsDao()) }

    val lyricsRepository: LyricsRepository by lazy { LyricsRepository(app) }

    val podcastRepository: PodcastRepository by lazy {
        PodcastRepository(database.podcastDao(), http, ItunesApi(http), settings)
    }

    val downloadProgress = DownloadProgress()

    val episodeDownloader: EpisodeDownloader by lazy {
        EpisodeDownloader(database.podcastDao(), http, MediaStoreEpisodeStore(app), settings, downloadProgress)
    }

    val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler(app, database.podcastDao(), settings) }

    val localPodcastScanner: LocalPodcastScanner by lazy { LocalPodcastScanner(app, database.podcastDao(), settings) }

    /** A screen to open, set by incoming links (podcasts.apple.com, feed URLs) and consumed by the UI. */
    val pendingRoute = MutableStateFlow<String?>(null)

    val playerConnection: PlayerConnection by lazy { PlayerConnection(app) }

    companion object {
        // Some podcast hosts reject requests without a recognisable client name.
        const val USER_AGENT = "AudioHub/0.1 (Android; podcast player)"
    }
}
