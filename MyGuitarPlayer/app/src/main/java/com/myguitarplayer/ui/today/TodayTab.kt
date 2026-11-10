package com.myguitarplayer.ui.today

import com.myguitarplayer.ui.common.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.myguitarplayer.data.Repos
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.data.Course
import com.myguitarplayer.data.Criterion
import com.myguitarplayer.data.Step
import com.myguitarplayer.data.Week
import com.myguitarplayer.music.StrumPattern
import com.myguitarplayer.ui.changes.ChangesScreen
import com.myguitarplayer.ui.chordcheck.ChordCheckScreen
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.metronome.MetronomeScreen
import com.myguitarplayer.ui.notes.NotesScreen
import com.myguitarplayer.ui.record.RecordScreen
import com.myguitarplayer.ui.rhythm.RhythmScreen
import com.myguitarplayer.ui.songs.SongViewerScreen
import com.myguitarplayer.ui.songs.SongsTab
import com.myguitarplayer.ui.theme.Palette
import com.myguitarplayer.ui.tuner.TunerScreen
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Today tab: what to practise now → a session's steps → the step's tool; plus each week's test. */
@Composable
fun TodayTab(onMicSetup: () -> Unit = {}) {
    val repo = Repos.progress
    val scope = rememberCoroutineScope()
    val course by remember { repo.course() }.collectAsStateWithLifecycle(initialValue = null)

    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var stepTool by rememberSaveable { mutableStateOf(false) }
    var weekNo by rememberSaveable { mutableStateOf<Int?>(null) }
    var testTool by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler(enabled = stepTool) { stepTool = false }
    BackHandler(enabled = !stepTool && sessionId != null) { sessionId = null }
    BackHandler(enabled = sessionId == null && testTool != null) { testTool = null }
    BackHandler(enabled = sessionId == null && testTool == null && weekNo != null) { weekNo = null }

    val c = course ?: return
    val today = LocalDate.now()
    val sid = sessionId
    val wn = weekNo
    val tt = testTool
    when {
        sid != null -> {
            val session = c.curriculum.session(sid)
            val week = c.curriculum.weekOf(sid)
            val step = session.steps[stepIndex.coerceIn(0, session.steps.lastIndex)]
            if (stepTool) {
                StepToolScreen(step, onBack = { stepTool = false })
            } else {
                SessionScreen(
                    week, session, stepIndex.coerceIn(0, session.steps.lastIndex),
                    onStep = { stepIndex = it },
                    onOpenTool = { stepTool = true },
                    onFinish = { r -> scope.launch { repo.finishSession(sid, r) }; sessionId = null },
                    onBack = { sessionId = null },
                )
            }
        }
        wn != null && tt != null -> StepToolScreen(criterionStep(c.curriculum.week(wn).test[tt]), onBack = { testTool = null })
        wn != null -> WeekContent(
            course = c,
            week = c.curriculum.week(wn),
            onBack = { weekNo = null },
            onStartSession = { sessionId = it; stepIndex = 0 },
            onSelfCheck = { i, on -> scope.launch { repo.setSelfCheck(c.selfKey(c.curriculum.week(wn), i), on) } },
            onDoCriterion = { testTool = it },
            onPass = { scope.launch { repo.passWeek(wn) }; weekNo = null },
        )
        else -> TodayContent(
            course = c,
            today = today,
            onStart = { sessionId = it; stepIndex = 0 },
            onOpenWeek = { weekNo = it },
            onRest = { scope.launch { repo.restToday(today) } },
            onMicSetup = onMicSetup.takeIf { !com.myguitarplayer.audio.AudioConfig.calibrated },
        )
    }
}

/** A week-test item as a one-step "lesson" so it can reuse the step tool launcher. */
fun criterionStep(c: Criterion): Step = Step(
    minutes = 0,
    title = c.text,
    text = c.text,
    tool = when (c.type) {
        "tuned" -> "TUNER"
        "chord" -> "CHORD_CHECK"
        "changes" -> "CHANGES"
        "quiz" -> "QUIZ"
        "rhythm" -> "RHYTHM"
        "rubric" -> "RECORD"
        else -> null
    },
    chord = c.chord,
    chordB = c.chordB,
    bpm = c.bpm.takeIf { it > 0 },
    pattern = c.pattern,
)

