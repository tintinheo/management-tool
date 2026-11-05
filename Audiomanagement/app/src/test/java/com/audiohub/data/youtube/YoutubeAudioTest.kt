package com.audiohub.data.youtube

import com.audiohub.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class YoutubeAudioTest {

    @Test
    fun recognisesEveryYoutubeLinkShape() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?feature=share&v=$id&t=42",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM",
            "https://youtu.be/$id?si=abc",
            "youtu.be/$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube-nocookie.com/embed/$id",
        ).forEach { assertEquals(it, id, YoutubeLinks.videoId(it)) }
        assertEquals("https://www.youtube.com/watch?v=$id", YoutubeLinks.canonical(" https://youtu.be/$id "))
        assertNull(YoutubeLinks.videoId("https://www.youtube.com/@channel"))
        assertNull(YoutubeLinks.videoId("https://vimeo.com/123456"))
        assertNull(YoutubeLinks.videoId("not a link"))
    }

    @Test
    fun picksTheBestSoundingOriginalTrack() {
        val aac128 = AudioCandidate("a", 128, AudioContainer.M4A_AAC)
        val opus160 = AudioCandidate("b", 160, AudioContainer.WEBM_OPUS)
        val opus70 = AudioCandidate("c", 70, AudioContainer.WEBM_OPUS)
        val dubbedOpus = AudioCandidate("d", 160, AudioContainer.WEBM_OPUS, isOriginalTrack = false)
        assertEquals(opus160, AudioPicker.best(listOf(aac128, opus70, opus160)))
        assertEquals(aac128, AudioPicker.best(listOf(aac128, opus70))) // AAC 128 beats Opus 70
        assertEquals(aac128, AudioPicker.best(listOf(dubbedOpus, aac128))) // original language first
        assertNull(AudioPicker.best(emptyList()))
        assertEquals("Opus · 160 kbps", opus160.label)
    }

    @Test
    fun trimValidationAndNames() {
        assertNull(YoutubeTrim.validate(0, 0, 200_000)) // whole video
        assertNull(YoutubeTrim.validate(30_000, 90_000, 200_000))
        assertNotNull(YoutubeTrim.validate(90_000, 90_500, 200_000))
        assertNotNull(YoutubeTrim.validate(250_000, 0, 200_000))
        assertNotNull(YoutubeTrim.validate(0, 260_000, 200_000))
        assertTrue(YoutubeTrim.isFull(0, 200_000, 200_000))
        assertTrue(YoutubeTrim.isFull(0, 0, 200_000))
        assertEquals("Sơn Tùng M-TP Lạc Trôi Official MV", YoutubeTrim.fileBaseName("Sơn Tùng M-TP | Lạc Trôi | Official MV"))
    }
}

@RunWith(RobolectricTestRunner::class)
class YoutubeShareTest {
    @Test
    fun sharedYoutubeLinkOpensTheConverterPrefilled() {
        val route = Routes.forSharedText("Xem nè https://youtu.be/dQw4w9WgXcQ?si=x")
        assertEquals(Routes.youtube("https://youtu.be/dQw4w9WgXcQ?si=x"), route)
        // Podcast links still go to the podcast screen.
        assertEquals(Routes.show(itunesId = 1200361736), Routes.forSharedText("https://podcasts.apple.com/vn/podcast/x/id1200361736"))
    }
}
