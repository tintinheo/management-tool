# Nghiên cứu: Điều khiển camera Xiaomi 14T từ Samsung Galaxy Fit 3

Ngày: 2026-11-09

## 1. Kết luận

- **Không làm được live preview trên Galaxy Fit 3.** Fit 3 chạy FreeRTOS. Samsung không phát hành SDK cho bên thứ ba, nên không cài được app hay watch face tự viết lên vòng. Màn hình của vòng chỉ hiện những gì firmware Samsung có sẵn.
- **Camera Remote có sẵn của Fit 3 không dùng được với Xiaomi.** Samsung chỉ hỗ trợ tính năng này trên điện thoại Galaxy chạy One UI 6.0 trở lên. Theo tài liệu Samsung, đây cũng chỉ là nút bấm chụp, không có preview.
- **Có thể làm được: dùng Fit 3 làm nút bấm chụp, không có preview.** Nút điều khiển nhạc (Media Controller) của Fit 3 gửi lệnh play/pause/next/prev tới MediaSession đang hoạt động trên điện thoại. App camera tự viết trên Xiaomi có thể tạo MediaSession riêng và coi các lệnh đó là "chụp", "quay", "đổi camera". **Chưa kiểm chứng** được việc này hoạt động trên Xiaomi/HyperOS, nên phải thử trên máy thật trước.
- **Nếu bắt buộc phải có live preview trên cổ tay**, cần đồng hồ Wear OS (Galaxy Watch 4 trở lên, Pixel Watch, …). Một lựa chọn khác là dùng điện thoại hoặc tablet thứ hai làm màn hình điều khiển qua Wi-Fi.

## 2. Hướng A: Fit 3 làm nút chụp qua Media Controller

### Cơ chế
```
Fit 3 ──BLE──> Galaxy Wearable + Fit3 Plugin (trên Xiaomi)
                  └─ MediaController.transportControls.pause()/skipToNext()/...
                        └─> MediaSession của app RemoteCam ──> CameraX chụp/quay
```

### Thiết kế
- App giữ một `MediaSession` (media3 `MediaSessionService` hoặc `MediaSessionCompat`) ở trạng thái **PLAYING**. Để Android coi đây là app media đang hoạt động, app phát một `AudioTrack` im lặng.
- Bấm play/pause trên vòng sẽ gọi `onPause` → **chụp ảnh**. Sau đó app đặt lại trạng thái PLAYING ngay. Có báo cáo rằng trên máy không phải Samsung, vòng pause được nhưng không resume được, nên app luôn giữ PLAYING để mỗi lần bấm đều là lệnh pause.
- Gán lệnh cho các nút:
  - `onSkipToNext` → bắt đầu/dừng quay video
  - `onSkipToPrevious` → đổi camera trước/sau, hoặc hẹn giờ 3 giây
  - Âm lượng +/- (nếu vòng gửi) → zoom
- Tên bài hát trong metadata dùng để báo trạng thái lên màn hình vòng, ví dụ "📷 Ready · 12 ảnh" hoặc "🔴 REC 00:15". Đây là cách duy nhất để vòng hiển thị phản hồi.

### Camera trên Xiaomi 14T
- CameraX gồm `Preview`, `ImageCapture` và `VideoCapture<Recorder>`. Có thể dùng CameraX Extensions (HDR/Night) nếu HyperOS hỗ trợ.
- **Giới hạn của Android 14 trở lên:** app không được mở camera khi đang chạy nền. App phải đang hiện trên màn hình, hoặc foreground service `type=camera` phải được khởi động lúc app đang hiện. Cách dùng thực tế: dựng máy lên tripod, mở app, để màn hình sáng (`FLAG_KEEP_SCREEN_ON`, giảm độ sáng).
- HyperOS: phải bật Autostart, đặt pin "No restrictions" và khoá app trong Recents. Nếu không, MIUI sẽ tắt service.

### Rủi ro (phải thử trên máy thật trước khi làm tiếp)
1. Galaxy Wearable/Fit3 Plugin có bật Media Controller trên Xiaomi không. Samsung chỉ liệt kê điện thoại Galaxy.
2. Lệnh từ vòng có đến MediaSession của app tự viết không, hay chỉ đến các app nhạc phổ biến.
3. Độ trễ từ lúc bấm đến lúc chụp, dự kiến khoảng 0,3–1 giây qua BLE.

**Spike (khoảng nửa ngày):** viết app tối thiểu gồm MediaSession và âm thanh im lặng, ghi log mọi callback. Bấm thử các nút trên Fit 3 và xem log. Nếu không nhận được callback nào thì bỏ hướng A.

## 3. Hướng B: Live preview trên đồng hồ Wear OS (cần đổi thiết bị)

- Xiaomi chạy app camera. Mỗi frame từ `ImageAnalysis` được thu nhỏ còn khoảng 320×320 và nén JPEG chất lượng khoảng 50 (5–15 KB).
- Gửi frame bằng Wearable Data Layer `MessageClient` hoặc `ChannelClient` (chạy qua BLE/Wi-Fi). Tốc độ thực tế khoảng 5–15 fps. Lệnh điều khiển gửi ngược lại từ đồng hồ.
- Đồng hồ chạy app Compose for Wear OS: preview, nút chụp/quay, đổi camera, đếm giờ, xem lại ảnh vừa chụp.
- Những app tương tự (Camera One, Camera Remote for Wear OS) đều làm theo cách này. App phải tự chạy camera, không điều khiển được app Camera gốc của Xiaomi.

## 4. Hướng C: Điện thoại hoặc tablet thứ hai làm màn hình điều khiển

- Dùng WebRTC hoặc MJPEG qua Wi-Fi LAN/hotspot. Độ trễ khoảng 100–200 ms, preview 720p mượt.
- Đây là hướng dễ làm nhất nếu không cần đeo trên tay.

## 5. Đề xuất

1. Làm spike hướng A trên Xiaomi 14T cùng Fit 3 đang có.
2. Nếu spike chạy được: làm app RemoteCam gồm CameraX, MediaSession nhận lệnh và trạng thái hiện trên vòng qua metadata.
3. Viết module camera và module lệnh độc lập với kênh truyền. Như vậy sau này có thể thêm `:wear` (hướng B) hoặc web/WebRTC (hướng C) mà không phải viết lại.

## Nguồn
- Fit 3 chạy FreeRTOS, không có app bên thứ ba: https://forum.developer.samsung.com/t/can-i-publish-an-app-to-manage-the-galaxy-fit-3-spheres/39511 , https://forum.developer.samsung.com/t/design-a-watchface-for-fit3/38315
- Camera Remote chỉ dùng với Galaxy One UI 6.0 trở lên: https://www.samsung.com/ae/support/mobile-devices/how-to-use-camera-remote-on-the-galaxy-fit3/ , https://www.samsung.com/lb/watches/galaxy-fit/galaxy-fit3-dark-gray-bluetooth-v5-3-sm-r390nzaamea/
- Media Controller của Fit 3: https://www.samsung.com/us/support/answer/ANS10004900/
- Báo cáo trên máy không phải Samsung (pause được, resume không): https://forums.pocketcasts.com/forums/topic/galaxy-fit-3-media-control-problems/
- App camera remote cho Wear OS: https://www.makeuseof.com/how-to-control-android-phone-camera-galaxy-watch-4-5/