/** Opens the practice tool a step points to, preset with its chord / tempo / pattern / song. */
@Composable
fun StepToolScreen(step: Step, onBack: () -> Unit) {
    when (step.tool) {
        "TUNER" -> TunerScreen(onBack)
        "METRONOME" -> MetronomeScreen(
            onBack,
            initialBpm = step.bpm ?: 60,
            initialPattern = StrumPattern.entries.firstOrNull { it.name == step.pattern || it.strokes == step.pattern } ?: StrumPattern.DOWN_4,
        )
        "CHORD_CHECK" -> ChordCheckScreen(step.chord ?: "Em", onBack)
        "CHANGES" -> ChangesScreen(step.chord ?: "Em", step.chordB ?: "Am", onBack)
        "NOTES" -> NotesScreen(onBack)
        "QUIZ" -> NotesScreen(onBack, startQuiz = true)
        "RHYTHM" -> RhythmScreen(
            onBack,
            initialBpm = step.bpm ?: 70,
            initialPattern = StrumPattern.entries.firstOrNull { it.name == step.pattern || it.strokes == step.pattern } ?: StrumPattern.DOWN_UP,
        )
        "RECORD" -> RecordScreen(onBack)
        "SONG" -> if (step.song == null) SongsTab() else SampleSong(step.song, onBack)
        else -> LaunchedEffect(Unit) { onBack() }
    }
}

@Composable
private fun SampleSong(file: String, onBack: () -> Unit) {
    val repo = Repos.songs
    var id by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(file) {
        repo.seedSamples()
        id = repo.bySample(file)?.id
    }
    id?.let { SongViewerScreen(repo, it, onBack = onBack, onEdit = null, onDeleted = onBack) }
}

