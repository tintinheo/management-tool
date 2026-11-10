package com.myguitarplayer.ui.progress

import com.myguitarplayer.ui.common.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myguitarplayer.data.Repos
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myguitarplayer.data.ChangeScore
import com.myguitarplayer.data.Course
import com.myguitarplayer.data.Recording
import com.myguitarplayer.data.RhythmScore
import com.myguitarplayer.data.SessionLog
import com.myguitarplayer.music.Chords
import com.myguitarplayer.ui.common.Card
import com.myguitarplayer.ui.common.ScreenHeader
import com.myguitarplayer.ui.record.RecordScreen
import com.myguitarplayer.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything the Progress tab shows, computed from the raw logs. */
data class ProgressModel(
    val sessions: Int,
    val minutes: Int,
    val streak: Int,
    val chordsPassed: Set<String>,
    val minutesByDay: List<Point>,
    /** Chord pairs with one-minute-changes data, most recently practised first. */
    val pairs: List<Pair<String, String>>,
    val changesByPair: Map<Pair<String, String>, List<Point>>,
    val changeTargets: Map<Set<String>, Int>,
    val rhythm: List<Point>,
    val rhythmPassed: Int,
    val recordings: Int,
    val recordingsRated: Int,
)

private val DAY = DateTimeFormatter.ofPattern("dd/MM")

/** Vietnamese weekday short names: T2 … T7, CN. */
fun weekdayShort(d: LocalDate): String = if (d.dayOfWeek.value == 7) "CN" else "T${d.dayOfWeek.value + 1}"

fun buildProgress(
    course: Course,
    logs: List<SessionLog>,
    changes: List<ChangeScore>,
    rhythm: List<RhythmScore>,
    recordings: List<Recording>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): ProgressModel {
    fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val minutesOf = course.curriculum.sessions.associate { it.id to it.minutes }
    val perDay = logs.groupBy { day(it.at) }.mapValues { (_, l) -> l.sumOf { minutesOf[it.sessionId] ?: 0 } }
    val minutesByDay = (13 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        val m = perDay[d] ?: 0
        Point(weekdayShort(d), m.toFloat(), "${weekdayShort(d)} ${d.format(DAY)}: $m phút")
    }

    // Best count per day for each pair; a pair is keyed in the order of its most recent round.
    val byPair = changes.groupBy { setOf(it.chordA, it.chordB) }
    val pairs = byPair.entries.sortedByDescending { (_, v) -> v.maxOf { it.at } }.map { (_, v) ->
        val last = v.maxBy { it.at }
        last.chordA to last.chordB
    }
    val changesByPair = pairs.associateWith { p ->
        byPair.getValue(setOf(p.first, p.second))
            .groupBy { day(it.at) }
            .toSortedMap()
            .map { (d, v) ->
                val best = v.maxOf { it.count }
                Point(d.format(DAY), best.toFloat(), "${d.format(DAY)}: $best lần/phút (${v.size} vòng)")
            }
    }
    val targets = course.curriculum.weeks.flatMap { it.test }
        .filter { it.type == "changes" }
        .associate { setOf(it.chord.orEmpty(), it.chordB.orEmpty()) to it.min }

    val takes = rhythm.sortedBy { it.at }.takeLast(20)
    val rhythmPoints = takes.mapIndexed { i, r ->
        Point(
            day(r.at).format(DAY),
            r.meanAbsMs.toFloat(),
            "Lần ${i + 1} · ${day(r.at).format(DAY)} · ${r.bpm} BPM: lệch ${r.meanAbsMs} ms, nghe được ${r.hitPercent}%",
        )
    }
    return ProgressModel(
        sessions = logs.size,
        minutes = logs.sumOf { minutesOf[it.sessionId] ?: 0 },
        streak = course.streak(today),
        chordsPassed = course.record.chordsPassed,
        minutesByDay = minutesByDay,
        pairs = pairs,
        changesByPair = changesByPair,
        changeTargets = targets,
        rhythm = rhythmPoints,
        rhythmPassed = rhythm.count { it.passes },
        recordings = recordings.size,
        recordingsRated = recordings.count { it.rated },
    )
}

