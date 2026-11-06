package com.snapbrain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.snapbrain.data.DayStats
import com.snapbrain.data.Settings
import com.snapbrain.data.content.CardParser
import com.snapbrain.domain.Streak
import com.snapbrain.intercept.HookScreen
import com.snapbrain.ui.card.CardContent
import com.snapbrain.ui.card.Phase
import com.snapbrain.ui.card.QuizOutcome
import com.snapbrain.ui.home.HomeContent
import com.snapbrain.ui.home.HomeState
import com.snapbrain.ui.onboarding.OnboardingActions
import com.snapbrain.ui.onboarding.OnboardingContent
import com.snapbrain.ui.settings.AppEntry
import com.snapbrain.ui.settings.SettingsActions
import com.snapbrain.ui.settings.SettingsContent
import com.snapbrain.ui.theme.Palette
import com.snapbrain.ui.theme.SnapBrainTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

const val PHONE_QUALIFIERS = "w393dp-h873dp-xxhdpi"

/** Renders each screen with sample data into app/build/screenshots/ for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val cards = CardParser.parse(File("src/main/assets/cards.json").readText())
    private val flashFill = cards.first { it.id == "office-flashfill" }
    private val english = cards.first { it.id == "english-earliest-convenience" }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SnapBrainTheme { Box(Modifier.fillMaxSize().background(Palette.Ink)) { content() } }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun card_hook() = shot("card_1_hook") { CardContent(flashFill, Phase.HOOK, 0, null, {}, {}, {}) }

    @Test
    fun card_body() = shot("card_2_body") { CardContent(flashFill, Phase.BODY, 3, null, {}, {}, {}) }

    @Test
    fun card_bodyCompare() = shot("card_2b_body_versus") { CardContent(english, Phase.BODY, 2, null, {}, {}, {}) }

    @Test
    fun card_quiz() = shot("card_3_quiz") { CardContent(flashFill, Phase.QUIZ, 3, null, {}, {}, {}) }

    @Test
    fun card_right() = shot("card_4_right") {
        CardContent(flashFill, Phase.RESULT, 3, QuizOutcome(correct = true, points = 1), {}, {}, {})
    }

    @Test
    fun card_wrong() = shot("card_5_wrong") {
        CardContent(english, Phase.RESULT, 3, QuizOutcome(correct = false, points = 0), {}, {}, {}, nextLabel = "Vào TikTok · 5 phút")
    }

    @Test
    fun hook() = shot("intercept_hook") {
        HookScreen("TikTok", expired = false, streak = 12, balance = 7, skipsLeft = 2, strict = false, {}, {}, {}, {})
    }

    @Test
    fun hook_expiredStrict() = shot("intercept_expired_strict") {
        HookScreen("Facebook", expired = true, streak = 3, balance = 2, skipsLeft = 0, strict = true, {}, {}, {}, {})
    }

    @Test
    fun home() = shot("home") {
        HomeContent(
            HomeState(
                streak = Streak.Status(12, studiedToday = true, repairableDay = null, brokenLength = 0),
                balance = 23,
                freeUntil = 0,
                freeIsTicket = false,
                now = 1,
                guardOn = true,
                today = DayStats(intercepted = 7, learned = 4, skipped = 1, closed = 2),
                allTime = DayStats(intercepted = 52, learned = 30, skipped = 9, closed = 8),
                mastered = 14,
                totalCards = 60,
            ),
            {}, {}, {}, {},
        )
    }

    @Test
    fun home_guardOffRepairable() = shot("home_guard_off") {
        HomeContent(
            HomeState(
                streak = Streak.Status(0, studiedToday = false, repairableDay = 9, brokenLength = 6),
                balance = 3,
                freeUntil = 0,
                freeIsTicket = false,
                now = 1,
                guardOn = false,
                today = DayStats(0, 0, 0, 0),
                allTime = DayStats(10, 6, 2, 1),
                mastered = 4,
                totalCards = 60,
            ),
            {}, {}, {}, {},
        )
    }

    private val apps = listOf(
        AppEntry("com.zhiliaoapp.musically", "TikTok", true, true),
        AppEntry("com.facebook.katana", "Facebook", true, true),
        AppEntry("com.instagram.android", "Instagram", true, true),
        AppEntry("com.google.android.youtube", "YouTube", true, true),
        AppEntry("com.google.android.gm", "Gmail", true, false),
    )

    private val noop = OnboardingActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})

    @Test
    fun onboarding() {
        val settings = Settings()
        var step by mutableIntStateOf(0)
        compose.setContent {
            SnapBrainTheme {
                Box(Modifier.fillMaxSize().background(Palette.Ink)) {
                    OnboardingContent(step, settings.blocked, usageOn = step == 2, overlayOn = false, notificationsOn = false, actions = noop, appsPreview = apps)
                }
            }
        }
        repeat(5) { i ->
            step = i
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/screenshots/onboarding_$i.png")
        }
    }

    @Test
    fun settings() = shot("settings") {
        SettingsContent(
            Settings(onboarded = true, strict = false, tts = true),
            usageOn = true,
            overlayOn = false,
            actions = SettingsActions({}, {}, {}, {}, {}, {}),
            appsPreview = apps,
        )
    }
}
