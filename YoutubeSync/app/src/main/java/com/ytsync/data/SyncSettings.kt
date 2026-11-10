package com.ytsync.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ytsync.youtube.VideoMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    /** Ask YouTube to mark uploads 18+ (contentRating.ytRating). Best effort — see README. */
    val ageRestrict: Boolean = true,
    /** Only camera videos added after this epoch-second are synced (set when sync is first enabled). */
    val sinceEpochSec: Long = 0,
    /** True once the YouTube scope was granted; false again if Google asks the user to re-consent. */
    val connected: Boolean = false,
    /** Title template, see [com.ytsync.youtube.VideoMetadata.TOKENS]. */
    val titleTemplate: String = VideoMetadata.DEFAULT_TEMPLATE,
    val channelTitle: String = "",
)

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val ageRestrict = booleanPreferencesKey("age_restrict")
        val since = longPreferencesKey("since_epoch_sec")
        val connected = booleanPreferencesKey("connected")
        /** 1.0.0–1.0.2 stored a plain prefix here; converted to a template on read. */
        val legacyTitlePrefix = stringPreferencesKey("title_prefix")
        val titleTemplate = stringPreferencesKey("title_template")
        val channelTitle = stringPreferencesKey("channel_title")
    }

    val flow: Flow<Settings> = context.store.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    private fun Preferences.toSettings() = Settings(
        enabled = this[K.enabled] ?: false,
        wifiOnly = this[K.wifiOnly] ?: true,
        chargingOnly = this[K.chargingOnly] ?: false,
        ageRestrict = this[K.ageRestrict] ?: true,
        sinceEpochSec = this[K.since] ?: 0,
        connected = this[K.connected] ?: false,
        titleTemplate = this[K.titleTemplate]
            ?: this[K.legacyTitlePrefix]?.trim()?.takeIf { it.isNotEmpty() }?.let { "{YYYYMMDD} $it {HH}:{mm}" }
            ?: VideoMetadata.DEFAULT_TEMPLATE,
        channelTitle = this[K.channelTitle] ?: "",
    )

    /** Turning sync on the first time starts the window "from now" so old videos aren't flooded in. */
    suspend fun setEnabled(on: Boolean) = context.store.edit {
        it[K.enabled] = on
        if (on && (it[K.since] ?: 0L) == 0L) it[K.since] = System.currentTimeMillis() / 1000
    }

    suspend fun setWifiOnly(v: Boolean) = context.store.edit { it[K.wifiOnly] = v }
    suspend fun setChargingOnly(v: Boolean) = context.store.edit { it[K.chargingOnly] = v }
    suspend fun setAgeRestrict(v: Boolean) = context.store.edit { it[K.ageRestrict] = v }
    suspend fun setSince(epochSec: Long) = context.store.edit { it[K.since] = epochSec }
    suspend fun setTitleTemplate(v: String) = context.store.edit { it[K.titleTemplate] = v }
    suspend fun setConnected(v: Boolean, channel: String? = null) = context.store.edit {
        it[K.connected] = v
        if (channel != null) it[K.channelTitle] = channel
    }
}
