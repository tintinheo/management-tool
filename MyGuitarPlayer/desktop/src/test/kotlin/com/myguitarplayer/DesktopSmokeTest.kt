package com.myguitarplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.myguitarplayer.audio.AudioConfig
import com.myguitarplayer.audio.ChordPlayer
import com.myguitarplayer.data.Repos
import com.myguitarplayer.platform.Platform
import com.myguitarplayer.ui.theme.GuitarTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Windows build: data layer on the real SQLite/Room stack + offscreen screenshots of every tab. */
class DesktopSmokeTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val dir = Files.createTempDirectory("mgp-test").toFile()
            System.setProperty("mgp.dataDir", dir.absolutePath)
            Platform.install(DesktopPlatform)
            AudioConfig.load()
        }
    }

    @Test
    fun bundledAssetsAreReadable() {
        val songs = DesktopPlatform.listAssets("songs").filter { it.endsWith(".cho") }
        assertTrue("samples: $songs", songs.size >= 8)
        assertTrue(DesktopPlatform.readAsset("curriculum.json").contains("\"weeks\""))
        assertEquals("1.0.0", DesktopPlatform.appVersion)
    }

    @Test
    fun databaseAndSettingsWork() = runBlocking {
        Repos.songs.seedSamples()
        Repos.songs.seedSamples() // second run must not duplicate
        val songs = Repos.songs.all().first()
        assertEquals(DesktopPlatform.listAssets("songs").count { it.endsWith(".cho") }, songs.size)

        val id = Repos.songs.save("{title: Thử}\n[C]Một [G]hai", "", "", null)
        assertEquals("Thử", Repos.songs.byId(id).first()?.title)

        Repos.progress.mark(com.myguitarplayer.data.Milestone.TUNED)
        assertTrue(Repos.progress.course().first().record.tuned)

        Repos.practice.latencyMs = 87
        assertEquals(87, Repos.practice.latencyMs)

        AudioConfig.chordLevel = 2
        AudioConfig.save()
        AudioConfig.chordLevel = 1
        AudioConfig.load()
        assertEquals(2, AudioConfig.chordLevel)
        AudioConfig.reset()
    }

    @Test
    fun pcmConversionIsLittleEndian() {
        val b = ChordPlayer.toPcm16(floatArrayOf(0f, 1f, -1f))
        assertEquals(listOf<Byte>(0, 0, -1, 0x7f, 1, -128), b.toList())
    }

    @Test
    fun screenshots() {
        val out = File("build/screenshots").also { it.mkdirs() }
        runBlocking { Repos.songs.seedSamples() }
        for (tab in Tab.entries) shot(File(out, "desktop_${tab.name.lowercase()}.png"), 1100, 820) { App(tab) }
        shot(File(out, "desktop_narrow_today.png"), 480, 860) { App(Tab.TODAY) }
    }

    private fun shot(file: File, w: Int, h: Int, content: @Composable () -> Unit) {
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(w, h, Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { GuitarTheme { content() } }
        }
        try {
            // Let Room flows deliver (they load on Dispatchers.IO) before the final frame.
            var t = 0L
            repeat(15) { scene.render(t); Thread.sleep(80); t += 100_000_000L }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
            file.writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }
}
