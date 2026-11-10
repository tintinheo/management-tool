package com.myguitarplayer.music

/**
 * Strum patterns on an eighth-note grid of one 4/4 bar: "1 & 2 & 3 & 4 &".
 * D = down stroke, U = up stroke, '.' = hand still moves but misses the strings.
 */
enum class StrumPattern(val title: String, val strokes: String, val tip: String) {
    DOWN_4("Xuống 4", "D.D.D.D.", "Mỗi phách một cú quạt xuống. Tay vẫn đưa lên ở chữ \"&\" nhưng không chạm dây."),
    DOWN_UP("Xuống–lên", "DUDUDUDU", "Tay như con lắc: xuống ở số, lên ở \"&\". Cú lên nhẹ, chỉ chạm 3–4 dây mỏng."),
    BALLAD("Ballad", "D.DU.UDU", "Mẫu đệm phổ biến nhất: xuống, (trượt), xuống-lên, (trượt), lên-xuống-lên. Tay không bao giờ dừng."),
    ;

    init {
        require(strokes.length == 8)
    }
}
