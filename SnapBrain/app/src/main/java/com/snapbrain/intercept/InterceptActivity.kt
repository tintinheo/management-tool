package com.snapbrain.intercept

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.snapbrain.appContainer
import com.snapbrain.data.Source
import com.snapbrain.data.SuggestedApps
import com.snapbrain.data.content.Card
import com.snapbrain.data.db.InterceptEvent
import com.snapbrain.domain.Rules
import com.snapbrain.ui.card.CardPlayer
import com.snapbrain.ui.theme.SnapBrainTheme
import kotlinx.coroutines.launch

/**
 * Full-screen cover shown over a blocked app: offer one card (big button), a ticket, a limited skip,
 * or leaving. Lives in its own task and finishes as soon as it's hidden, so it never lingers in Recents.
 */
class InterceptActivity : ComponentActivity() {

    private var targetPkg by mutableStateOf("")
    private var expired by mutableStateOf(false)
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        val container = appContainer
        container.haptics.knock()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeApp()
        })

        setContent {
            SnapBrainTheme {
                val settings by container.settings.state.collectAsState()
                val balance by container.study.balance.collectAsState(initial = 0)
                val streak by container.study.streak.collectAsState(initial = null)
                var card by remember { mutableStateOf<Card?>(null) }
                var learning by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val appName = remember(targetPkg) { appLabel(targetPkg) }
                val today = container.clock.today()

                LaunchedEffect(learning) { if (learning && card == null) card = container.study.nextCard() }

                val current = card
                if (learning && current != null) {
                    CardPlayer(
                        card = current,
                        active = true,
                        onAnswer = { correct ->
                            val result = container.study.answer(current, correct, Source.INTERCEPT)
                            grantPass(InterceptEvent.LEARNED)
                            result.points
                        },
                        onNext = ::openTarget,
                        nextLabel = "Vào $appName · ${Rules.PASS_MINUTES} phút",
                        haptics = container.haptics,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                } else {
                    HookScreen(
                        appName = appName,
                        expired = expired,
                        streak = streak?.current ?: 0,
                        balance = balance,
                        skipsLeft = Rules.skipsLeft(settings.strict, settings.skipsUsedDay, settings.skipsUsed, today),
                        strict = settings.strict,
                        onLearn = { container.haptics.tick(); learning = true },
                        onTicket = {
                            scope.launch {
                                if (container.study.buyTickets(1)) {
                                    log(InterceptEvent.TICKET)
                                    openTarget()
                                }
                            }
                        },
                        onSkip = ::skip,
                        onClose = ::closeApp,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // Hidden without a choice (Home, Recents): counts as leaving the app. Never linger.
        if (!decided && !isChangingConfigurations) log(InterceptEvent.CLOSED)
        if (!isChangingConfigurations) finish()
    }

    private fun readIntent(intent: Intent) {
        targetPkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        expired = intent.getBooleanExtra(EXTRA_EXPIRED, false)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: SuggestedApps.ALL[pkg] ?: "ứng dụng"

    private fun log(outcome: String) {
        if (decided) return
        decided = true
        val study = appContainer.study
        val pkg = targetPkg
        lifecycleScope.launch { study.logIntercept(pkg, outcome) }
    }

    private fun grantPass(outcome: String) {
        val container = appContainer
        val now = container.clock.now()
        container.settings.update { it.copy(freeUntil = Rules.pass(it.freeUntil, now), freeIsTicket = false) }
        log(outcome)
    }

    private fun skip() {
        val container = appContainer
        val today = container.clock.today()
        container.settings.update {
            val used = if (it.skipsUsedDay == today) it.skipsUsed else 0
            it.copy(skipsUsedDay = today, skipsUsed = used + 1)
        }
        grantPass(InterceptEvent.SKIPPED)
        openTarget()
    }

    private fun openTarget() {
        decided = true
        packageManager.getLaunchIntentForPackage(targetPkg)?.let {
            runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    private fun closeApp() {
        log(InterceptEvent.CLOSED)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_EXPIRED = "expired"

        @Volatile
        var visible = false
            private set

        fun intent(context: Context, pkg: String, expired: Boolean): Intent =
            Intent(context, InterceptActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_EXPIRED, expired)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
