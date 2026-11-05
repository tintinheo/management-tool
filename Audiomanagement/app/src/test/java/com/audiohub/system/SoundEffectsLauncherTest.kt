package com.audiohub.system

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SoundEffectsLauncherTest {

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun fallsBackToSoundSettingsWhenNothingHandlesTheEffectsPanel() {
        // Like Xiaomi: no app answers ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL.
        val resolved = SoundEffectsLauncher.resolve(app)
        assertEquals(SoundEffectsLauncher.Target.SOUND_SETTINGS, resolved.target)
        assertEquals(Settings.ACTION_SOUND_SETTINGS, resolved.intent.action)
        assertEquals(SoundEffectsLauncher.Target.SOUND_SETTINGS, SoundEffectsLauncher.open(app))
    }

    @Test
    fun opensDolbyAppWhenInstalled() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.dolby.daxappui")
        shadowOf(app.packageManager).addResolveInfoForIntent(
            launcher,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.dolby.daxappui"
                    name = "com.dolby.daxappui.MainActivity"
                }
            },
        )
        val resolved = SoundEffectsLauncher.resolve(app)
        assertEquals(SoundEffectsLauncher.Target.EFFECTS_APP, resolved.target)
        assertEquals("com.dolby.daxappui", resolved.intent.component?.packageName ?: resolved.intent.`package`)
    }
}
