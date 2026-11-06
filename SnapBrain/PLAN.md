# SnapBrain cho Android: phân tích proposal và kế hoạch triển khai

Thiết bị đích: Xiaomi 14T (HyperOS). Stack giống AudioHub và English Coach: Kotlin, Jetpack Compose, Room, DataStore, WorkManager và Glance. Cài trực tiếp bằng APK, không lên Play Store.

---

## 1. Phân tích proposal

### 1.1 Điểm mạnh nên giữ
- **Đánh tráo thay vì cấm.** Đây là điểm khác biệt thật sự so với Opal/AppBlock. Luôn có lối thoát ("Không, tôi bận việc thật"), nên người dùng ít có lý do gỡ app.
- **Phiên học ngắn (dưới 30 giây) và có quiz chốt.** Làm quiz giúp nhớ lâu hơn chỉ đọc. Câu sai được đưa vào kho ôn tập (SRS), nên lần sau gặp lại đúng chỗ yếu.
- **Vuốt Tinder để trả lời Đúng/Sai** cần rất ít thao tác và rất hợp với quiz dạng nhận định đúng/sai.

### 1.2 Vấn đề kỹ thuật cần sửa

| # | Proposal nói | Thực tế | Đề xuất |
|---|---|---|---|
| T1 | Dùng cả Accessibility Service **và** UsageStatsManager | Accessibility nhận sự kiện `TYPE_WINDOW_STATE_CHANGED` kèm tên gói (package name) gần như tức thì. UsageStats phải hỏi liên tục (polling) nên chậm và tốn pin | Chỉ dùng **Accessibility**. Không cần UsageStats |
| T2 | Hiện màn hình đè "trước khi app kia kịp tải nội dung" | Không chặn được việc app kia khởi động. Chỉ che được nó sau khoảng 100–300ms | Mở `InterceptActivity` toàn màn hình ngay khi nhận sự kiện, và tắt âm/hiệu ứng nền của app bị che. Coi độ trễ đó là chấp nhận được |
| T3 | Dùng WorkManager + Foreground Service để chống HyperOS diệt tiến trình | Accessibility Service do hệ thống bind nên tự sống lâu. WorkManager không giúp gì cho việc phát hiện thời gian thực. Foreground Service thêm một thông báo thường trực mà không cần thiết | **Không dùng Foreground Service.** Hướng dẫn bật Autostart và đặt pin "Không giới hạn" trên Xiaomi. Mỗi lần mở app, kiểm tra service còn bật không và nhắc bật lại nếu bị tắt. WorkManager chỉ dùng cho widget và thông báo cứu chuỗi |
| T4 | — | Nếu không xử lý, người dùng vừa học xong quay lại TikTok sẽ bị chặn lại ngay (vòng lặp) | Thêm **"giấy thông hành"**: sau khi học xong hoặc bấm bỏ qua, gói đó được tự do cho tới khi rời app cộng 60 giây ân hạn, hoặc cho tới khi hết vé |
| T5 | Danh sách cố định TikTok/FB/IG | Còn TikTok Lite, Facebook Lite, YouTube (Shorts), Threads… | Màn chọn app từ danh sách app đã cài, kèm danh sách gợi ý sẵn |
| T6 | — | Accessibility có thể đọc mọi nội dung trên màn hình (rủi ro quyền riêng tư) | Đặt `canRetrieveWindowContent=false`, chỉ nghe sự kiện đổi cửa sổ, chỉ đọc tên gói. Ghi rõ điều này trên màn hình xin quyền |
| T7 | Rung "cao cấp như TikTok" | Xiaomi 14T có motor tuyến tính hỗ trợ `VibrationEffect.Composition` | Dùng các hiệu ứng `PRIMITIVE_CLICK` / `THUD` / `TICK` khi `areAllPrimitivesSupported`, nếu không thì lùi về `createPredefined` |
| T8 | Mẹo Excel dạng GIF 15 giây, có giọng đọc | Tự làm 200 GIF và file thu âm tốn công. GIF cũng làm APK nặng | **V1:** "GIF" là animation vẽ bằng Compose (các bước thao tác, phím tắt hiện lần lượt) cộng TTS có sẵn trên máy. Ảnh/GIF thật để sau |