@Composable
fun TodayContent(
    course: Course,
    today: LocalDate,
    onStart: (String) -> Unit,
    onOpenWeek: (Int) -> Unit,
    onRest: () -> Unit,
    /** Shown until the mic has been set up on this phone. */
    onMicSetup: (() -> Unit)? = null,
) {
    val week = course.currentWeek
    val next = course.nextSession
    val practiced = today in course.record.practiceDays
    val rested = today in course.record.restDays
    val streak = course.streak(today)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(
            "Hôm nay",
            if (course.finished) "Đã xong lộ trình 8 tuần" else "Tuần ${week.number} · ${week.title}",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (streak > 0) Palette.Accent else Palette.Muted)
            Text(
                "  " + when {
                    streak == 0 -> "Bắt đầu chuỗi ngày tập từ hôm nay"
                    else -> "$streak ngày tập liên tiếp"
                } + when {
                    practiced -> " · hôm nay đã tập ✓"
                    rested -> " · hôm nay nghỉ tay"
                    else -> ""
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        onMicSetup?.let { open ->
            Card {
                Text("Hiệu chỉnh micro cho máy này", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1 phút, làm một lần: đo tiếng ồn phòng và thử quạt, để app nghe hợp âm và nhịp chính xác hơn.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                OutlinedButton(onClick = open) { Text("Hiệu chỉnh ngay") }
            }
        }

        when {
            course.finished -> Card {
                Text("Chúc mừng! 🎸", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Bạn đã đi hết 8 tuần. Giữ thói quen: mỗi ngày lên dây, chạy 1 vòng chuyển hợp âm, rồi đệm một bài bạn thích. " +
                        "Mỗi tuần thêm một bài mới vào tab Bài hát.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            next != null -> Card {
                val n = week.sessions.indexOf(next) + 1
                Text("Buổi $n/${week.sessions.size} · ${next.minutes} phút", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Text(next.title, style = MaterialTheme.typography.headlineSmall)
                Text(next.goal, style = MaterialTheme.typography.bodyLarge)
                next.steps.forEach { s ->
                    Row {
                        Text("${s.minutes}'", style = MaterialTheme.typography.titleMedium, color = Palette.Accent, modifier = Modifier.width(40.dp))
                        Text(s.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(onClick = { onStart(next.id) }, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)) {
                    Text(if (practiced) "Tập thêm buổi tiếp theo" else "Bắt đầu buổi tập")
                }
            }
            else -> Card {
                Text("Bài kiểm tra tuần ${week.number}", style = MaterialTheme.typography.headlineSmall)
                Text("Bạn đã xong ${week.sessions.size} buổi. Đạt các mục sau để mở tuần tiếp theo:", style = MaterialTheme.typography.bodyLarge)
                CriteriaList(course, week, onSelfCheck = null, onDo = null)
                Button(onClick = { onOpenWeek(week.number) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Làm bài kiểm tra") }
            }
        }

        if (!practiced && !rested && !course.finished) {
            OutlinedButton(onClick = onRest, modifier = Modifier.fillMaxWidth()) { Text("Hôm nay nghỉ tay (giữ chuỗi ngày)") }
            Text(
                "Đầu ngón đau rát trong 1–2 tuần đầu là bình thường. Đau quá thì nghỉ một ngày — da sẽ chai lại và hết đau.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
        }

        Text("Lộ trình 8 tuần", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
        course.curriculum.weeks.forEach { w ->
            val unlocked = course.isUnlocked(w)
            val passed = w.number in course.record.passedWeeks
            Card(if (unlocked) Modifier.clickable { onOpenWeek(w.number) } else Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            passed -> Icons.Rounded.CheckCircle
                            unlocked -> Icons.Rounded.RadioButtonUnchecked
                            else -> Icons.Rounded.Lock
                        },
                        null,
                        tint = when {
                            passed -> Palette.Right
                            unlocked -> Palette.Accent
                            else -> Palette.Muted
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Tuần ${w.number} · ${w.title}", style = MaterialTheme.typography.titleMedium, color = if (unlocked) Palette.Text else Palette.Muted)
                        Text(
                            if (unlocked) "${course.doneIn(w)}/${w.sessions.size} buổi" + if (passed) " · đã qua kiểm tra" else "" else w.goal,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CriteriaList(course: Course, week: Week, onSelfCheck: ((Int, Boolean) -> Unit)?, onDo: ((Int) -> Unit)?) {
    week.test.forEachIndexed { i, c ->
        val met = course.met(week, i)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.type == "self" && onSelfCheck != null) {
                Checkbox(
                    checked = met,
                    onCheckedChange = { onSelfCheck(i, it) },
                    colors = CheckboxDefaults.colors(checkedColor = Palette.Right, uncheckedColor = Palette.Muted, checkmarkColor = Palette.Ink),
                )
            } else {
                StatusIcon(if (met) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, met)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(c.text, style = MaterialTheme.typography.bodyLarge)
                progressNote(course, c)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) }
            }
            if (!met && onDo != null && c.type != "self") TextButton(onClick = { onDo(i) }) { Text("Làm ngay") }
        }
    }
}

private fun progressNote(course: Course, c: Criterion): String? = when (c.type) {
    "changes" -> "Kỷ lục: ${course.record.bestChanges[setOf(c.chord, c.chordB)] ?: 0}/${c.min} lần"
    "quiz" -> "Tốt nhất: ${course.record.bestQuiz}/10"
    "rhythm" -> course.record.rhythm.filter { it.pattern == c.pattern && it.bpm >= c.bpm }.minOfOrNull { it.meanAbsMs }
        ?.let { "Tốt nhất ở ≥ ${c.bpm} BPM: lệch $it ms (cần ≤ ${c.maxMs} ms)" } ?: "Chưa chấm ở ${c.bpm} BPM"
    "rubric" -> if (course.record.rubricMins.isEmpty()) "Ghi âm một bài rồi tự chấm 5 tiêu chí" else null
    "self" -> "Tự đánh dấu khi bạn làm được thật"
    else -> null
}

@Composable
private fun StatusIcon(icon: ImageVector, met: Boolean) =
    Icon(icon, null, tint = if (met) Palette.Right else Palette.Muted, modifier = Modifier.padding(12.dp).size(24.dp))

@Composable
fun WeekContent(
    course: Course,
    week: Week,
    onBack: () -> Unit,
    onStartSession: (String) -> Unit,
    onSelfCheck: (Int, Boolean) -> Unit,
    onDoCriterion: (Int) -> Unit,
    onPass: () -> Unit,
) {
    val passed = week.number in course.record.passedWeeks
    val met = course.testMet(week)
    val last = week.number == course.curriculum.weeks.last().number
    var confirmSkip by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Tuần ${week.number}", week.title, onBack)
        Card { Text(week.goal, style = MaterialTheme.typography.bodyLarge) }
        Text("Các buổi tập", style = MaterialTheme.typography.titleLarge)
        week.sessions.forEachIndexed { i, s ->
            val done = s.id in course.record.doneSessions
            Card(Modifier.clickable { onStartSession(s.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        null,
                        tint = if (done) Palette.Right else Palette.Muted,
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Buổi ${i + 1} · ${s.title}", style = MaterialTheme.typography.titleMedium)
                        Text("${s.minutes} phút · ${s.goal}", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    }
                }
            }
        }
        Text("Kiểm tra để mở tuần sau", style = MaterialTheme.typography.titleLarge)
        Card {
            CriteriaList(course, week, onSelfCheck, onDoCriterion)
            when {
                passed -> Text("Đã qua ✓", style = MaterialTheme.typography.titleMedium, color = Palette.Right)
                met -> Button(onClick = onPass, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (last) "Hoàn thành khoá học" else "Đạt! Mở tuần ${week.number + 1}")
                }
                else -> TextButton(onClick = { confirmSkip = true }) { Text("Tôi đã biết phần này — bỏ qua kiểm tra") }
            }
        }
    }
    if (confirmSkip) {
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("Bỏ qua kiểm tra tuần ${week.number}?") },
            text = { Text("Chỉ nên bỏ qua nếu bạn đã chơi được những mục trên. Tuần sau xây trên nền tuần này.") },
            confirmButton = { TextButton(onClick = { confirmSkip = false; onPass() }) { Text("Bỏ qua") } },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("Ở lại tập") } },
            containerColor = Palette.Surface,
        )
    }
}
