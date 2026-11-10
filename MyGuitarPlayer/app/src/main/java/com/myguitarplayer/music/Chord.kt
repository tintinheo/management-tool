package com.myguitarplayer.music

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ChordGroup(val title: String) {
    BASIC("Cơ bản · tuần 1–3"),
    MORE("Mở rộng · tuần 5–6"),
    SEVENTH("Hợp âm 7 & chặn"),
}

/**
 * One guitar voicing. [frets] and [fingers] run from string 6 (low E) to string 1 (high E);
 * fret −1 = muted, 0 = open; finger 0 = none, 1 = index … 4 = pinky.
 */
class Chord(
    val name: String,
    val frets: IntArray,
    val fingers: IntArray,
    val group: ChordGroup,
    val tip: String,
) {
    init {
        require(frets.size == 6 && fingers.size == 6)
    }

    /** MIDI notes that actually sound, low to high. */
    val midiNotes: List<Int> = GuitarString.entries.mapIndexedNotNull { i, s -> frets[i].takeIf { it >= 0 }?.let { s.midi + it } }

    val pitchClasses: Set<Int> = midiNotes.map { Math.floorMod(it, 12) }.toSet()

    /** Lowest string to strum from (the first one not muted), as the 6…1 string number. */
    val firstString: Int get() = 6 - frets.indexOfFirst { it >= 0 }

    /** Expected chroma: each string's harmonic series folded onto pitch classes (linear magnitude, like ChromaAnalyzer). */
    val template: DoubleArray by lazy {
        val t = DoubleArray(12)
        for (m in midiNotes) {
            for (h in HARMONICS) {
                val pc = Math.floorMod((m + 12 * log2(h.toDouble())).roundToInt(), 12)
                t[pc] += 0.8.pow(h - 1)
            }
        }
        val top = t.max()
        DoubleArray(12) { t[it] / top }
    }

    override fun toString() = name

    companion object {
        /** Harmonics the chroma analyzer keeps (7 and 11 are too far off any tempered pitch). */
        val HARMONICS = intArrayOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 12)
    }
}

object Chords {
    private fun c(name: String, frets: String, fingers: String, group: ChordGroup, tip: String) = Chord(
        name,
        frets.split(' ').map { if (it == "x") -1 else it.toInt() }.toIntArray(),
        fingers.split(' ').map { it.toInt() }.toIntArray(),
        group,
        tip,
    )

    val ALL: List<Chord> = listOf(
        c("Em", "0 2 2 0 0 0", "0 2 3 0 0 0", ChordGroup.BASIC, "Hợp âm dễ nhất: 2 ngón trên ngăn 2, quạt cả 6 dây."),
        c("Am", "x 0 2 2 1 0", "0 0 2 3 1 0", ChordGroup.BASIC, "Giống hình Em dời xuống 1 dây, thêm ngón trỏ ngăn 1 dây 2. Không gảy dây 6."),
        c("C", "x 3 2 0 1 0", "0 3 2 0 1 0", ChordGroup.BASIC, "Ngón áp út dây 5 là nốt bass C. Cong ngón để dây 3 và dây 1 buông kêu rõ."),
        c("G", "3 2 0 0 0 3", "2 1 0 0 0 3", ChordGroup.BASIC, "Quạt cả 6 dây. Đầu ngón đứng thẳng để không chạm dây 4–2 buông."),
        c("D", "x x 0 2 3 2", "0 0 0 1 3 2", ChordGroup.BASIC, "Hình tam giác trên 3 dây mỏng. Chỉ gảy từ dây 4."),
        c("A", "x 0 2 2 2 0", "0 0 1 2 3 0", ChordGroup.MORE, "3 ngón xếp hàng ngang ngăn 2. Dây 1 buông phải kêu."),
        c("E", "0 2 2 1 0 0", "0 2 3 1 0 0", ChordGroup.MORE, "Là Em thêm ngón trỏ ngăn 1 dây 3. Quạt cả 6 dây."),
        c("Dm", "x x 0 2 3 1", "0 0 0 2 3 1", ChordGroup.MORE, "Giống D nhưng dây 1 hạ xuống ngăn 1. Gảy từ dây 4."),
        c("F", "x x 3 2 1 1", "0 0 3 2 1 1", ChordGroup.MORE, "F rút gọn: ngón trỏ đè 2 dây mỏng ở ngăn 1. Gảy 4 dây mỏng."),
        c("Bm7", "x 2 4 2 3 2", "0 1 3 1 2 1", ChordGroup.MORE, "Thay tạm cho Bm khi chưa bấm được chặn. Ngón trỏ đè ngăn 2."),
        c("Bm", "x 2 4 4 3 2", "0 1 3 4 2 1", ChordGroup.SEVENTH, "Hợp âm chặn đầu tiên: ngón trỏ đè ngăn 2 từ dây 5. Tập sau tuần 6."),
        c("E7", "0 2 0 1 0 0", "0 2 0 1 0 0", ChordGroup.SEVENTH, "Là E bỏ ngón áp út. Hay đứng trước Am."),
        c("A7", "x 0 2 0 2 0", "0 0 2 0 3 0", ChordGroup.SEVENTH, "Là A bỏ ngón giữa ở dây 3. Hay đứng trước D."),
        c("D7", "x x 0 2 1 2", "0 0 0 2 1 3", ChordGroup.SEVENTH, "Hình tam giác ngược của D. Hay đứng trước G."),
        c("G7", "3 2 0 0 0 1", "3 2 0 0 0 1", ChordGroup.SEVENTH, "Như G nhưng dây 1 ở ngăn 1. Hay đứng trước C."),
        c("B7", "x 2 1 2 0 2", "0 2 1 3 0 4", ChordGroup.SEVENTH, "4 ngón, dây 2 buông. Hay đứng trước Em."),
    )

    private val byName = ALL.associateBy { it.name }

    operator fun get(name: String): Chord = byName.getValue(name)
    fun find(name: String): Chord? = byName[name]
}
