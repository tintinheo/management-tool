package com.myguitarplayer.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myguitarplayer.data.Repos
import androidx.compose.ui.unit.dp
import com.myguitarplayer.audio.ChordPlayer
import com.myguitarplayer.audio.MicStream
import com.myguitarplayer.audio.Yin
import com.myguitarplayer.data.Milestone
import com.myguitarplayer.music.Note
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.KeepScreenOn
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.common.rememberMicPermission
import com.myguitarplayer.ui.lesson.DotKind
import com.myguitarplayer.ui.lesson.Fret
import com.myguitarplayer.ui.lesson.FretDot
import com.myguitarplayer.ui.lesson.Fretboard
import com.myguitarplayer.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

const val QUIZ_LENGTH = 10
const val QUIZ_FRETS = 3

/** A note to find: string + fret in the first position (frets 0–3), natural notes only. */
data class NoteQuestion(val string: Int, val fret: Int) {
    val midi: Int get() = Fret.midi(string, fret)
    val name: String get() = Fret.noteName(string, fret)
}

enum class Verdict { RIGHT, WRONG_STRING, WRONG }

/** The quiz rules, separate from the UI so they can be tested. */
class NoteQuiz(seed: Long = System.nanoTime()) {
    val questions: List<NoteQuestion> = run {
        val pool = Fret.naturalNotes(QUIZ_FRETS).map { NoteQuestion(it.string, it.fret) }
        val rnd = Random(seed)
        // No two questions in a row on the same spot.
        buildList {
            while (size < QUIZ_LENGTH) {
                val q = pool[rnd.nextInt(pool.size)]
                if (lastOrNull() != q) add(q)
            }
        }
    }

    /** By tap: the exact spot. The same note name elsewhere is "right name, wrong string". */
    fun judgeTap(q: NoteQuestion, string: Int, fret: Int): Verdict = when {
        string == q.string && fret == q.fret -> Verdict.RIGHT
        Fret.noteName(string, fret) == q.name -> Verdict.WRONG_STRING
        else -> Verdict.WRONG
    }

    /** By ear: the played pitch must be the same note in the same octave (any string that gives it is fine). */
    fun judgeMidi(q: NoteQuestion, midi: Int): Verdict = when {
        midi == q.midi -> Verdict.RIGHT
        Math.floorMod(midi - q.midi, 12) == 0 -> Verdict.WRONG_STRING
        else -> Verdict.WRONG
    }
}

data class QuizUi(
    val index: Int,
    val question: NoteQuestion,
    val correct: Int,
    val answered: Verdict?,
    val tapped: Pair<Int, Int>?,
    val hint: Boolean,
    val finished: Boolean,
)

