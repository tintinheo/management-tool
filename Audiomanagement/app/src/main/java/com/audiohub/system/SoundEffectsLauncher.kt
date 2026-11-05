package com.audiohub.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.provider.Settings

/**
 * Opens the phone's own sound-effects screen (Dolby Atmos / Mi Sound on Xiaomi). Xiaomi doesn't
 * answer the standard ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, so known apps are tried first and
 * the system Sound settings are the last resort. Package names must also be listed in <queries>.
 */
object SoundEffectsLauncher {

    /** Sound-effect apps seen on Xiaomi / Redmi / POCO and other OEMs, best first. */
    val KNOWN_PACKAGES = listOf(
        "com.dolby.daxappui", // Dolby Atmos (Xiaomi, many OEMs)
        "com.dolby.dax2appUI",
        "com.miui.misound", // Mi Sound / Headphones & audio effects
        "com.sec.android.app.soundalive", // Samsung SoundAlive
        "com.motorola.dolby.dolbyui",
    )

    enum class Target { EFFECTS_APP, EFFECTS_PANEL, SOUND_SETTINGS }

    data class Resolved(val intent: Intent, val target: Target)

    fun resolve(context: Context): Resolved {
        val pm = context.packageManager
        for (pkg in KNOWN_PACKAGES) {
            pm.getLaunchIntentForPackage(pkg)?.let { return Resolved(it, Target.EFFECTS_APP) }
        }
        val panel = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        if (panel.resolveActivity(pm) != null) return Resolved(panel, Target.EFFECTS_PANEL)
        return Resolved(Intent(Settings.ACTION_SOUND_SETTINGS), Target.SOUND_SETTINGS)
    }

    /** Opens the best match; returns what was opened (null if even Sound settings failed). */
    fun open(context: Context): Target? {
        val resolved = resolve(context)
        val opened = try {
            context.startActivity(resolved.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
        DiagnosticsLog.log("SoundEffects", "Open ${resolved.target} (${resolved.intent.`package` ?: resolved.intent.action}) → $opened")
        if (opened) return resolved.target
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Target.SOUND_SETTINGS
        }.getOrNull()
    }
}
