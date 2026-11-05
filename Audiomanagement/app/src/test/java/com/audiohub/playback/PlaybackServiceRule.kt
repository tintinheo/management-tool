package com.audiohub.playback

import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaSessionService
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Creates the real [PlaybackService] in-process and routes bindService() calls to it, so a
 * MediaController (PlayerConnection) connects exactly as on a device. Must wrap any rule that
 * launches an Activity (use RuleChain / a lower `order`).
 */
class PlaybackServiceRule : ExternalResource() {

    private var controller: ServiceController<PlaybackService>? = null

    override fun before() {
        val app = RuntimeEnvironment.getApplication()
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        val component = ComponentName(app, PlaybackService::class.java)
        val binder = created.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(component))
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
    }

    override fun after() {
        controller?.destroy()
    }
}
