package com.snapbrain

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.snapbrain.ui.PHONE_QUALIFIERS
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real MainActivity (real Room, prefs, card pack): onboarding → home → feed → answer a quiz. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class AppFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingThenLearnOneCard() {
        compose.onNodeWithText("Bắt đầu").performClick()
        compose.onNodeWithText("Tiếp tục").performClick()           // apps
        compose.onNodeWithText("Để sau").performClick()             // accessibility
        compose.onNodeWithText("Để sau").performClick()             // Xiaomi
        compose.onNodeWithText("Xong").performClick()               // notifications

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Bộ chặn đang tắt").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("build/screenshots/flow_1_home.png")

        compose.onNodeWithText("Lướt tri thức").performClick()
        // Tap through hook and body lines to reach the quiz.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Chạm để tiếp").fetchSemanticsNodes().isNotEmpty() }
        repeat(4) { compose.onNodeWithText("Chạm để tiếp").performClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("✓  Đúng").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("build/screenshots/flow_2_quiz.png")

        compose.onNodeWithText("✓  Đúng").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Thẻ tiếp theo").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("build/screenshots/flow_3_result.png")

        compose.onNodeWithText("Hôm nay").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Hôm nay đã giữ chuỗi. Giỏi lắm!").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("🔥 1").fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage("build/screenshots/flow_4_home_after.png")
    }
}
