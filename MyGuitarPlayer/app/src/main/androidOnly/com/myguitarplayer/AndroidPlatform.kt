package com.myguitarplayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import androidx.core.content.edit
import androidx.room.Room
import com.myguitarplayer.data.AppDb
import com.myguitarplayer.platform.Platform
import com.myguitarplayer.platform.Prefs
import java.io.File

/** Android implementation of [Platform]: app-private files, assets, SharedPreferences, Room on guitar.db. */
class AndroidPlatform private constructor(val context: Context) : Platform {
    override val db: AppDb by lazy {
        Room.databaseBuilder(context, AppDb::class.java, AppDb.FILE).build()
    }

    override val filesDir: File get() = context.filesDir

    override fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    override fun listAssets(dir: String): List<String> = context.assets.list(dir).orEmpty().toList()

    override fun prefs(name: String): Prefs = SharedPrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun copyToClipboard(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override val supportsRawMic: Boolean
        get() = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    override val micSourceChoice: Boolean get() = true

    override val appVersion: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    override val deviceDescription: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    private class SharedPrefs(private val p: SharedPreferences) : Prefs {
        override fun getInt(key: String, default: Int) = p.getInt(key, default)
        override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
        override fun getString(key: String): String? = p.getString(key, null)
        override fun edit(block: Prefs.Editor.() -> Unit) = p.edit {
            val e = this
            object : Prefs.Editor {
                override fun putInt(key: String, value: Int) { e.putInt(key, value) }
                override fun putFloat(key: String, value: Float) { e.putFloat(key, value) }
                override fun putString(key: String, value: String) { e.putString(key, value) }
                override fun remove(key: String) { e.remove(key) }
            }.block()
        }
    }

    companion object {
        @Volatile private var instance: AndroidPlatform? = null

        /** One instance per process, so there is only ever one Room database. */
        fun get(context: Context): AndroidPlatform = instance ?: synchronized(this) {
            instance ?: AndroidPlatform(context.applicationContext).also { instance = it }
        }
    }
}
