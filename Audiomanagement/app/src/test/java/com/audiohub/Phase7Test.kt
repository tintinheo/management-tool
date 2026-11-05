package com.audiohub

import com.audiohub.data.SortField
import com.audiohub.data.SortOrder
import com.audiohub.data.TrackSorter
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.ringtone.RingtoneClip
import com.audiohub.ui.nowplaying.parseSleepMinutes
import com.audiohub.widget.WidgetCollection
import com.audiohub.widget.WidgetCollections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class RingtoneAndSleepTest {

    @Test
    fun clipTimesParseAndFormat() {
        assertEquals(83_500L, RingtoneClip.parseTime("1:23.5"))
        assertEquals(83_000L, RingtoneClip.parseTime("83"))
        assertEquals(83_500L, RingtoneClip.parseTime(" 1:23,5 "))
        assertEquals(3_723_000L, RingtoneClip.parseTime("1:02:03"))
        assertNull(RingtoneClip.parseTime("1:75"))
        assertNull(RingtoneClip.parseTime("abc"))
        assertNull(RingtoneClip.parseTime(""))
        assertEquals("1:23.5", RingtoneClip.formatTime(83_500))
        assertEquals("0:00.0", RingtoneClip.formatTime(0))
        assertEquals("10:00.0", RingtoneClip.formatTime(599_960)) // rounds to the nearest tenth
    }

    @Test
    fun clipValidationAndDefaults() {
        assertEquals(0L to 30_000L, RingtoneClip.defaultRange(240_000))
        assertEquals(0L to 12_000L, RingtoneClip.defaultRange(12_000))
        assertNull(RingtoneClip.validate(10_000, 40_000, 240_000))
        assertNotNull(RingtoneClip.validate(10_000, 10_500, 240_000)) // too short
        assertNotNull(RingtoneClip.validate(0, 400_000, 600_000)) // too long
        assertNotNull(RingtoneClip.validate(10_000, 250_000, 240_000)) // past the end
        assertEquals("Lạc Trôi ringtone.m4a", RingtoneClip.fileName("Lạc Trôi: ringtone?"))
    }

    @Test
    fun sleepMinutesAcceptMinutesOrHoursColonMinutes() {
        assertEquals(20L, parseSleepMinutes("20"))
        assertEquals(90L, parseSleepMinutes("1:30"))
        assertEquals(5L, parseSleepMinutes("0:05"))
        assertNull(parseSleepMinutes("0"))
        assertNull(parseSleepMinutes("1:75"))
        assertNull(parseSleepMinutes("2000")) // over 24 h
        assertNull(parseSleepMinutes(""))
    }

    @Test
    fun sortingFiveThousandSongsIsFast() {
        val words = listOf("Đà Lạt", "Sài Gòn", "Hà Nội", "Mùa thu", "Em ơi", "Track", "Bài hát", "Tình yêu")
        val tracks = (1L..5_000L).map { i ->
            TrackEntity(
                id = i, uri = "content://x/$i", title = "${words[(i % 8).toInt()]} ${i * 7919 % 5000}",
                artist = "Nghệ sĩ ${i % 97}", albumArtist = null, album = "Album ${i % 211}", albumId = i % 211,
                trackNumber = (i % 12).toInt(), discNumber = 0, year = 2000, genre = null, durationMs = 1,
                dateAddedSec = i, relativePath = "Music/AudioHub/", displayName = "$i.mp3",
            )
        }
        TrackSorter.sort(tracks, SortOrder(SortField.TITLE)) // warm-up (regex/JIT)
        for (field in SortField.entries) {
            val started = System.nanoTime()
            val sorted = TrackSorter.sort(tracks, SortOrder(field))
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(5_000, sorted.size)
            assertTrue("$field took $ms ms", ms < 1_500)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class WidgetCollectionsTest {
    @Test
    fun collectionsRoundTripThroughIntentData() {
        listOf(
            WidgetCollection.All,
            WidgetCollection.Playlist(42),
            WidgetCollection.Folder("Music/AudioHub/Nhạc Việt/Trịnh Công Sơn/"),
        ).forEach { c -> assertEquals(c, WidgetCollections.parse(WidgetCollections.uri(c))) }
        // Distinct URIs → distinct PendingIntents for each row.
        assertTrue(WidgetCollections.uri(WidgetCollection.Playlist(1)) != WidgetCollections.uri(WidgetCollection.Playlist(2)))
    }
}