@Composable
fun NotesScreen(onBack: () -> Unit, startQuiz: Boolean = false) {
    KeepScreenOn()
    val scope = rememberCoroutineScope()
    val player = remember { ChordPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val (granted, ask) = rememberMicPermission()

    var quizMode by rememberSaveable { mutableStateOf(startQuiz) }
    var showNames by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var quiz by remember { mutableStateOf(NoteQuiz()) }
    var ui by remember { mutableStateOf<QuizUi?>(null) }
    var heard by remember { mutableStateOf<String?>(null) }

    fun newQuiz() {
        quiz = NoteQuiz()
        ui = QuizUi(0, quiz.questions[0], 0, null, null, hint = false, finished = false)
    }
    LaunchedEffect(quizMode) { if (quizMode) newQuiz() else ui = null }

    fun answer(v: Verdict, tapped: Pair<Int, Int>?) {
        val u = ui ?: return
        if (u.answered == Verdict.RIGHT || u.finished) return
        // First try counts; a hint means it doesn't.
        val first = u.answered == null
        ui = u.copy(answered = v, tapped = tapped, correct = u.correct + if (v == Verdict.RIGHT && first && !u.hint) 1 else 0)
    }

    fun next() {
        val u = ui ?: return
        val i = u.index + 1
        if (i >= QUIZ_LENGTH) {
            ui = u.copy(finished = true)
            scope.launch { Repos.progress.mark(Milestone.QUIZ, value = u.correct, once = false) }
        } else {
            ui = QuizUi(i, quiz.questions[i], u.correct, null, null, hint = false, finished = false)
        }
    }

    // Listen while a question is open: a steady pitch (3 frames on the same note) answers it.
    val listening = granted && quizMode && ui?.let { !it.finished && it.answered != Verdict.RIGHT } == true
    if (listening) {
        LaunchedEffect(ui?.index) {
            val yin = Yin(MicStream.SAMPLE_RATE, 4096)
            var last = -1
            var run = 0
            MicStream.windows()
                .map { w -> yin.detect(w)?.takeIf { it.confidence > 0.85 }?.let { Note.midiOf(it.freq).roundToInt() } }
                .flowOn(Dispatchers.Default)
                .collect { m ->
                    if (m == null) { run = 0; return@collect }
                    run = if (m == last) run + 1 else 1
                    last = m
                    if (run == 3) {
                        heard = Note.label(m)
                        ui?.let { answer(quiz.judgeMidi(it.question, m), null) }
                    }
                }
        }
    }

    NotesContent(
        quizMode = quizMode,
        showNames = showNames,
        selected = selected,
        quiz = ui,
        micGranted = granted,
        heard = heard,
        onAskMic = ask,
        onMode = { quizMode = it; heard = null },
        onShowNames = { showNames = it },
        onTap = { s, f ->
            scope.launch { player.playNote(Fret.midi(s, f)) }
            if (quizMode) ui?.let { answer(quiz.judgeTap(it.question, s, f), s to f) } else selected = s to f
        },
        onHint = { ui = ui?.copy(hint = true) },
        onNext = ::next,
        onRestart = ::newQuiz,
        onBack = onBack,
    )
}

@Composable
fun NotesContent(
    quizMode: Boolean,
    showNames: Boolean,
    selected: Pair<Int, Int>?,
    quiz: QuizUi?,
    micGranted: Boolean,
    heard: String?,
    onAskMic: () -> Unit,
    onMode: (Boolean) -> Unit,
    onShowNames: (Boolean) -> Unit,
    onTap: (Int, Int) -> Unit,
    onHint: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Palette.Accent,
        selectedLabelColor = Palette.OnAccent,
        labelColor = Palette.Text,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Nốt trên cần đàn", "Dây nào, ngăn nào, ngón nào ra nốt gì", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !quizMode, onClick = { onMode(false) }, label = { Text("Khám phá") }, colors = chip)
            FilterChip(selected = quizMode, onClick = { onMode(true) }, label = { Text("Đố nốt") }, colors = chip)
        }

        if (!quizMode) {
            val dots = buildList {
                if (showNames) addAll(Fret.naturalNotes(5))
                selected?.let { (s, f) -> add(FretDot(s, f, Fret.noteName(s, f), DotKind.NORMAL)) }
            }
            Fretboard(dots, frets = 5, rowHeight = 34.dp, onTap = onTap)
            FilterChip(selected = showNames, onClick = { onShowNames(!showNames) }, label = { Text("Hiện tên nốt tự nhiên") }, colors = chip)
            Card {
                if (selected == null) {
                    Text("Chạm vào một vị trí trên cần đàn để nghe và xem tên nốt.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    val (s, f) = selected
                    Text(Note.label(Fret.midi(s, f)), style = MaterialTheme.typography.displaySmall, color = Palette.Accent)
                    Text(positionText(s, f), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Quy tắc: mỗi ngăn cao hơn nửa cung. Giữa B–C và E–F chỉ cách 1 ngăn; các nốt khác cách 2 ngăn " +
                        "(ngăn ở giữa là nốt thăng #). Ở thế bấm đầu: ngăn 1 dùng ngón trỏ, ngăn 2 ngón giữa, ngăn 3 ngón áp út.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
            }
        } else if (quiz != null) {
            QuizBlock(quiz, micGranted, heard, onAskMic, onTap, onHint, onNext, onRestart)
        }
    }
}

fun positionText(s: Int, f: Int): String =
    "Dây $s · " + if (f == 0) "dây buông, không bấm" else "ngăn $f · ngón ${minOf(f, 4)} (${Fret.FINGER_NAMES[minOf(f, 4)]})"

@Composable
private fun QuizBlock(
    quiz: QuizUi,
    micGranted: Boolean,
    heard: String?,
    onAskMic: () -> Unit,
    onTap: (Int, Int) -> Unit,
    onHint: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
) {
    val q = quiz.question
    if (quiz.finished) {
        Card {
            Text("${quiz.correct}/$QUIZ_LENGTH", style = MaterialTheme.typography.displaySmall, color = Palette.Accent)
            Text(
                when {
                    quiz.correct >= 9 -> "Xuất sắc — bạn đã thuộc thế bấm đầu."
                    quiz.correct >= 7 -> "Tốt! Ôn thêm vài nốt còn nhầm rồi làm lại."
                    else -> "Mở chế độ Khám phá, đọc to tên nốt từng dây, rồi thử lại."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Làm lượt mới") }
        }
        return
    }
    Text("Câu ${quiz.index + 1}/$QUIZ_LENGTH · đúng ${quiz.correct}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    Text("Tìm nốt ${q.name} trên dây ${q.string}", style = MaterialTheme.typography.headlineSmall)
    val dots = buildList {
        if (quiz.hint) addAll(Fret.naturalNotes(QUIZ_FRETS, q.string..q.string).filter { it.label != q.name })
        quiz.tapped?.let { (s, f) ->
            if (quiz.answered != Verdict.RIGHT) add(FretDot(s, f, Fret.noteName(s, f), DotKind.GHOST))
        }
        if (quiz.answered == Verdict.RIGHT) add(FretDot(q.string, q.fret, q.name, DotKind.ROOT))
    }
    Fretboard(dots, frets = QUIZ_FRETS + 1, rowHeight = 34.dp, onTap = onTap)
    Card {
        when (quiz.answered) {
            null -> Text(
                if (micGranted) "Chạm vào vị trí đúng — hoặc đánh nốt đó trên đàn, micro sẽ nghe." else "Chạm vào vị trí đúng trên cần đàn.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Verdict.RIGHT -> {
                Text("Đúng! ${q.name} = ${positionText(q.string, q.fret)}", style = MaterialTheme.typography.titleMedium, color = Palette.Right)
            }
            Verdict.WRONG_STRING -> Text(
                "Đúng tên ${q.name} nhưng chưa đúng chỗ — tìm trên dây ${q.string}" + (heard?.let { " (nghe được $it)" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Accent,
            )
            Verdict.WRONG -> Text(
                "Chưa đúng" + (heard?.let { " — nghe được $it" } ?: "") + ". Thử lại hoặc xem gợi ý.",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Wrong,
            )
        }
        if (!micGranted) TextButton(onClick = onAskMic) { Text("Cho phép micro để trả lời bằng tiếng đàn") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onHint, enabled = !quiz.hint && quiz.answered != Verdict.RIGHT, modifier = Modifier.weight(1f).height(48.dp)) {
            Text("Gợi ý")
        }
        Button(onClick = onNext, modifier = Modifier.weight(1f).height(48.dp)) {
            Text(if (quiz.answered == Verdict.RIGHT) "Câu tiếp" else "Bỏ qua")
        }
    }
}
