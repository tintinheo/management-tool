package com.audiohub.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.audiohub.data.SortField
import com.audiohub.data.SortOrder
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
    /** Order of song lists (Songs, folder playlists). */
    val songSort: SortOrder = SettingsRepository.DEFAULT_SONG_SORT,
    /** Order of episode lists (show pages, Downloaded). */
    val episodeSort: SortOrder = SettingsRepository.DEFAULT_EPISODE_SORT,
    /** Trim silent gaps in podcast episodes. */
    val skipSilence: Boolean = false,
    /** Equalizer on/off, preset index (-1 = custom) and custom band levels in millibels. */
    val eqEnabled: Boolean = false,
    val eqPreset: Int = 0,
    val eqBands: List<Int> = emptyList(),
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
        val SONG_SORT = stringPreferencesKey("song_sort")
        val EPISODE_SORT = stringPreferencesKey("episode_sort")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = intPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
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
            songSort = SortOrder.decode(p[Keys.SONG_SORT], DEFAULT_SONG_SORT),
            episodeSort = SortOrder.decode(p[Keys.EPISODE_SORT], DEFAULT_EPISODE_SORT),
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            eqEnabled = p[Keys.EQ_ENABLED] ?: false,
            eqPreset = p[Keys.EQ_PRESET] ?: 0,
            eqBands = p[Keys.EQ_BANDS]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty(),
        )
    }

    suspend fun setDownloadWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    suspend fun setSongSort(order: SortOrder) {
        context.dataStore.edit { it[Keys.SONG_SORT] = order.encode() }
    }

    suspend fun setEpisodeSort(order: SortOrder) {
        context.dataStore.edit { it[Keys.EPISODE_SORT] = order.encode() }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_SILENCE] = enabled }
    }

    suspend fun setEqualizer(enabled: Boolean, preset: Int, bands: List<Int>) {
        context.dataStore.edit {
            it[Keys.EQ_ENABLED] = enabled
            it[Keys.EQ_PRESET] = preset
            it[Keys.EQ_BANDS] = bands.joinToString(",")
        }
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
        val DEFAULT_SONG_SORT = SortOrder(SortField.TITLE)
        val DEFAULT_EPISODE_SORT = SortOrder(SortField.DATE_ADDED, descending = true)

        fun defaultCountry(): String =
            Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"
    }
}
