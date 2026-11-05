package com.audiohub

import com.audiohub.data.storage.AudioFolders
import com.audiohub.ui.components.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class BasicsTest {

    @Test
    fun normalizeFolder() {
        assertEquals("Music/AudioHub/", AudioFolders.normalize("Music/AudioHub"))
        assertEquals("Music/AudioHub/", AudioFolders.normalize("/Music/AudioHub/"))
        assertEquals("Music/AudioHub/", AudioFolders.normalize("  Music\\AudioHub  "))
        assertEquals("", AudioFolders.normalize("/"))
    }

    @Test
    fun formatDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:42", formatDuration(42_000))
        assertEquals("3:07", formatDuration(187_000))
        assertEquals("1:02:33", formatDuration(3_753_000))
        assertEquals("0:00", formatDuration(-5))
    }
}