### 1.3 Mâu thuẫn trong thiết kế sản phẩm (cần chốt trước khi code)
1. **Kinh tế điểm không thống nhất.** Mục E ghi 5 điểm = 10 phút (tức 1 điểm = 2 phút). Kịch bản ở mục 5 lại ghi trả lời đúng 1 câu = vé 5 phút. → Đề xuất: **1 điểm = 2 phút**, mỗi lần mua tối thiểu 5 điểm (10 phút). Chỉ lần đầu trả lời đúng một thẻ trong ngày mới được điểm, để không cày điểm bằng thẻ dễ.
2. **Chuỗi (streak) phạt đúng hành vi tốt.** Định nghĩa hiện tại là "≥1 phiên bẻ hướng thành công", nên ngày nào bạn **không mở TikTok** thì đứt chuỗi. → Đổi thành: một ngày được tính nếu có **≥1 phiên học bất kỳ**, dù đến từ bộ chặn, tự mở app hay widget.
3. **Widget A/B/C khác kiểu quiz Đúng/Sai.** Thẻ chỉ có câu nhận định đúng/sai, còn widget lại cần câu trắc nghiệm 3 lựa chọn. → Mỗi thẻ có cả `tf` (đúng/sai) và `mcq` (3 lựa chọn). Hoặc làm widget V1 bằng hai nút Đúng/Sai cho đơn giản. Đề xuất: schema có cả hai, widget dùng `mcq`.
4. **"Vé lướt" mâu thuẫn với tinh thần "không trừng phạt".** Nếu nút "bận việc thật" luôn dùng được thì vé gần như vô nghĩa. → Có 2 chế độ: **Mềm** (mặc định, được bỏ qua 3 lần/ngày, hết lượt thì phải học hoặc dùng vé) và **Nghiêm** (không có nút bỏ qua, chỉ học hoặc dùng vé).
5. **45 giây hay 30 giây.** Lời mời ghi 45 giây, phiên học ghi 30 giây. → Thống nhất "30 giây".
6. **Lộ trình làm widget trước.** Với sản phẩm thương mại, đó là cách đo tương tác tự nguyện. Nhưng với app cá nhân, giá trị cốt lõi là bộ chặn (chính proposal cũng lập luận app học thuần không ai tự mở). → **Làm bộ chặn, feed và quiz trước, widget sau.**
7. **Giai đoạn 3 (creator, UGC)** cần backend, duyệt nội dung và tài khoản người dùng, nên **nằm ngoài phạm vi app V1**. V1 chạy hoàn toàn offline. Có thể thêm "Tạo thêm thẻ bằng AI (Groq)" như English Coach ở V1.x.

### 1.4 Rủi ro
- **Play Store:** dùng Accessibility cho mục đích không phải trợ năng sẽ bị Google soi rất kỹ. Không ảnh hưởng vì cài APK trực tiếp, nhưng nếu muốn phát hành sau này thì cần khai báo và xin duyệt riêng.
- **HyperOS tắt Accessibility** sau khi cập nhật hệ thống hoặc khi dọn RAM mạnh. Đã có cơ chế kiểm tra và nhắc lại ở T3. Thêm một thông báo nhắc nếu service tắt hơn 1 giờ.
- **Nội dung là nút thắt.** App hay hay dở phụ thuộc vào 200 thẻ chất lượng. Kế hoạch là sinh thẻ bằng AI rồi tự duyệt (xem Giai đoạn 1).

---

## 2. Kiến trúc

