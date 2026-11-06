package com.snapbrain.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.snapbrain.data.content.Topic
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Time source; tests swap in a fixed clock. Days are local epoch days. */
open class AppClock {
    open fun now(): Long = System.currentTimeMillis()
    fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    fun today(): Long = dayOf(now())
}

data class Settings(
    val onboarded: Boolean = false,
    val blocked: Set<String> = SuggestedApps.DEFAULT_BLOCKED,
    val strict: Boolean = false,
    val skipsUsedDay: Long = -1,
    val skipsUsed: Int = 0,
    /** Blocked apps open freely until this time (pass after a card, or bought tickets). */
    val freeUntil: Long = 0,
    val freeIsTicket: Boolean = false,
    val saverHour: Int = 21,
    val tts: Boolean = false,
    /** Empty = all topics. */
    val topics: Set<Topic> = emptySet(),
    val streakNotifiedDay: Long = -1,
    val guardNotifiedAt: Long = 0,
)

/**
 * Settings in SharedPreferences (not DataStore): the accessibility service reads them synchronously
 * on every window change, so they must be cheap to read without suspending.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        write(next)
        _state.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            onboarded = prefs.getBoolean("onboarded", d.onboarded),
            blocked = prefs.getStringSet("blocked", null)?.toSet() ?: d.blocked,
            strict = prefs.getBoolean("strict", d.strict),
            skipsUsedDay = prefs.getLong("skipsUsedDay", d.skipsUsedDay),
            skipsUsed = prefs.getInt("skipsUsed", d.skipsUsed),
            freeUntil = prefs.getLong("freeUntil", d.freeUntil),
            freeIsTicket = prefs.getBoolean("freeIsTicket", d.freeIsTicket),
            saverHour = prefs.getInt("saverHour", d.saverHour),
            tts = prefs.getBoolean("tts", d.tts),
            topics = prefs.getStringSet("topics", null)
                ?.mapNotNull { name -> Topic.entries.firstOrNull { it.name == name } }?.toSet() ?: d.topics,
            streakNotifiedDay = prefs.getLong("streakNotifiedDay", d.streakNotifiedDay),
            guardNotifiedAt = prefs.getLong("guardNotifiedAt", d.guardNotifiedAt),
        )
    }

    private fun write(s: Settings) {
        prefs.edit {
            putBoolean("onboarded", s.onboarded)
            putStringSet("blocked", s.blocked)
            putBoolean("strict", s.strict)
            putLong("skipsUsedDay", s.skipsUsedDay)
            putInt("skipsUsed", s.skipsUsed)
            putLong("freeUntil", s.freeUntil)
            putBoolean("freeIsTicket", s.freeIsTicket)
            putInt("saverHour", s.saverHour)
            putBoolean("tts", s.tts)
            putStringSet("topics", s.topics.map { it.name }.toSet())
            putLong("streakNotifiedDay", s.streakNotifiedDay)
            putLong("guardNotifiedAt", s.guardNotifiedAt)
        }
    }
}

object SuggestedApps {
    /** package → display name. Shown first in the app picker even if not installed. */
    val ALL = linkedMapOf(
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok (châu Á)",
        "com.zhiliaoapp.musically.go" to "TikTok Lite",
        "com.ss.android.ugc.tiktok.lite" to "TikTok Lite",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook Lite",
        "com.instagram.android" to "Instagram",
        "com.instagram.barcelona" to "Threads",
        "com.google.android.youtube" to "YouTube",
        "com.twitter.android" to "X (Twitter)",
        "com.reddit.frontpage" to "Reddit",
    )

    val DEFAULT_BLOCKED = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go",
        "com.ss.android.ugc.tiktok.lite",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.instagram.android",
    )
}
