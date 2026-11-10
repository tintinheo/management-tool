package com.myguitarplayer.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.runtime.Composable
import com.myguitarplayer.audio.ChordMatcher
import com.myguitarplayer.audio.ChordVerdict
import com.myguitarplayer.audio.ChromaAnalyzer
import com.myguitarplayer.audio.PluckSynth
import com.myguitarplayer.audio.StrumJudge
import com.myguitarplayer.data.ChangeScore
import com.myguitarplayer.music.Chord
import com.myguitarplayer.music.ChordGroup
import com.myguitarplayer.music.Chords
import com.myguitarplayer.ui.changes.ChangesContent
import com.myguitarplayer.ui.changes.ChangesPhase
import com.myguitarplayer.ui.chordcheck.ChordCheckContent
import com.myguitarplayer.ui.chords.ChordDetailContent
import com.myguitarplayer.ui.chords.ChordListScreen
import com.myguitarplayer.music.GuitarString
import com.myguitarplayer.music.Note
import com.myguitarplayer.music.StrumPattern
import com.myguitarplayer.music.TunerReading
import com.myguitarplayer.ui.metronome.MetronomeContent
import com.myguitarplayer.data.SavedSong
import com.myguitarplayer.data.SongRepo
import com.myguitarplayer.music.ChordPro
import com.myguitarplayer.ui.songs.SongEditorContent
import com.myguitarplayer.ui.songs.SongListContent
import com.myguitarplayer.ui.songs.SongViewerContent
import com.myguitarplayer.ui.songs.ViewerState
import androidx.compose.foundation.rememberScrollState
import java.io.File
import com.myguitarplayer.ui.theme.GuitarTheme
import com.myguitarplayer.ui.tuner.TunerContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