```
com.snapbrain
├── data/
│   ├── content/        CardPack (JSON trong assets), CardRepository
│   ├── db/             Room: CardProgress, PointLedger, InterceptLog, DailyStat
│   └── prefs/          DataStore: danh sách app bị chặn, chế độ Mềm/Nghiêm, số lần bỏ qua, vé đang chạy
├── domain/
│   ├── InterceptPolicy   (hàm thuần: event + state → Intercept | Allow | TicketExpired)
│   ├── Srs               (Leitner 5 hộp: 0d, 1d, 3d, 7d, 21d)
│   ├── Wallet            (cộng/trừ điểm, mua vé, giới hạn cày điểm)
│   └── Streak            (tính từ DailyStat, xử lý cứu chuỗi)
├── intercept/
│   ├── GuardAccessibilityService
│   └── InterceptActivity (Hook UI → Session → Reward / hoặc bỏ qua)
├── ui/
│   ├── feed/           VerticalPager + CardScreen + SwipeQuizCard
│   ├── home/           streak, điểm, vé, thống kê "đã bẻ hướng X lần"
│   ├── onboarding/     xin quyền + hướng dẫn riêng cho Xiaomi
│   └── settings/       chọn app, chế độ, giờ nhắc
├── haptics/HapticsKit
└── widget/             Glance QuizWidget (2x2, 4x2) + RefreshWorker
```

