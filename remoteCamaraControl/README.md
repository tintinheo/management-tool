# RemoteCam (0.3.0)

Thử nghiệm: dùng nút điều khiển nhạc của Galaxy Fit 3 làm nút chụp cho camera của Xiaomi 14T. Lý do chọn cách này xem ở [RESEARCH.md](RESEARCH.md).

## Cài đặt
1. Cài `app/build/outputs/apk/debug/RemoteCam-0.3.0-debug.apk` lên Xiaomi 14T.
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

## Xem preview trên tablet / PC Windows (không cần internet)
Xiaomi mở một trang web trong mạng nội bộ. Tablet và PC chỉ cần trình duyệt (Chrome/Edge/Safari), không phải cài gì.

**Không có Wi-Fi/router, hoặc không muốn dùng internet:** dùng Hotspot của Xiaomi làm mạng riêng.
1. Trên Xiaomi: có thể **tắt dữ liệu di động**. Hotspot vẫn bật được, và preview chỉ chạy giữa các máy với nhau.
2. Settings → Connection & sharing → **Portable hotspot**: bật lên. Đặt tên và mật khẩu cố định để tablet/PC tự kết nối lại những lần sau.
3. Tắt **"Turn off hotspot automatically"**, nếu không hotspot sẽ tự tắt khi rảnh.
4. Cho tablet và PC kết nối vào hotspot đó.
   - Android tablet báo "Không có internet": chọn **Vẫn kết nối**.
   - Windows báo "No internet": bỏ qua, vẫn dùng được.
5. Mở RemoteCam. Ô **"Xem trên tablet / PC"** hiện địa chỉ dạng `http://10.x.x.x:8080/?k=123456`. Gõ địa chỉ này vào trình duyệt. Mã `k` giữ nguyên giữa các lần mở app, nên bookmark lại được.

**Có Wi-Fi ở nhà:** cho cả 3 máy vào cùng Wi-Fi rồi mở địa chỉ hiện trong app. Cách này cũng không dùng internet.

**Trên trang xem:**
- Nút bấm: chụp, hẹn giờ 3s, đổi camera, zoom, toàn màn hình.
- Phím tắt trên PC: Space chụp · T hẹn giờ · C đổi camera · +/− zoom · F toàn màn hình.
- Fit 3 vẫn điều khiển song song như trước.

Lưu ý:
- Màn hình RemoteCam trên điện thoại phải đang mở. Android không cho app dùng camera khi chạy nền.
- Chỉ ai có mã `k` mới xem được.

## Tính năng chụp/quay (0.3.0)
| Tính năng | Cách dùng trên web | Điều kiện |
|---|---|---|
| Chế độ Ảnh / Chân dung / Đêm / HDR / Video | Hàng chế độ phía trên nút chụp, phím 1–5 | Chân dung/Đêm/HDR chỉ có nếu Xiaomi mở CameraX Extensions cho app ngoài |
| RAW (DNG), RAW + JPEG, Ultra HDR | Cài đặt → Ảnh → Định dạng | Chỉ ở chế độ Ảnh, và camera phải mở RAW cho app ngoài |
| Độ phân giải tối đa (vd 50 MP) | Cài đặt → Ảnh → Độ phân giải → Tối đa | Chụp chậm hơn; app hiện kích thước thật sau khi chọn |
| Quay 4K, 60 fps | Cài đặt → Video | 60 fps chỉ hiện nếu camera hỗ trợ |
| HLG 10-bit | Cài đặt → Video → Màu | Dạng HDR phẳng, chỉnh màu được |
| Log (phẳng) | Cài đặt → Video → Màu | Đường cong log tự tạo (8-bit). **Xiaomi Log gốc không mở cho app ngoài** |
| Flash, hẹn giờ 3/10 s, bù sáng, zoom 0.6x–10x | Cài đặt và thanh zoom | Bấm vào hình để lấy nét/đo sáng |
| Thư viện | Nút Thư viện: xem ảnh thu nhỏ, bấm để tải về | Chỉ hiện file do RemoteCam chụp |

Trên điện thoại, mục **"Máy này hỗ trợ"** trong Cài đặt web và dòng log `Camera sau: …` cho biết chính xác máy mở những gì.

**Máy yếu (PC 2 GB RAM):** trang tự chọn preview **Thấp** (640 px, ~8 khung hình/giây). Trạng thái được đẩy về qua một kết nối duy nhất, không hỏi liên tục. Preview tự dừng khi thu nhỏ tab. Có thể đổi trong Cài đặt → Preview trên máy này.

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