@Composable
fun ProgressTab() {
    val progress = Repos.progress
    val practice = Repos.practice
    val db = remember { com.myguitarplayer.platform.Platform.current.db }
    val course by remember { progress.course() }.collectAsStateWithLifecycle(initialValue = null)
    val logs by remember { db.progress().logs() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val changes by remember { db.changes().all() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val rhythm by remember { practice.rhythm() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val recordings by remember { practice.recordings() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var openRecord by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = openRecord) { openRecord = false }

    if (openRecord) {
        RecordScreen(onBack = { openRecord = false })
        return
    }
    val c = course ?: return
    ProgressContent(buildProgress(c, logs, changes, rhythm, recordings, LocalDate.now()), onOpenRecordings = { openRecord = true })
}

@Composable
fun ProgressContent(model: ProgressModel, onOpenRecordings: () -> Unit) {
    var pair by remember(model.pairs) { mutableStateOf(model.pairs.firstOrNull()) }
    val chip = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Palette.Accent,
        selectedLabelColor = Palette.OnAccent,
        labelColor = Palette.Text,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Tiến độ", "Đo được thì mới thấy mình tiến bộ")
        Card {
            Row {
                StatTile("Buổi đã tập", "${model.sessions}", null, Modifier.weight(1f))
                StatTile("Phút tập", "${model.minutes}", null, Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 8.dp)) {
                StatTile("Chuỗi ngày", "${model.streak}", if (model.streak > 0) "ngày liên tiếp" else null, Modifier.weight(1f))
                StatTile("Hợp âm đã qua", "${model.chordsPassed.size}/${Chords.ALL.size}", "chấm bằng micro", Modifier.weight(1f))
            }
        }

        Card {
            Text("Phút tập · 14 ngày qua", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tổng ${model.minutesByDay.sumOf { it.y.toInt() }} phút. Đều đặn mỗi ngày quan trọng hơn tập dồn.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            ColumnChart(model.minutesByDay, unit = "")
        }

        Card {
            Text("Chuyển hợp âm trong 1 phút", style = MaterialTheme.typography.titleMedium)
            if (model.pairs.isEmpty()) {
                Text("Chưa có vòng nào. Mở Công cụ → Chuyển hợp âm 1 phút.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            } else {
                Text("Kỷ lục mỗi ngày, số lần đổi qua lại trong 60 giây.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.pairs.forEach { p ->
                        FilterChip(selected = p == pair, onClick = { pair = p }, label = { Text("${p.first} ↔ ${p.second}") }, colors = chip)
                    }
                }
                pair?.let { p ->
                    val target = model.changeTargets[setOf(p.first, p.second)]
                    LineChart(
                        model.changesByPair[p].orEmpty(),
                        reference = target?.let { Reference(it.toFloat(), "mục tiêu $it") },
                    )
                }
            }
        }

        Card {
            Text("Độ đều nhịp quạt", style = MaterialTheme.typography.titleMedium)
            if (model.rhythm.isEmpty()) {
                Text("Chưa có lần chấm nào. Mở Công cụ → Chấm nhịp quạt.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            } else {
                Text(
                    "Lệch trung bình so với tiếng click, ${model.rhythm.size} lần gần nhất. Càng thấp càng đều — " +
                        "${model.rhythmPassed} lần đạt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                LineChart(model.rhythm, unit = " ms", reference = Reference(60f, "đạt ≤ 60 ms", shadeBelow = true), lowerIsBetter = true)
            }
        }

        Card {
            Text("Hợp âm", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chords.ALL.forEach { ch ->
                    val done = ch.name in model.chordsPassed
                    Text(
                        (if (done) "✓ " else "") + ch.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (done) Palette.OnAccent else Palette.Muted,
                        modifier = Modifier
                            .background(if (done) Palette.Accent else Palette.Ink, RoundedCornerShape(10.dp))
                            .border(1.dp, if (done) Palette.Accent else Palette.Hairline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Text("✓ = đã được micro chấm đúng 3 lần liền.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }

        Card {
            Text("Ghi âm", style = MaterialTheme.typography.titleMedium)
            Text(
                if (model.recordings == 0) "Chưa có bản ghi. Ghi lại một bài để nghe mình chơi thế nào."
                else "${model.recordings} bản ghi · ${model.recordingsRated} đã tự chấm",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
            OutlinedButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) { Text("Mở ghi âm & tự chấm") }
        }
    }
}