**Schema của một thẻ (assets/cards/*.json):**
```json
{
  "id": "excel-flashfill-01",
  "topic": "office",            // office | english | ai-prompt | psychology | finance | science-history
  "hook": "Đừng ngồi gõ tay tách họ tên nữa!",
  "body": ["Gõ mẫu ở dòng đầu", "Nhấn Ctrl + E", "Excel tự điền cả cột"],
  "visual": { "type": "keys", "data": ["Ctrl", "E"] },   // keys | steps | emoji | quote
  "tf":  { "statement": "Ctrl + E kích hoạt Flash Fill?", "answer": true, "explain": "Ctrl+E = Flash Fill, tự điền theo mẫu." },
  "mcq": { "q": "Phím tắt Flash Fill?", "options": ["Ctrl+E", "Ctrl+F", "Ctrl+D"], "correct": 0 }
}
```

**Luồng chặn:**
```
Sự kiện đổi cửa sổ (gói X)
  → InterceptPolicy:
      X không nằm trong danh sách chặn             → Allow
      X có giấy thông hành hoặc đang có vé hợp lệ  → Allow
      ngược lại                                     → Intercept
  → InterceptActivity (Hook: "Ok, nạp ngay" / "Bận việc thật (còn 2/3)")
      học xong 1 thẻ + quiz → +điểm, cấp giấy thông hành cho X, quay lại X
      bỏ qua                 → trừ 1 lượt bỏ qua, cấp giấy thông hành
Vé hết hạn khi đang ở trong X → InterceptActivity ở chế độ "Hết vé"
```

---

## 3. Lộ trình triển khai

Mỗi giai đoạn kết thúc bằng: unit test, Roborazzi screenshot, `lintDebug`, `assembleDebug`, rồi cài APK lên máy để thử.

### Giai đoạn 0: Khung dự án (≈0,5 ngày)
- Tạo `SnapBrain/android/` (hoặc đặt ngay trong `SnapBrain/`), dùng cùng file version catalog với AudioHub (AGP 9.5, Kotlin 2.4, Compose 1.12, minSdk 29, targetSdk 36).
- Theme, điều hướng, Room schema v1, DataStore, `CardRepository` đọc JSON.
- Test: parse JSON, migration Room.

### Giai đoạn 1: Gói nội dung (≈1 ngày, song song được)
- Viết hướng dẫn phong cách cho thẻ: hook dưới 10 chữ, body 3 dòng, `explain` dưới 15 chữ.
- Sinh **60 thẻ trước** (10 thẻ cho mỗi chủ đề × 6 chủ đề), duyệt tay, rồi mở rộng lên 200.
- Test kiểm định: mỗi thẻ đủ trường, `explain` ≤ 15 chữ, `mcq.correct` hợp lệ, không trùng id.

### Giai đoạn 2: Anti-Feed + Instant Quiz (≈2 ngày)
- `VerticalPager` vuốt dọc. Mỗi thẻ có các pha Hook (0–3s) → Body (3–15s, animation `visual` + TTS tùy chọn) → Quiz.
- `SwipeQuizCard` dùng `anchoredDraggable`: thẻ nghiêng theo ngón tay, viền xanh/đỏ, vượt ngưỡng thì chốt đáp án.
- `HapticsKit`: rung đúng = CLICK; rung sai = THUD-THUD. Pháo hoa vẽ bằng Canvas cho nhẹ.
- `Srs` + `Wallet` (chống cày điểm). Thứ tự feed: thẻ đến hạn ôn → thẻ mới → thẻ đã thuộc.
- Test: `Srs`, `Wallet`, chọn thẻ tiếp theo, screenshot các trạng thái quiz.

### Giai đoạn 3: Interceptor (≈2–3 ngày, cốt lõi)
- `GuardAccessibilityService` (`accessibility_service_config.xml`: chỉ nghe typeWindowStateChanged, `canRetrieveWindowContent=false`).
- `InterceptPolicy` viết thuần Kotlin và có test đầy đủ: giấy thông hành, ân hạn, vé, chia đôi màn hình, mở lại app liên tục, đổi ngày.
- `InterceptActivity`: Hook UI → một phiên 1 thẻ → Reward → `moveTaskToBack` / mở lại gói X. Chế độ Mềm/Nghiêm, đếm số lần bỏ qua (reset lúc 0h).
- Onboarding: bật Accessibility (deep link tới Settings), Autostart của Xiaomi (`com.miui.securitycenter`, có dự phòng), pin "Không giới hạn", quyền thông báo. Mỗi lần mở app đều kiểm tra lại.
- Màn chọn app (từ danh sách app có launcher, kèm danh sách gợi ý).
- **Thử trên Xiaomi 14T:** thời gian che, quay lại app gốc, khởi động lại máy, dọn RAM.

### Giai đoạn 4: Vé lướt + Streak + cứu chuỗi (≈1,5 ngày)
- Mua vé (5 điểm = 10 phút). Bộ đếm vé chạy trong service, hết vé thì hiện màn "Hết vé".
- `Streak` tính theo `DailyStat`. `StreakSaverWorker` chạy lúc 21:00 (giờ tùy chỉnh): nếu hôm nay chưa học thì gửi thông báo, mở phiên ôn 3 câu, phải đúng liên tiếp cả 3.
- Màn Home: chuỗi ngày, điểm, vé, "Hôm nay đã bẻ hướng 7 lần, tiết kiệm khoảng 40 phút".

### Giai đoạn 5: Widget Đấu Trường (≈1,5 ngày)
- Glance widget 2x2 (1 câu, 3 nút) và 4x2 (câu hỏi + 3 đáp án + nút "câu tiếp"). Dùng `actionRunCallback` để chấm điểm và đổi màu nền xanh/đỏ, ghi điểm vào Room.
- `WidgetRefreshWorker` mỗi 24 giờ: lấy thẻ đã xem hoặc làm sai, đến hạn ôn trước.
- Bo góc và màu theo HyperOS, hỗ trợ chế độ tối.

### Giai đoạn 6: Hoàn thiện (≈1 ngày)
- Màn thống kê (số lần chặn, tỷ lệ chuyển thành học, chủ đề mạnh/yếu), xuất/nhập tiến độ ra JSON.
- Cài đặt: chủ đề yêu thích, tắt/bật TTS, giờ cứu chuỗi.
- Ký release và build APK release.

**Tổng ước tính: khoảng 10–12 ngày làm việc.**

### Ngoài phạm vi V1
Video ngắn từ creator, UGC và duyệt chéo, tài khoản, đồng bộ cloud, phát hành lên Play Store.

---

## 4. Các quyết định cần bạn chốt (kèm mặc định đề xuất)
1. **Kinh tế điểm:** 1 đúng = 1 điểm, 5 điểm = 10 phút vé, mỗi thẻ chỉ tính điểm lần đầu trong ngày.
2. **Chế độ mặc định:** Mềm (3 lần bỏ qua/ngày).
3. **Định nghĩa streak:** ≥1 phiên học/ngày từ bất kỳ nguồn nào.
4. **Phong cách giao diện:** proposal muốn kiểu TikTok (tối, nhiều màu, pháo hoa), còn các app trước của bạn theo hướng tối giản sang trọng. Đề xuất: nền tối kiểu feed, một màu nhấn duy nhất, pháo hoa tiết chế.
5. **Ngôn ngữ nội dung:** tiếng Việt, riêng chủ đề Business English có câu mẫu tiếng Anh.
6. **Thứ tự làm:** bộ chặn trước widget (ngược với proposal).
