# MyGuitarPlayer — Proposal & Plan (app Android tự học guitar đệm hát)

## 1. Bối cảnh

- **Người dùng:** chưa biết gì về guitar. Tập 20–30 phút/ngày, mục tiêu là tự đệm hát trọn một bài dễ sau khoảng 8 tuần.
- **Hiện có:** 2 prototype Streamlit là `../guitar_30_day_coach_v2` và `../hon_da_co_don_guitar_coach`. Cả hai đều có giáo trình 21/30 ngày, tuner, chord check và rhythm check, nhưng phải chạy trên PC và upload file ghi âm. Vì vậy phản hồi không tức thì, không tiện cầm đàn tập.
- **Hướng đi:** viết app native Kotlin/Compose cho Xiaomi 14T trong `MyGuitarPlayer/`, chạy offline. Mic được xử lý **realtime trên máy**: lên dây, nhận hợp âm, đo nhịp quạt. Giáo trình lấy ý tưởng từ 2 prototype, dàn lại cho 20–30 phút/ngày.
- **Bài hát:** app có sẵn vài bài mẫu không vướng bản quyền. Người dùng tự nhập bài mình thích bằng định dạng ChordPro (`[G]lời [D]hát`), app tự chuyển tông và gợi ý capo.

## 2. Nguyên tắc sư phạm (rút từ cách dạy guitar phổ biến cho người mới)

1. **Tay phải (nhịp) quan trọng ngang tay trái.** Đệm hát hỏng phần lớn vì dừng nhịp khi đổi hợp âm, ít khi vì bấm sai. Từ tuần 2, buổi nào cũng có metronome.
2. **Học hợp âm theo cặp dễ chuyển.** Em→Am→C→G→D→A→E, rồi tới Dm, F (bản rút gọn) và Bm7. Không bắt người mới học barre trước tuần 6.
3. **"One-minute changes":** đếm số lần chuyển qua lại giữa 2 hợp âm trong 60 giây. Đây là chỉ số tiến bộ rõ nhất, và app lưu lại thành biểu đồ.
4. **Giữ nhịp, bỏ bớt nốt:** khi chưa kịp đổi hợp âm thì quạt dây buông chứ không dừng tay.
5. **Buổi tập ngắn, cố định:** khởi động 3' → kỹ năng mới 8' → chuyển hợp âm 7' → nhịp/bài hát 8' → tự đánh giá 2'.
6. **Mở khóa theo năng lực:** tuần sau mở khi qua bài test của tuần trước, không chỉ dựa vào số ngày. Bài test do mic chấm và người học tự chấm.
7. **Đau đầu ngón tay là bình thường trong 2 tuần đầu.** App nhắc nghỉ và không tính phạt streak khi người dùng chọn "nghỉ tay".

## 3. Lộ trình 8 tuần (~40 buổi, 5 buổi/tuần)

| Tuần | Chủ đề | Bài test để mở tuần sau |
|---|---|---|
| 1 | Cầm đàn, tên dây, lên dây bằng tuner, Em & Am, quạt xuống 4 phách | Lên đủ 6 dây trong ±10 cents; Em/Am được mic nhận đúng |
| 2 | C, G; metronome 60 BPM; chuyển Em–Am, C–G | One-minute changes C↔G ≥ 20 |
| 3 | D; quạt xuống–lên (D DU); vòng G–D–Em–C | Quạt đều 70 BPM, lệch trung bình < 60 ms |
| 4 | Mẫu quạt ballad `D - D U - U D U`; bài mẫu đầu tiên (không hát) | Chạy hết vòng 4 hợp âm 8 lần không dừng nhịp |
| 5 | Ghép hát: đếm nhịp, hát nhẩm, hát từng câu; A, E | Đệm hát 1 bài mẫu ở tốc độ chậm (tự chấm + ghi âm) |
| 6 | Dm, F rút gọn, Bm7; capo và chuyển tông theo giọng | Mic nhận đúng Dm/F; tìm được tông hợp giọng |
| 7 | Bass–quạt (điệu Slow/Ballad), nhấn phách, intro/outro | Bass đúng dây gốc của 4 hợp âm |
| 8 | Bài của chính mình: nhập ChordPro, tập từng đoạn, tổng duyệt | Ghi âm trọn bài, tự chấm theo rubric 5 tiêu chí |