const val PHONE_QUALIFIERS = "w393dp-h873dp-xxhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { GuitarTheme { content() } }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val cur = com.myguitarplayer.data.loadTestCurriculum()
    private val today = java.time.LocalDate.of(2026, 11, 10)

    /** Mid-week-2 learner: week 1 passed, two sessions done, 4-day streak with a rest day. */
    private val record = com.myguitarplayer.data.emptyRecord().copy(
        doneSessions = cur.week(1).sessions.map { it.id }.toSet() + setOf("w2s1", "w2s2"),
        practiceDays = listOf(0, 1, 3, 4).map { today.minusDays(it.toLong()) }.toSet(),
        restDays = setOf(today.minusDays(2)),
        passedWeeks = setOf(1),
        tuned = true,
        chordsPassed = setOf("Em", "Am", "C"),
        bestChanges = mapOf(setOf("C", "G") to 14),
        bestQuiz = 8,
    )
    private val course = com.myguitarplayer.data.Course(cur, record)

    @Test
    fun today() = shot("today") { com.myguitarplayer.ui.today.TodayContent(course, today, {}, {}, {}) }

    @Test
    fun week() = shot("week_2") { com.myguitarplayer.ui.today.WeekContent(course, cur.week(2), {}, {}, { _, _ -> }, {}, {}) }

    private fun step(id: String, index: Int, name: String) = shot(name) {
        val s = cur.session(id)
        com.myguitarplayer.ui.today.SessionContent(cur.weekOf(id), s, index, s.steps[index].minutes * 60 - 37, true, {}, {}, {}, {}, null, {})
    }

    @Test
    fun stepPress() = step("w1s2", 1, "step_press")

    @Test
    fun stepChord() = step("w1s3", 1, "step_chord_em")

    @Test
    fun stepBass() = step("w3s4", 2, "step_bass")

    @Test
    fun stepStrum() = step("w4s1", 2, "step_strum")

    @Test
    fun stepCapo() = step("w6s4", 1, "step_capo")

    @Test
    fun notesExplore() = shot("notes_explore") {
        com.myguitarplayer.ui.notes.NotesContent(false, true, 2 to 1, null, true, null, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun notesQuiz() = shot("notes_quiz") {
        val q = com.myguitarplayer.ui.notes.QuizUi(
            3, com.myguitarplayer.ui.notes.NoteQuestion(5, 3), 2, com.myguitarplayer.ui.notes.Verdict.WRONG_STRING, 2 to 1, hint = true, finished = false,
        )
        com.myguitarplayer.ui.notes.NotesContent(true, true, null, q, true, "C4", {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test
    fun tunerFlat() = shot("tuner_flat") {
        TunerContent(
            reading = TunerReading.of(Note.freqOf(GuitarString.A2.midi - 0.18)),
            tuned = setOf(GuitarString.E2),
            locked = null,
            micGranted = true,
            onAskMic = {}, onLock = {}, onBack = {},
        )
    }

    @Test
    fun tunerInTune() = shot("tuner_in_tune") {
        TunerContent(
            reading = TunerReading.of(GuitarString.G3.freq * 1.001),
            tuned = GuitarString.entries.toSet() - GuitarString.E4,
            locked = GuitarString.G3,
            micGranted = true,
            onAskMic = {}, onLock = {}, onBack = {},
        )
    }

    @Test
    fun tunerNoMic() = shot("tuner_no_mic") {
        TunerContent(null, emptySet(), null, micGranted = false, onAskMic = {}, onLock = {}, onBack = {})
    }

    @Test
    fun chordList() = shot("chords_list") { ChordListScreen(onOpen = {}) }

    @Test
    fun chordDetailF() = shot("chord_detail_f") { ChordDetailContent(Chords["F"], {}, {}, {}, {}) }

    private fun verdictFor(target: String, played: Chord): ChordVerdict {
        val an = ChromaAnalyzer(PluckSynth.SAMPLE_RATE)
        val s = (0.15 * PluckSynth.SAMPLE_RATE).toInt()
        val chroma = an.chroma(PluckSynth.strum(played).copyOfRange(s, s + an.size))!!
        return ChordMatcher.judge(chroma, Chords[target])
    }

    @Test
    fun chordCheckCorrect() = shot("check_correct") {
        ChordCheckContent(Chords["Em"], StrumJudge.State(verdictFor("Em", Chords["Em"]), 3, 2, true), true, {}, {}, {})
    }

    @Test
    fun chordCheckMissing() = shot("check_missing") {
        val sloppy = Chord("Am?", intArrayOf(-1, 0, 2, 2, 0, 0), IntArray(6), ChordGroup.BASIC, "")
        ChordCheckContent(Chords["Am"], StrumJudge.State(verdictFor("Am", sloppy), 2, 0, true), true, {}, {}, {})
    }

    private val history = listOf(
        ChangeScore(chordA = "C", chordB = "G", count = 17, micCount = 15, at = 1_762_400_000_000),
        ChangeScore(chordA = "C", chordB = "G", count = 12, micCount = 12, at = 1_762_300_000_000),
    )

    @Composable
    private fun changes(phase: ChangesPhase, count: Int = 0, heard: Chord? = null) = ChangesContent(
        Chords["C"], Chords["G"], phase, secondsLeft = 42, count = count, heard = heard, history = history,
        bestBefore = 17, micGranted = true, {}, {}, {}, {}, {}, {}, {}, {}, {},
    )

    @Test
    fun changesSetup() = shot("changes_setup") { changes(ChangesPhase.SETUP) }

    @Test
    fun changesRunning() = shot("changes_running") { changes(ChangesPhase.RUNNING, count = 14, heard = Chords["G"]) }

    @Test
    fun changesDone() = shot("changes_done") { changes(ChangesPhase.DONE, count = 21) }

    @Test
    fun metronome() = shot("metronome") {
        MetronomeContent(
            bpm = 72, pattern = StrumPattern.BALLAD, running = true, step = 3,
            onBpm = {}, onPattern = {}, onToggle = {}, onBack = {},
        )
    }

    private fun sample(name: String): String =
        listOf("src/main/assets/songs", "app/src/main/assets/songs").map { File(it, name) }.first { it.exists() }.readText()

    @Test
    fun songsList() = shot("songs_list") {
        val samples = listOf("04_twinkle.cho", "07_amazing_grace.cho", "03_bac_kim_thang.cho", "01_vong_4_hop_am.cho")
            .mapIndexed { i, f -> SongRepo.fromChordPro(sample(f), sample = f).copy(id = i + 10L) }
        val mine = SavedSong(id = 1, title = "Bài của tôi", artist = "Tự nhập", body = "[Bb]La [Eb]la [F]la [Gm]la", capo = 3)
        SongListContent(listOf(mine) + samples, onOpen = {}, onAdd = {})
    }

    @Composable
    private fun viewer(file: String, state: ViewerState) = SongViewerContent(
        song = ChordPro.parse(sample(file)), state = state, isSample = true, scroll = rememberScrollState(),
        onChange = {}, onChord = {}, onEdit = {}, onDelete = {}, onBack = {},
    )

    @Test
    fun songViewer() = shot("song_viewer") { viewer("07_amazing_grace.cho", ViewerState(bpm = 76)) }

    @Test
    fun songViewerCapo() = shot("song_viewer_capo") {
        // Moved up to Bb for the voice: the app suggests capo 3 with G shapes.
        viewer("06_kumbaya.cho", ViewerState(transpose = 3, bpm = 72, playing = true, click = true))
    }

    @Test
    fun songChordsOnly() = shot("song_chords_only") { viewer("08_auld_lang_syne.cho", ViewerState(capo = 0, chordsOnly = true)) }

    @Test
    fun songEditor() = shot("song_editor") {
        val text = "Am            F\nHôm nay trời nắng\nC         G\nEm đi đâu đó"
        SongEditorContent(
            isNew = true, text = text, title = "Bài thử", artist = "",
            preview = ChordPro.parse(ChordPro.toChordPro(text)),
            onText = {}, onTitle = {}, onArtist = {}, onSave = {}, onCancel = {},
        )
    }

    private val hits = listOf(
        com.myguitarplayer.audio.Hit(8, -12.0, true), com.myguitarplayer.audio.Hit(9, 34.0, true),
        com.myguitarplayer.audio.Hit(10, 8.0, true), com.myguitarplayer.audio.Hit(11, 71.0, true),
        com.myguitarplayer.audio.Hit(12, -22.0, true), com.myguitarplayer.audio.Hit(13, 18.0, true),
    )

    private fun rhythmUi(phase: com.myguitarplayer.ui.rhythm.RhythmPhase, summary: com.myguitarplayer.audio.RhythmSummary? = null) =
        com.myguitarplayer.ui.rhythm.RhythmUi(
            phase, 70, StrumPattern.DOWN_UP, 8, false, 118, step = 8 + 13, hits = hits, summary = summary,
            history = listOf(
                com.myguitarplayer.data.RhythmScore(bpm = 70, pattern = "DUDUDUDU", bars = 8, meanAbsMs = 52, meanSignedMs = -8, hitPercent = 88, extras = 1),
                com.myguitarplayer.data.RhythmScore(bpm = 60, pattern = "DUDUDUDU", bars = 8, meanAbsMs = 31, meanSignedMs = 4, hitPercent = 94, extras = 0),
            ),
            micGranted = true,
        )

    @Composable
    private fun rhythm(ui: com.myguitarplayer.ui.rhythm.RhythmUi) =
        com.myguitarplayer.ui.rhythm.RhythmContent(ui, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun rhythmSetup() = shot("rhythm_setup") { rhythm(rhythmUi(com.myguitarplayer.ui.rhythm.RhythmPhase.SETUP)) }

    @Test
    fun rhythmRunning() = shot("rhythm_running") { rhythm(rhythmUi(com.myguitarplayer.ui.rhythm.RhythmPhase.RUNNING)) }

    @Test
    fun rhythmDone() = shot("rhythm_done") {
        rhythm(rhythmUi(com.myguitarplayer.ui.rhythm.RhythmPhase.DONE, com.myguitarplayer.audio.RhythmSummary(41.0, -9.0, 0.91, 2, 64, 58)))
    }

    @Test
    fun recordings() = shot("recordings") {
        val recs = listOf(
            com.myguitarplayer.data.Recording(id = 2, file = "b", title = "Kumbaya — lần 2", durationMs = 154_000, rhythm = 3, clean = 2, changes = 2, voice = 3, flow = 2),
            com.myguitarplayer.data.Recording(id = 1, file = "a", title = "Bản ghi 08/11 20:15", durationMs = 98_000, rhythm = 2, clean = 1),
        )
        com.myguitarplayer.ui.record.RecordContent(false, 0, recs, null, 1, true, {}, {}, {}, {}, { _, _, _ -> }, {}, {})
    }

    @Test
    fun progress() = shot("progress") {
        val zone = java.time.ZoneId.systemDefault()
        fun at(daysAgo: Long) = today.minusDays(daysAgo).atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        val ids = cur.sessions.map { it.id }
        val logs = listOf(13, 12, 11, 9, 8, 7, 6, 4, 3, 1, 0).mapIndexed { i, d -> com.myguitarplayer.data.SessionLog(sessionId = ids[i], rating = 2, at = at(d.toLong())) }
        val changes = listOf(8, 6, 4, 3, 1, 0).zip(listOf(9, 12, 13, 16, 17, 21)).map { (d, n) ->
            ChangeScore(chordA = "C", chordB = "G", count = n, micCount = n, at = at(d.toLong()))
        } + ChangeScore(chordA = "Em", chordB = "Am", count = 24, micCount = 22, at = at(10))
        val rhythm = listOf(92, 78, 70, 64, 55, 58, 47, 41).mapIndexed { i, ms ->
            com.myguitarplayer.data.RhythmScore(bpm = 70, pattern = "DUDUDUDU", bars = 8, meanAbsMs = ms, meanSignedMs = 0, hitPercent = 90, extras = 0, at = at(7L - i))
        }
        val c = com.myguitarplayer.data.Course(cur, com.myguitarplayer.data.ProgressRepo.record(logs, emptyList(), changes, zone, rhythm).copy(chordsPassed = setOf("Em", "Am", "C", "G", "D")))
        com.myguitarplayer.ui.progress.ProgressContent(com.myguitarplayer.ui.progress.buildProgress(c, logs, changes, rhythm, emptyList(), today, zone), {})
    }

    @Test
    fun progressCharts() = shot("progress_charts") {
        val changes = listOf(9, 12, 13, 16, 17, 21).mapIndexed { i, n ->
            com.myguitarplayer.ui.progress.Point("0${i + 3}/11", n.toFloat(), "0${i + 3}/11: $n lần/phút")
        }
        val rhythm = listOf(92, 78, 70, 64, 55, 58, 47, 41).mapIndexed { i, ms ->
            com.myguitarplayer.ui.progress.Point("0${i + 1}/11", ms.toFloat(), "Lần ${i + 1}: $ms ms")
        }
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
            com.myguitarplayer.ui.common.Card {
                androidx.compose.material3.Text("Chuyển C ↔ G")
                com.myguitarplayer.ui.progress.LineChart(changes, reference = com.myguitarplayer.ui.progress.Reference(20f, "mục tiêu 20"))
            }
            com.myguitarplayer.ui.common.Card {
                androidx.compose.material3.Text("Độ đều nhịp quạt")
                com.myguitarplayer.ui.progress.LineChart(
                    rhythm, unit = " ms",
                    reference = com.myguitarplayer.ui.progress.Reference(60f, "đạt ≤ 60 ms", shadeBelow = true), lowerIsBetter = true,
                )
            }
        }
    }

    @Test
    fun todayMicHint() = shot("today_mic_hint") { com.myguitarplayer.ui.today.TodayContent(course, today, {}, {}, {}, onMicSetup = {}) }

    @Test
    fun micSetup() = shot("mic_setup") {
        com.myguitarplayer.ui.setup.MicSetupContent(
            com.myguitarplayer.ui.setup.MicSetupUi(
                micGranted = true, levelDb = -31f, peakDb = -24f, noiseRms = 0.0011, measuring = null,
                strums = 7, flash = true, chordLevel = 1, onsetLevel = 1,
                source = com.myguitarplayer.audio.AudioConfig.Source.STANDARD, rawSupported = false, latencyMs = 118,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
