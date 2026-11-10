# RemoteCam (spike 0.1.2)

Thử nghiệm: dùng nút điều khiển nhạc của Galaxy Fit 3 làm nút chụp cho camera của Xiaomi 14T. Lý do chọn cách này xem ở [RESEARCH.md](RESEARCH.md).

## Cài đặt
1. Cài `app/build/outputs/apk/debug/RemoteCam-0.1.2-debug.apk` lên Xiaomi 14T.
2. Mở app, cho phép **Camera** và **Thông báo**.
3. Vào Settings → Apps → RemoteCam: bật **Autostart**, chọn Battery saver **No restrictions**.
4. Trong Galaxy Wearable, kiểm tra Fit 3 đã bật mục **Media controller/Nhạc**.

## Cách thử
1. Dựng điện thoại lên, mở RemoteCam. Huy hiệu góc trên bên trái phải hiện "Đang nghe Fit 3".
2. Trên Fit 3, vuốt tới widget **Nhạc**. Nếu thấy chữ "RemoteCam · 0 ảnh · cam sau" thì vòng đã nhận app làm trình phát.
3. Bấm thử từng nút và xem log ở dưới màn hình:

| Nút trên vòng | Callback mong đợi | Hành động |
|---|---|---|
| Play/Pause | `onPause` hoặc `onPlay` | Chụp |
| Next | `onSkipToNext` | Đổi camera trước/sau |
| Prev | `onSkipToPrevious` | Hẹn giờ 3s (bấm lần nữa để huỷ) |
| Volume +/- | `volume +/-` (chỉ khi bật "Volume → app") | Zoom |

4. Log ghi package gửi lệnh (`← com.samsung...`) và độ trễ từ lúc nhận lệnh đến lúc lưu xong ảnh.
5. Bấm **Copy log** rồi dán log gửi lại.

## Các nút bật/tắt
- **Giữ PLAYING**: sau mỗi lần pause, app tự báo lại trạng thái "đang phát". Nhờ vậy nút trên vòng luôn gửi pause, tránh lỗi "pause được nhưng không resume được" trên máy không phải Samsung. Thử cả hai chế độ.
- **Volume → app**: chuyển nút âm lượng của vòng sang app (dùng để zoom), thay vì chỉnh âm lượng điện thoại.
- **Âm thanh im lặng**: phát âm thanh câm để Android coi RemoteCam là app nhạc đang hoạt động. Thử tắt để xem có cần không.

## Kết quả cần ghi lại
- [ ] Vòng có hiện tên "RemoteCam · …" không
- [ ] Play/Pause có chụp được không, và có chụp liên tiếp được không (≥5 lần)
- [ ] Next / Prev / Volume có gửi lệnh không
- [ ] Độ trễ (ms) hiện trong log
- [ ] Mở app nhạc khác rồi quay lại RemoteCam: vòng còn điều khiển RemoteCam không

## Build
```
G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.2-bin/*/gradle-9.8.2)/bin/gradle
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" "$G" testDebugUnitTest lintDebug assembleDebug -Proborazzi.test.record=true
```
