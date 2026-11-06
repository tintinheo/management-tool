package com.snapbrain.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.snapbrain.MainActivity
import com.snapbrain.appContainer
import com.snapbrain.data.Source
import com.snapbrain.data.content.Card

/**
 * "Đấu trường" home-screen widget: one A/B/C question from cards you've seen (weakest first).
 * Tap an answer right on the home screen; the widget turns green or red and awards the point.
 */
class QuizWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        if (state[CARD_ID] == null) pickNext(context, id)
        val cards = context.appContainer.cards
        provideContent {
            val prefs = currentState<Preferences>()
            val card = prefs[CARD_ID]?.let { cards[it] }
            Content(card, answered = prefs[ANSWER] ?: -1, points = prefs[POINTS] ?: 0)
        }
    }

    @Composable
    private fun Content(card: Card?, answered: Int, points: Int) {
        val correct = card != null && answered == card.mcq.correct
        val bg = when {
            answered < 0 -> BG
            correct -> Color(0xFF12301F)
            else -> Color(0xFF3A1717)
        }
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(bg))
                .cornerRadius(24.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>())) {
                Text(
                    if (card == null) "SNAPBRAIN" else "${card.topic.emoji} ĐẤU TRƯỜNG",
                    style = TextStyle(color = ColorProvider(ACCENT), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            if (card == null) {
                Text("Chạm để nhận câu hỏi", style = TextStyle(color = ColorProvider(INK), fontSize = 14.sp))
                return@Column
            }
            Text(
                card.mcq.q,
                maxLines = 3,
                style = TextStyle(color = ColorProvider(INK), fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(8.dp))
            card.mcq.options.forEachIndexed { i, option ->
                OptionRow(i, option, answered, card.mcq.correct)
                Spacer(GlanceModifier.height(4.dp))
            }
            if (answered >= 0) {
                Row(
                    GlanceModifier.fillMaxWidth().padding(top = 2.dp).clickable(actionRunCallback<NextAction>()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            points > 0 -> "✓ +$points điểm"
                            correct -> "✓ Chính xác"
                            else -> "✕ Đã thêm vào ôn tập"
                        },
                        style = TextStyle(color = ColorProvider(if (correct) RIGHT else WRONG), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text("Câu tiếp ›", style = TextStyle(color = ColorProvider(ACCENT), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }

    @Composable
    private fun OptionRow(index: Int, text: String, answered: Int, correct: Int) {
        val color = when {
            answered < 0 -> SURFACE
            index == correct -> Color(0xFF1F6B3F)
            index == answered -> Color(0xFF7A2A2A)
            else -> SURFACE
        }
        val base = GlanceModifier
            .fillMaxWidth()
            .background(ColorProvider(color))
            .cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
        Box(
            if (answered < 0) base.clickable(actionRunCallback<AnswerAction>(actionParametersOf(ANSWER_PARAM to index))) else base,
        ) {
            Text(
                "${'A' + index}. $text",
                maxLines = 1,
                style = TextStyle(color = ColorProvider(INK), fontSize = 13.sp),
            )
        }
    }

    companion object {
        private val BG = Color(0xFF141417)
        private val SURFACE = Color(0xFF26262C)
        private val INK = Color(0xFFF2F0EC)
        private val ACCENT = Color(0xFFE8B84A)
        private val RIGHT = Color(0xFF4ADE80)
        private val WRONG = Color(0xFFF87171)

        val CARD_ID = stringPreferencesKey("card")
        val ANSWER = intPreferencesKey("answer")
        val POINTS = intPreferencesKey("points")
        val DAY = longPreferencesKey("day")
        val ANSWER_PARAM = ActionParameters.Key<Int>("index")

        suspend fun pickNext(context: Context, id: GlanceId) {
            val container = context.appContainer
            val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
            val current = state[CARD_ID]
            val candidates = container.study.review(limit = 6).filter { it.id != current }
            val card = candidates.randomOrNull() ?: container.cards.all.random()
            updateAppWidgetState(context, id) { prefs ->
                prefs[CARD_ID] = card.id
                prefs[ANSWER] = -1
                prefs[POINTS] = 0
                prefs[DAY] = container.clock.today()
            }
        }

        /** New question for widgets whose question is from an earlier day (called by the hourly check). */
        suspend fun refreshStale(context: Context) {
            val today = context.appContainer.clock.today()
            val widget = QuizWidget()
            GlanceAppWidgetManager(context).getGlanceIds(QuizWidget::class.java).forEach { id ->
                val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                if (state[DAY] != today) {
                    pickNext(context, id)
                    widget.update(context, id)
                }
            }
        }
    }
}

class AnswerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[QuizWidget.ANSWER_PARAM] ?: return
        val container = context.appContainer
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        if ((state[QuizWidget.ANSWER] ?: -1) >= 0) return
        val card = state[QuizWidget.CARD_ID]?.let { container.cards[it] } ?: return
        val correct = index == card.mcq.correct
        if (correct) container.haptics.success() else container.haptics.failure()
        val result = container.study.answer(card, correct, Source.WIDGET)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[QuizWidget.ANSWER] = index
            prefs[QuizWidget.POINTS] = result.points
        }
        QuizWidget().update(context, glanceId)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        QuizWidget.pickNext(context, glanceId)
        QuizWidget().update(context, glanceId)
    }
}

class QuizWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuizWidget()
}
