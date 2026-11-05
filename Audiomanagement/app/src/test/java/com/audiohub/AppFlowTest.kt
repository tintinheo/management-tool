package com.audiohub

import android.Manifest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.audiohub.playback.PlaybackServiceRule
import com.audiohub.ui.PHONE_QUALIFIERS
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Launches the real MainActivity (real Room, DataStore, AppContainer) and walks the first-run flow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class AppFlowTest {

    @get:Rule(order = 0)
    val playbackService = PlaybackServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingThenMainTabs() {
        compose.onNodeWithText("Welcome to My Audio Hub").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/flow_01_welcome.png")

        // Simulate the user tapping "Allow" in the system dialogs.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Continue").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/screenshots/flow_02_permissions.png")

        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Keep playing in the background").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/flow_03_background.png")

        compose.onNodeWithText("Done").performScrollTo().performClick()

        // Onboarding flag is persisted via DataStore and the library scan runs on IO.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("No music yet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("build/screenshots/flow_04_music_empty.png")

        compose.onNodeWithText("Podcasts").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No shows yet").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Music folder").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/flow_05_settings.png")

        compose.onNodeWithText("Music").performClick() // on Settings, only the tab label says "Music"
        compose.onNodeWithText("No music yet").assertExists()
    }
}
