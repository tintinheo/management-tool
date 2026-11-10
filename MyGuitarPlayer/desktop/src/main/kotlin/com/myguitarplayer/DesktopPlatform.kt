package com.myguitarplayer

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.myguitarplayer.data.AppDb
import com.myguitarplayer.platform.Platform
import com.myguitarplayer.platform.Prefs
import kotlinx.coroutines.Dispatchers
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.JarURLConnection
import java.util.Properties
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Windows implementation of [Platform]. Everything lives in %LOCALAPPDATA%\MyGuitarPlayer:
 * guitar.db (same Room schema as the phone), settings\<name>.properties, recordings/, last_crash.txt.
 */
object DesktopPlatform : Platform {
    private val dataDir: File by lazy {
        // mgp.dataDir lets tests use a throwaway folder.
        val dir = System.getProperty("mgp.dataDir")?.let(::File)
            ?: File(System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".myguitarplayer"), "MyGuitarPlayer")
        dir.also { it.mkdirs() }
    }

    override val db: AppDb by lazy {
        Room.databaseBuilder<AppDb>(name = File(dataDir, AppDb.FILE).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    override val filesDir: File get() = dataDir

    override fun readAsset(path: String): String =
        resource("assets/$path").use { it.bufferedReader().readText() }

    override fun listAssets(dir: String): List<String> {
        val url = javaClass.classLoader.getResource("assets/$dir") ?: return emptyList()
        return when (url.protocol) {
            "file" -> File(url.toURI()).list()?.toList().orEmpty()
            "jar" -> {
                val prefix = "assets/$dir/"
                (url.openConnection() as JarURLConnection).jarFile.use { jar ->
                    jar.entries().asSequence().map { it.name }
                        .filter { it.startsWith(prefix) && it.length > prefix.length && '/' !in it.substring(prefix.length) }
                        .map { it.substring(prefix.length) }
                        .toList()
                }
            }
            else -> emptyList()
        }
    }

    private val prefsCache = mutableMapOf<String, FilePrefs>()

    override fun prefs(name: String): Prefs = synchronized(prefsCache) {
        prefsCache.getOrPut(name) { FilePrefs(File(File(dataDir, "settings").also { it.mkdirs() }, "$name.properties")) }
    }

    override fun copyToClipboard(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override val supportsRawMic: Boolean get() = false

    override val micSourceChoice: Boolean get() = false

    override val appVersion: String by lazy {
        runCatching { resource("app-version.txt").use { it.bufferedReader().readText().trim() } }.getOrDefault("?")
    }

    override val deviceDescription: String
        get() {
            val mic = runCatching {
                AudioSystem.getMixerInfo().firstOrNull { info ->
                    AudioSystem.getMixer(info).targetLineInfo.any { it.lineClass == TargetDataLine::class.java }
                }?.name
            }.getOrNull() ?: "không thấy micro"
            return "${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${System.getProperty("os.arch")} · " +
                "Java ${System.getProperty("java.version")} · $mic"
        }

    private fun resource(path: String) =
        javaClass.classLoader.getResourceAsStream(path) ?: error("Missing bundled file: $path")

    /** A tiny Properties-file backed [Prefs]; writes through on every edit (settings change rarely). */
    private class FilePrefs(private val file: File) : Prefs {
        private val props = Properties().apply {
            if (file.exists()) runCatching { file.inputStream().use { load(it) } }
        }

        override fun getInt(key: String, default: Int) = synchronized(props) { props.getProperty(key)?.toIntOrNull() ?: default }
        override fun getFloat(key: String, default: Float) = synchronized(props) { props.getProperty(key)?.toFloatOrNull() ?: default }
        override fun getString(key: String): String? = synchronized(props) { props.getProperty(key) }

        override fun edit(block: Prefs.Editor.() -> Unit) = synchronized(props) {
            object : Prefs.Editor {
                override fun putInt(key: String, value: Int) { props.setProperty(key, value.toString()) }
                override fun putFloat(key: String, value: Float) { props.setProperty(key, value.toString()) }
                override fun putString(key: String, value: String) { props.setProperty(key, value) }
                override fun remove(key: String) { props.remove(key) }
            }.block()
            runCatching { file.outputStream().use { props.store(it, "MyGuitarPlayer") } }
            Unit
        }
    }
}