Nội dung từng buổi nằm trong JSON (`assets/curriculum.json`): mục tiêu, giải thích ngắn bằng tiếng Việt, các bước có hẹn giờ, bài tập gắn với công cụ (tuner, chord check, changes, rhythm, song) và tiêu chí đạt.

## 4. Tính năng v1

| # | Tính năng | Mô tả | Kỹ thuật |
|---|---|---|---|
| 1 | **Hôm nay** | Thẻ buổi tập hôm nay, các bước có đồng hồ đếm ngược, nút "Bắt đầu", streak | Room + DataStore |
| 2 | **Tuner** | Kim cents, tự nhận dây, hiện cả tên nốt | YIN pitch detection trên `AudioRecord` 44.1 kHz, cửa sổ ~4096 mẫu |
| 3 | **Chord Check** | Bấm hợp âm và quạt, app báo "Đúng / thiếu nốt X / có dây rè (tạp âm)" | FFT → chroma 12 bậc → so với mẫu hợp âm (cosine). Kết hợp ngưỡng năng lượng |
| 4 | **Chord Changes** | Chọn 2 hợp âm, đếm 60 giây, mic tự đếm số lần đổi đúng (hoặc tự bấm đếm), lưu biểu đồ | Chroma theo khung thời gian + phát hiện chuyển trạng thái |
| 5 | **Metronome / Rhythm** | Click chính xác, chọn BPM, mẫu quạt hiện mũi tên ↓↑ chạy theo phách; chế độ chấm độ lệch nhịp | Click tổng hợp sẵn và phát qua `AudioTrack` low-latency; spectral-flux onset detection, bù độ trễ đo một lần |
| 6 | **Thư viện hợp âm** | Sơ đồ hợp âm vẽ bằng Canvas, ngón tay đánh số, nghe thử tiếng | Tổng hợp tiếng gảy Karplus-Strong (không cần file mẫu) |
| 7 | **Bài hát** | Danh sách bài; xem lời + hợp âm; chuyển tông ±, gợi ý capo để dùng hợp âm mở; tự cuộn theo BPM; mẫu quạt của bài; chế độ "chỉ hợp âm" to chữ để đặt điện thoại lên giá | ChordPro parser + transposer (Kotlin thuần) |
| 8 | **Nhập bài** | Dán text ChordPro hoặc dạng "hợp âm trên dòng lời" (kiểu hopamchuan) → tự chuyển sang ChordPro | Parser 2 định dạng |
| 9 | **Ghi âm & tự chấm** | Ghi đoạn tập, nghe lại, chấm theo rubric 5 tiêu chí (nhịp, âm sạch, chuyển hợp âm, ghép giọng, liền mạch) | `MediaRecorder` AAC, lưu nội bộ |
| 10 | **Tiến độ** | Biểu đồ one-minute changes, độ lệch nhịp, số phút tập, hợp âm đã thuộc | Compose Canvas |

**Không làm ở v1:** AI/LLM, đồng bộ cloud, nhận hợp âm từ file nhạc/YouTube, tab solo/fingerstyle, xử lý giọng hát.

**Bài mẫu đi kèm** (chỉ hợp âm + lời thuộc phạm vi công cộng, hoặc vòng hợp âm không có lời): Happy Birthday, Twinkle Twinkle, Kumbaya, Amazing Grace, Auld Lang Syne, 2–3 vòng hợp âm "pop 4 chords" và vòng Slow/Ballad Việt (chỉ có hợp âm, không kèm lời). Bài Việt có bản quyền như Hòn Đá Cô Đơn thì người dùng tự dán vào.

## 5. Kiến trúc

Stack giống SnapBrain/AudioHub: Kotlin, Jetpack Compose, Material3, Room, DataStore, Navigation-Compose, minSdk 31, không dùng thư viện DSP ngoài. TarsosDSP mang giấy phép GPL nên không dùng; YIN và FFT đều tự viết, mỗi thứ dưới 200 dòng.

```
MyGuitarPlayer/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  app/src/main/java/com/myguitarplayer/
    audio/      MicStream (AudioRecord → Flow<FloatArray>), Fft, Yin, Chroma,
                ChordMatcher, OnsetDetector, Metronome (AudioTrack), PluckSynth
    music/      Note, Chord (định nghĩa + fingering), ChordPro parser, Transposer, CapoAdvisor
    data/       Room: Session, ChangeScore, RhythmScore, Song, Recording; CurriculumRepo (assets JSON)
    ui/         today/, tuner/, chordcheck/, changes/, rhythm/, chords/, songs/, progress/, theme/
  app/src/main/assets/  curriculum.json, chords.json, songs/*.cho
  app/src/test/         DSP + parser + UI (Robolectric/Roborazzi) tests
```

