package com.myguitarplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

// Windows side of the small UI hooks the shared screens use (Android: app/src/main/androidOnly/.../PlatformUi.kt).

/**
 * "Back" on Windows = Esc or the mouse's back button (wired in Main.kt to [BackDispatcher.back]).
 * Like Android, the most recently composed enabled handler wins.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val entry = remember { BackDispatcher.Entry() }
    SideEffect {
        entry.enabled = enabled
        entry.onBack = onBack
    }
    DisposableEffect(entry) {
        BackDispatcher.add(entry)
        onDispose { BackDispatcher.remove(entry) }
    }
}

object BackDispatcher {
    class Entry {
        @Volatile var enabled = true
        @Volatile var onBack: () -> Unit = {}
    }

    private val entries = mutableListOf<Entry>()

    fun add(e: Entry) = synchronized(entries) { entries.add(e) }
    fun remove(e: Entry) = synchronized(entries) { entries.remove(e) }

    /** Runs the active handler; false when nothing handled it. */
    fun back(): Boolean {
        val e = synchronized(entries) { entries.lastOrNull { it.enabled } } ?: return false
        e.onBack()
        return true
    }
}

/** The PC doesn't lock the screen mid-practice the way a phone does; nothing to do. */
@Composable
fun KeepScreenOn() {}

/**
 * Desktop apps don't ask per-app for the mic; Windows' privacy switch decides, and MicStream reports a
 * clear error if it's off.
 */
@Composable
fun rememberMicPermission(): Pair<Boolean, () -> Unit> = true to {}
