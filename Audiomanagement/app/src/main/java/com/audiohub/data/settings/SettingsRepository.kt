package com.audiohub.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val onboardingDone: Boolean,
    /** Relative to shared storage root, e.g. "Music/AudioHub/". */
    val musicFolder: String,
    /** Relative to shared storage root, e.g. "Podcasts/AudioHub/". */
    val podcastFolder: String,
    /** Two-letter Apple Podcasts storefront used for search and charts, e.g. "vn". */
    val podcastCountry: String,
    /** Playback speed applied to podcast episodes (music always plays at 1×). */
    val podcastSpeed: Float,
    /** Only download episodes on unmetered (Wi-Fi) networks. */
    val downloadWifiOnly: Boolean = true,
    /** Delete a downloaded episode once it has been played. */
    val deletePlayedDownloads: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val MUSIC_FOLDER = stringPreferencesKey("music_folder")
        val PODCAST_FOLDER = stringPreferencesKey("podcast_folder")
        val PODCAST_COUNTRY = stringPreferencesKey("podcast_country")
        val PODCAST_SPEED = floatPreferencesKey("podcast_speed")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val DELETE_PLAYED = booleanPreferencesKey("delete_played_downloads")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            musicFolder = p[Keys.MUSIC_FOLDER] ?: AudioFolders.DEFAULT_MUSIC,
            podcastFolder = p[Keys.PODCAST_FOLDER] ?: AudioFolders.DEFAULT_PODCAST,
            podcastCountry = p[Keys.PODCAST_COUNTRY] ?: defaultCountry(),
            podcastSpeed = p[Keys.PODCAST_SPEED] ?: 1f,
            downloadWifiOnly = p[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
            deletePlayedDownloads = p[Keys.DELETE_PLAYED] ?: true,
        )
    }

    suspend fun setDownloadWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    suspend fun setDeletePlayedDownloads(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DELETE_PLAYED] = enabled }
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setMusicFolder(relativePath: String) {
        context.dataStore.edit { it[Keys.MUSIC_FOLDER] = AudioFolders.normalize(relativePath) }
    }

    suspend fun setPodcastFolder(relativePath: String) {
        context.dataStore.edit { it[Keys.PODCAST_FOLDER] = AudioFolders.normalize(relativePath) }
    }

    suspend fun setPodcastCountry(country: String) {
        context.dataStore.edit { it[Keys.PODCAST_COUNTRY] = country.lowercase() }
    }

    suspend fun setPodcastSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.PODCAST_SPEED] = speed.coerceIn(0.5f, 3f) }
    }

    companion object {
        val PODCAST_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)

        fun defaultCountry(): String =
            Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"
    }
}
