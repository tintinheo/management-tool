package com.audiohub.playback

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSessionService
import com.audiohub.AudioHubApp
import com.audiohub.data.db.EpisodeFeedData
import com.audiohub.data.db.PodcastEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.io.File
import java.time.Duration
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives the real [PlaybackService] (ExoPlayer + MediaSession) through [PlayerConnection]
 * (MediaController), exactly as the UI does, using generated WAV files.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackFlowTest {

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: ServiceController<PlaybackService>
    private lateinit var connection: PlayerConnection

    @Before
    fun setUp() {
        service = Robolectric.buildService(PlaybackService::class.java).create()
        val component = ComponentName(app, PlaybackService::class.java)
        val binder = service.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(component))
        // Route the controller's bindService() to the in-process service instance.
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(MediaSessionService.SERVICE_INTERFACE).setClassName(app, PlaybackService::class.java.name),
            component,
            binder,
        )
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        connection = PlayerConnection(app)
    }

    @After
    fun tearDown() {
        service.destroy()
    }

    @Test
    fun playsQueueAndControlsWork() {
        val items = listOf("One", "Two", "Three").mapIndexed { i, name ->
            wavItem(name, 330.0 + i * 110, seconds = 3)
        }

        connection.playQueue(items, startIndex = 1)
        waitFor("playback starts") { connection.state.value.isPlaying }

        var s = connection.state.value
        assertEquals("Two", s.title)
        assertEquals(1, s.queueIndex)
        assertEquals(3, s.queueSize)
        assertTrue("duration known", s.durationMs in 2_500..3_500)

        waitFor("position advances") { connection.currentPositionMs > 300 }

        connection.togglePlayPause()
        waitFor("paused") { !connection.state.value.isPlaying }

        connection.togglePlayPause()
        waitFor("resumed") { connection.state.value.isPlaying }

        connection.skipNext()
        waitFor("skipped to Three") { connection.state.value.title == "Three" }

        connection.cycleRepeat()
        waitFor("repeat all") { connection.state.value.repeatMode == Player.REPEAT_MODE_ALL }
        connection.toggleShuffle()
        waitFor("shuffle on") { connection.state.value.shuffleEnabled }

        connection.addToQueue(wavItem("Four", 550.0, seconds = 1))
        waitFor("queue grew") { connection.state.value.queueSize == 4 }

        s = connection.state.value
        assertTrue(s.hasMedia)
    }

    @Test
    fun playsToEndOfQueue() {
        connection.playQueue(listOf(wavItem("Short", 440.0, seconds = 1)))
        waitFor("playback starts") { connection.state.value.isPlaying }
        waitFor("reaches end", timeoutMs = 10_000) { !connection.state.value.isPlaying }
        // Play again after end should restart from the beginning.
        connection.togglePlayPause()
        waitFor("restarts") { connection.state.value.isPlaying }
    }

    @Test
    fun podcastEpisodeResumesSavesProgressAndUsesPodcastSpeed() {
        val container = (app as AudioHubApp).container
        val repo = container.podcastRepository
        val wav = File(app.cacheDir, "episode.wav").also { writeSineWav(it, 300.0, seconds = 8) }
        val episodeId = runBlocking {
            container.settings.setPodcastSpeed(1.5f)
            val dao = container.database.podcastDao()
            val podcastId = dao.insertPodcast(
                PodcastEntity(
                    feedUrl = "https://example.com/feed", itunesId = null, title = "Test Show", author = "",
                    description = "", artworkUrl = null, websiteUrl = null, subscribedAt = 0,
                ),
            )
            dao.mergeEpisodes(
                podcastId,
                listOf(EpisodeFeedData("g1", "Episode 1", "<p>Notes</p>", 0, 8_000, Uri.fromFile(wav).toString(), "audio/wav", 0, null, null, null)),
            )
            dao.observeEpisodes(podcastId).first().single().id
        }
        val episode = runBlocking { repo.episode(episodeId)!! }

        // Resume from a saved position at the podcast speed.
        connection.playQueue(listOf(episode.toMediaItem()), startPositionMs = 2_000)
        waitFor("episode playing") { connection.state.value.isPlaying }
        assertTrue("resumed near 2 s", connection.currentPositionMs >= 1_900)
        assertEquals("Test Show", connection.state.value.artist)
        waitFor("podcast speed applied") { connection.state.value.speed == 1.5f }

        waitFor("progress") { connection.currentPositionMs > 3_000 }
        connection.togglePlayPause()
        waitFor("paused") { !connection.state.value.isPlaying }
        waitFor("position saved on pause") {
            runBlocking { repo.episode(episodeId)!!.episode.positionMs } > 2_500
        }
        assertFalse(runBlocking { repo.episode(episodeId)!!.episode.played })

        // Playing it to the end marks it played.
        connection.seekTo(7_500)
        connection.togglePlayPause()
        waitFor("marked played", timeoutMs = 10_000) { runBlocking { repo.episode(episodeId)!!.episode.played } }

        // Music always plays at 1×.
        connection.playQueue(listOf(wavItem("Song", 440.0, seconds = 2)))
        waitFor("music at 1x") { connection.state.value.isPlaying && connection.state.value.speed == 1f }
    }

    @Test
    fun sleepTimerPausesAfterDurationAndAtEndOfTrack() {
        // Timed: pauses (after fading) once the duration elapses, then clears itself.
        connection.playQueue(listOf(wavItem("Long", 330.0, seconds = 30)))
        waitFor("playing") { connection.state.value.isPlaying }
        connection.setSleepTimer(1_500)
        waitFor("timer visible") { connection.state.value.sleepEndsAt > 0 }
        waitFor("paused by timer", timeoutMs = 6_000) { !connection.state.value.isPlaying }
        waitFor("timer cleared") { !connection.state.value.sleepActive }
        // A natural end keeps playWhenReady=true; only a pause (the timer) clears it.
        assertTrue("paused by the timer (pos=${connection.currentPositionMs})", !connection.state.value.playWhenReady)

        // End of track: the first item plays out, then playback pauses instead of continuing.
        connection.playQueue(listOf(wavItem("A", 440.0, seconds = 1), wavItem("B", 550.0, seconds = 3)))
        waitFor("playing A") { connection.state.value.isPlaying }
        connection.setSleepTimer(PlaybackService.SLEEP_END_OF_ITEM)
        waitFor("end-of-track armed") { connection.state.value.sleepEndOfItem }
        waitFor("paused at end of A", timeoutMs = 6_000) { !connection.state.value.isPlaying }
        waitFor("timer cleared") { !connection.state.value.sleepActive }
        assertTrue("didn't play through B", connection.state.value.title != "B" || connection.currentPositionMs < 500)

        // Cancel works.
        connection.togglePlayPause()
        connection.setSleepTimer(60_000)
        waitFor("armed") { connection.state.value.sleepActive }
        connection.setSleepTimer(0)
        waitFor("cancelled") { !connection.state.value.sleepActive }
    }

    /** Pumps the main looper (controller callbacks) while ExoPlayer runs on its own thread. */
    private fun waitFor(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for: $what (state=${connection.state.value})")
    }

    private fun wavItem(title: String, freq: Double, seconds: Int): MediaItem {
        val file = File(app.cacheDir, "$title.wav")
        writeSineWav(file, freq, seconds)
        val uri = Uri.fromFile(file)
        return MediaItem.Builder()
            .setMediaId("test:$title")
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Test").build())
            .build()
    }

    private fun writeSineWav(file: File, freq: Double, seconds: Int) {
        val rate = 22_050
        val n = rate * seconds
        val buf = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buf.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) buf.putShort((sin(2 * PI * freq * i / rate) * 6000).toInt().toShort())
        file.writeBytes(buf.array())
    }
}
