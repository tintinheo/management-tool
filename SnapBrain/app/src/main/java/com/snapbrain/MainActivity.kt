package com.snapbrain

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.snapbrain.data.DayStats
import com.snapbrain.domain.Streak
import com.snapbrain.intercept.GuardPermissions
import com.snapbrain.intercept.GuardService
import com.snapbrain.intercept.PhoneSetup
import com.snapbrain.ui.Speaker
import com.snapbrain.ui.ai.AiScreen
import com.snapbrain.ui.feed.FeedScreen
import com.snapbrain.ui.home.HomeContent
import com.snapbrain.ui.home.HomeState
import com.snapbrain.ui.onboarding.OnboardingActions
import com.snapbrain.ui.onboarding.OnboardingContent
import com.snapbrain.ui.saver.SaverScreen
import com.snapbrain.ui.settings.SettingsActions
import com.snapbrain.ui.settings.SettingsContent
import com.snapbrain.ui.theme.Palette
import com.snapbrain.ui.theme.SnapBrainTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Re-read on every resume: the user flips these in system settings, outside the app. */
    private var usageOn by mutableStateOf(false)
    private var overlayOn by mutableStateOf(false)
    private var notificationsOn by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleOpen(intent)
        setContent { SnapBrainTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    override fun onResume() {
        super.onResume()
        usageOn = GuardPermissions.usageAccess(this)
        overlayOn = GuardPermissions.overlay(this)
        if (appContainer.settings.value.onboarded) GuardService.start(this)
        notificationsOn = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun handleOpen(intent: Intent?) {
        screen = when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_FEED -> Screen.FEED
            OPEN_SAVER -> Screen.SAVER
            OPEN_HOME -> Screen.HOME
            else -> screen
        }
    }

    @Composable
    private fun App() {
        val container = appContainer
        val settings by container.settings.state.collectAsState()
        val context = LocalContext.current
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsOn = it
        }

        if (!settings.onboarded) {
            var step by rememberSaveable { mutableIntStateOf(0) }
            BackHandler(step > 0) { step-- }
            OnboardingContent(
                step = step,
                blocked = settings.blocked,
                usageOn = usageOn,
                overlayOn = overlayOn,
                notificationsOn = notificationsOn,
                actions = OnboardingActions(
                    next = { step++ },
                    finish = {
                        container.settings.update { it.copy(onboarded = true) }
                        GuardService.start(context)
                    },
                    toggleApp = { pkg, on -> container.settings.update { it.copy(blocked = if (on) it.blocked + pkg else it.blocked - pkg) } },
                    openUsageAccess = { GuardPermissions.openUsageAccess(context) },
                    openOverlay = { GuardPermissions.openOverlay(context) },
                    openAutostart = { PhoneSetup.openAutostart(context) },
                    openPopups = { PhoneSetup.openOtherPermissions(context) },
                    openBattery = { PhoneSetup.openBattery(context) },
                    requestNotifications = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                ),
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
            return
        }

        val speaker = remember(settings.tts) { if (settings.tts) Speaker(context) else null }
        DisposableEffect(speaker) { onDispose { speaker?.shutdown() } }

        if (screen == Screen.SAVER) {
            BackHandler { screen = Screen.HOME }
            SaverScreen(container, onDone = { screen = Screen.HOME }, modifier = Modifier.fillMaxSize().safeDrawingPadding())
            return
        }

        if (screen == Screen.AI) {
            BackHandler { screen = Screen.SETTINGS }
            AiScreen(
                container,
                onBack = { screen = Screen.SETTINGS },
                onOpenFeed = { screen = Screen.FEED },
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
            return
        }

        BackHandler(screen != Screen.HOME) { screen = Screen.HOME }
        Scaffold(
            containerColor = Palette.Ink,
            bottomBar = {
                NavigationBar(containerColor = Palette.Surface) {
                    Tab(Screen.HOME, "Hôm nay", Icons.Rounded.Home)
                    Tab(Screen.FEED, "Lướt", Icons.Rounded.Bolt)
                    Tab(Screen.SETTINGS, "Cài đặt", Icons.Rounded.Settings)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.HOME -> Home()
                    Screen.FEED -> FeedScreen(container, onSpeak = speaker?.let { s -> s::speak })
                    else -> SettingsContent(
                        settings = settings,
                        usageOn = usageOn,
                        overlayOn = overlayOn,
                        actions = SettingsActions(
                            update = container.settings::update,
                            openUsageAccess = { GuardPermissions.openUsageAccess(context) },
                            openOverlay = { GuardPermissions.openOverlay(context) },
                            openAutostart = { PhoneSetup.openAutostart(context) },
                            openPopups = { PhoneSetup.openOtherPermissions(context) },
                            openBattery = { PhoneSetup.openBattery(context) },
                            openAi = { screen = Screen.AI },
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun Home() {
        val container = appContainer
        val study = container.study
        val settings by container.settings.state.collectAsState()
        val streak by study.streak.collectAsState(initial = Streak.Status(0, false, null, 0))
        val balance by study.balance.collectAsState(initial = 0)
        val today by study.todayStats.collectAsState(initial = DayStats(0, 0, 0, 0))
        val allTime by study.allTimeStats.collectAsState(initial = DayStats(0, 0, 0, 0))
        val mastered by study.masteredCount.collectAsState(initial = 0)
        val scope = rememberCoroutineScope()
        HomeContent(
            state = HomeState(
                streak = streak,
                balance = balance,
                freeUntil = settings.freeUntil,
                freeIsTicket = settings.freeIsTicket,
                now = container.clock.now(),
                guardOn = usageOn && overlayOn,
                today = today,
                allTime = allTime,
                mastered = mastered,
                totalCards = container.cards.all.size,
            ),
            onEnableGuard = { GuardPermissions.openMissing(this) },
            onOpenFeed = { screen = Screen.FEED },
            onSaver = { screen = Screen.SAVER },
            onBuyTicket = { scope.launch { study.buyTickets(1) } },
        )
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.Tab(target: Screen, label: String, icon: ImageVector) {
        NavigationBarItem(
            selected = screen == target,
            onClick = { screen = target },
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(label) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Palette.OnAccent,
                indicatorColor = Palette.Accent,
                selectedTextColor = Palette.Text,
                unselectedIconColor = Palette.Muted,
                unselectedTextColor = Palette.Muted,
            ),
        )
    }

    enum class Screen { HOME, FEED, SETTINGS, SAVER, AI }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_FEED = "feed"
        const val OPEN_SAVER = "saver"
        const val OPEN_HOME = "home"
    }
}