- Mic là `callbackFlow` dùng chung. Mỗi màn hình chỉ thu khi đang hiển thị, xin quyền `RECORD_AUDIO` lúc cần.
- DSP chạy trên `Dispatchers.Default`, UI nhận `StateFlow` khoảng 20 fps.
- `archivesName = "MyGuitarPlayer-${versionName}"`, theo quy ước đặt tên APK.
- **Giao diện:** tối giản, nền tối, một màu nhấn vàng (giống SnapBrain), tiêu đề serif, chữ to để nhìn từ xa khi đang ôm đàn. Màn hình luôn sáng (`keepScreenOn`) khi ở Tuner/Song/Metronome.

## 6. Các giai đoạn

| Pha | Nội dung | Kết quả |
|---|---|---|
| 1 | Khung dự án, theme, điều hướng 5 tab (Hôm nay · Công cụ · Hợp âm · Bài hát · Tiến độ), Room, quyền mic | Cài được APK |
| 2 | Lõi âm thanh: MicStream, FFT, YIN → **Tuner**; Metronome + mẫu quạt | Lên dây và tập nhịp được |
| 3 | Chroma + ChordMatcher → **Chord Check**, **Chord Changes**; thư viện hợp âm + PluckSynth | Tập hợp âm có phản hồi |
| 4 | ChordPro parser/transposer/capo → **Bài hát**, nhập bài, tự cuộn; bài mẫu | Đệm hát được trong app |
| 5 | Giáo trình 8 tuần (curriculum.json ~40 buổi), màn Hôm nay, bài test mở khóa, streak | Có lộ trình hằng ngày |
| 6 | OnsetDetector → chấm nhịp; ghi âm + rubric; màn Tiến độ | Đo được tiến bộ |
| 7 | Hoàn thiện: hiệu chỉnh ngưỡng trên Xiaomi 14T, độ trễ, release signing, APK 1.0.0 | MyGuitarPlayer-1.0.0-release.apk |

## 7. Rủi ro & cách xử lý

- **Nhận hợp âm qua mic điện thoại không hoàn hảo**, nhất là khi có tiếng ồn, hợp âm giống nhau (Am/C) hoặc dây rè. Cách xử lý: chỉ so với hợp âm mục tiêu (bài toán "đúng/sai" dễ hơn "đây là hợp âm gì"), có thanh độ tin cậy, và luôn có nút tự chấm để thay.
- **Độ trễ âm thanh** làm sai điểm nhịp. Cách xử lý: bước hiệu chỉnh một lần (gõ theo click) để đo độ lệch, rồi lưu lại.
- **Người mới bỏ cuộc** vì đau tay hoặc chán. Cách xử lý: buổi tập ngắn, bài thật từ tuần 4, có lựa chọn "nghỉ tay" không mất streak.
- **Bản quyền lời bài hát:** chỉ đóng gói bài thuộc phạm vi công cộng. Bài khác do người dùng tự nhập và chỉ lưu trên máy.

## 8. Kiểm thử

- **Unit test DSP với tín hiệu tổng hợp:** sóng 82.41 Hz phải ra E2 ±2 cents. Hợp âm tổng hợp (PluckSynth) G/C/Em/Am/D phải được ChordMatcher nhận đúng, kể cả khi thêm nhiễu trắng. Chuỗi click 80 BPM phải được onset detector bắt đủ, lệch dưới 15 ms.
- **Unit test music:** chuyển tông (G +3 → Bb), gợi ý capo (tông Bb → capo 3, thế G), parser cho cả 2 định dạng nhập.
- **UI:** Robolectric + Roborazzi chụp màn hình từng tab rồi xem ảnh (emulator không chạy được trên máy này).
- **Build:** `gradle testDebugUnitTest lintDebug assembleDebug`, giống quy trình của AudioHub.
- **Thiết bị thật:** bạn sideload APK lên Xiaomi 14T để kiểm tra tuner với đàn thật, Chord Check với 6 hợp âm cơ bản và độ trễ metronome. Ngưỡng sẽ được chỉnh lại theo phản hồi ở pha 7.
