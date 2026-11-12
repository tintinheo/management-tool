# Youtube Sync

Ứng dụng Android (Xiaomi 14T / HyperOS) tự tải video quay bằng camera (`DCIM/Camera`) lên kênh YouTube của bạn. Mỗi video được đặt ở chế độ **Riêng tư**, **Không dành cho trẻ em**, và app gửi kèm yêu cầu **giới hạn 18+**.

## Cài đặt một lần: Google Cloud (khoảng 10 phút)

App không chứa client ID hay secret. Google nhận ra app qua **package name và SHA-1** của khoá ký.

1. Vào https://console.cloud.google.com, tạo project, ví dụ `youtube-sync`.
2. Mở **APIs & Services → Library**, tìm **YouTube Data API v3** rồi bấm **Enable**.
3. Mở **Google Auth Platform → Branding**: điền tên app và email hỗ trợ. User type chọn **External**.
4. Mở **Audience**:
   - Thêm Gmail sở hữu kênh vào **Test users**.
   - Nên bấm **Publish app** để chuyển sang *In production*. Ở chế độ *Testing*, quyền truy cập hết hạn sau 7 ngày và bạn phải kết nối lại. Ở chế độ production mà chưa xác minh, Google sẽ hiện màn hình "Google chưa xác minh ứng dụng này". Bấm **Nâng cao → Đi tới … (không an toàn)** là dùng được, vì app chỉ dành cho riêng bạn.
5. Mở **Data Access → Add scopes** và thêm:
   - `https://www.googleapis.com/auth/youtube.upload`
   - `https://www.googleapis.com/auth/youtube.readonly`
6. Mở **Clients → Create client → Android** và điền:
   - Package name: `com.ytsync`
   - SHA-1: `34:03:0D:0B:4F:C0:50:44:9F:27:62:57:41:48:43:D0:BE:B9:86:D0`

   Đây là SHA-1 của `keystore/youtubesync.keystore`. Bản debug và release dùng chung khoá này. Không được xoá file keystore: nếu tạo lại khoá thì phải khai báo SHA-1 mới.

### Khi app báo lỗi `UNREGISTERED_ON_API_CONSOLE` hoặc `DEVELOPER_ERROR`

Google không tìm thấy Android client nào khớp với app. Thông báo lỗi trong app có ghi đúng package name và SHA-1 của bản đang cài; bạn bôi đen để copy. Kiểm tra lần lượt:

1. Client có **loại Android**, không phải Web hay Desktop.
2. Package là `com.ytsync`. Ô SHA-1 chứa **SHA-1**: 20 cặp ký tự hex, ngăn cách bằng dấu hai chấm. Không dán nhầm SHA-256, vì SHA-256 dài gấp đôi.
3. Client nằm **cùng project** với YouTube Data API v3 và màn hình xin quyền (Branding/Audience). Xem tên project ở thanh trên cùng của Console.
4. Tạo thêm một client loại **Web application** trong cùng project, không cần điền gì. Tài liệu của Google có yêu cầu bước này, và thực tế lỗi này thường hết sau khi tạo.
5. Sau khi lưu, chờ 5–30 phút. Sau đó vào Cài đặt → Ứng dụng → Youtube Sync, chọn **Buộc dừng**, rồi thử lại.

## Trên điện thoại

1. Cài `app/build/outputs/apk/release/YoutubeSync-1.0.0-release.apk`.
2. Trong app, làm lần lượt phần **Thiết lập**:
   - **Kết nối kênh YouTube.**
   - **Quyền đọc video:** chọn *Cho phép tất cả*.
   - **Thông báo.**
   - **Chạy nền không giới hạn.**
   - **Tự khởi chạy (HyperOS).**
3. Bật **Tự động tải lên**. Từ lúc này, mọi video mới quay sẽ được đưa vào hàng đợi. Nếu muốn tải cả video cũ, dùng **Thêm video cũ…**.

## Đặt tên video

Mục **Tự động tải lên → Mẫu tiêu đề video**. Mặc định là `{YYYYMMDD} Video {HH}:{mm}`, ví dụ `20261109 Video 17:51`. Mọi biến ngày giờ lấy theo **thời điểm quay** video, không phải lúc tải lên.

| Biến | Ý nghĩa | Ví dụ |
|---|---|---|
| `{YYYYMMDD}` | năm tháng ngày | 20261109 |
| `{YYYY}` `{MM}` `{DD}` | năm / tháng / ngày | 2026 / 11 / 09 |
| `{HH}` `{mm}` `{ss}` | giờ / phút / giây | 17 / 51 / 30 |
| `{name}` | tên file, bỏ đuôi | VID_20261109_175130 |

Lưu ý: `{MM}` viết hoa là tháng, `{mm}` viết thường là phút.

Một số mẫu:
- `{YYYYMMDD} Du lịch Đà Lạt` → `20261109 Du lịch Đà Lạt`
- `{YYYYMMDD}_{HH}{mm}{ss}` → `20261109_175130`. Nên dùng mẫu này nếu một ngày quay nhiều video: tên không bị trùng, và Studio sắp xếp đúng thứ tự.
- `{YYYYMMDD} {name}` → `20261109 VID_20261109_175130`

Chạm vào các biến bên dưới ô để chèn vào cuối mẫu. Dòng **Ví dụ** cho xem trước kết quả ngay khi gõ. Nút **Mặc định** khôi phục mẫu gốc. Nếu để trống ô, app dùng mẫu mặc định.

Mẫu mới chỉ áp dụng cho các video **bắt đầu tải lên sau khi đổi**. Video đã lên YouTube, hoặc đang tải dở, vẫn giữ tên cũ. Muốn đổi tên những video đó thì sửa trong Studio.

## Tab Video: xem trước, chọn nhóm, đặt tên

Tab **Video** ở thanh dưới liệt kê **mọi** video trong DCIM/Camera, nhóm theo ngày quay, mới nhất ở trên. Mỗi video hiện:
- thumbnail lấy từ khung hình của video, kèm thời lượng;
- tiêu đề trên YouTube. Chữ xám là tên tự động theo mẫu, chữ trắng là tên đã đặt riêng;
- giờ quay, dung lượng và trạng thái (Chờ tải, Đang tải, Đã lên YouTube…).

Cách dùng:
- **Chạm** một video để xem trước ngay trong app, có thanh tua. Từ đây cũng có các nút Tải lên, Đặt tên, Mở Studio.
- **Giữ** một video để bật chế độ chọn nhiều. Sau đó chạm thêm để chọn, hoặc bấm **Chọn cả ngày**.
- **Đặt tên** áp một mẫu cho cả nhóm. Biến `{n}` đánh số 1, 2, 3… theo **thứ tự giờ quay**, và tự thêm số 0 phía trước cho đều độ dài: nhóm 12 video sẽ ra 01…12. Ví dụ `{YYYYMMDD} Đà Lạt {n}` cho ra `20261108 Đà Lạt 1`, `20261109 Đà Lạt 2`… Hộp thoại cho xem trước tên mới trước khi áp dụng.
  - Video **chưa tải lên**: tên được lưu lại và dùng khi tải lên.
  - Video **đã lên YouTube**: tên được đổi trực tiếp trên YouTube. Mô tả, tag và danh mục giữ nguyên. Lần đầu làm việc này, Google hỏi thêm quyền **quản lý video** (`youtube.force-ssl`). Nếu bạn đã khai báo phạm vi quyền ở Data Access, nên thêm scope này vào đó. Mỗi lần đổi tên tốn 51 đơn vị hạn mức, trong tổng 10.000 đơn vị/ngày.
  - Video **đang tải lên**: bị bỏ qua. Bạn đổi tên sau khi video tải xong.
- **Tải lên** đưa các video đã chọn vào hàng đợi, kể cả video cũ quay trước khi bật tự động. Tính năng này hoạt động cả khi đang tắt tự động tải lên.

## Chỉ tải video quay bằng camera

App chỉ xét thư mục `DCIM/Camera`, nên video tải về bằng trình duyệt (nằm trong `Download/`) vốn đã không xuất hiện. Tuy vậy, một số app lưu thẳng vào `DCIM/Camera`, ví dụ Zalo/Messenger khi bấm "Lưu", trình chỉnh sửa của Thư viện Xiaomi, hay CapCut. Từ bản 1.2.0, app kiểm tra **app nào đã tạo ra file** (thông tin Android ghi lại cho từng file) để loại các video này:

| Nguồn tạo file | Kết quả |
|---|---|
| App camera (`com.android.camera` của Xiaomi, hoặc bất kỳ app nào quay được video) | ✅ Tự động tải lên |
| App khác (Zalo, Chrome, Thư viện, CapCut…) | ❌ Ẩn khỏi danh sách, không tự động tải lên |
| Android không rõ nguồn (video khôi phục từ bản sao lưu, chép từ máy tính) | ✅ chỉ khi tên file có dạng camera: `VID_YYYYMMDD_HHMMSS`, `PXL_…`, `YYYYMMDD_HHMMSS` |

- Ở tab **Video**, dòng "Đã ẩn N video tải về / từ app khác · Hiện" cho phép hiện lại các video bị ẩn để kiểm tra. Những video này có nhãn đỏ ghi tên app đã lưu chúng, và bạn vẫn tải tay được nếu muốn.
- Nếu trước đây một video không phải từ camera đã vào hàng đợi mà chưa tải byte nào, app tự chuyển nó sang trạng thái **Bỏ qua**.
- Để đọc được tên app tạo file, app cần quyền "xem các app đã cài" (`QUERY_ALL_PACKAGES`), vì từ Android 14 thông tin này bị ẩn nếu thiếu quyền. Đây là app cài tay, không phát hành qua Play Store, nên dùng quyền này không gặp vấn đề.

## Ngày giờ trong tên = lúc QUAY video

Mọi biến ngày giờ (`{YYYYMMDD}`, `{HH}`…), ở cả tên tự động lẫn tên đặt theo nhóm, đều lấy theo **thời điểm quay**. App xác định thời điểm này theo thứ tự ưu tiên:
1. Dấu thời gian trong tên file của camera: `VID_20261109_175130.mp4` (Xiaomi), `PXL_…` (Pixel), `20261109_175130.mp4` (Samsung). Đây là giờ địa phương lúc bắt đầu quay.
2. DATE_TAKEN của Android. Hệ thống đọc giá trị này từ metadata của chính file video.
3. Thời điểm tạo file ghi trong metadata của video. App đọc trực tiếp từ file khi không có hai thông tin trên.
4. Thời điểm file được ghi lần cuối, gần bằng lúc quay xong.

App **không bao giờ** dùng ngày tải lên hay ngày file được chép vào máy. Vì vậy video cũ, hoặc video chép từ máy khác, vẫn giữ đúng ngày quay.

## Cách hoạt động

- **Phát hiện video mới:** WorkManager theo dõi thay đổi của MediaStore, chờ 10 giây sau khi camera ghi xong, rồi quét. Ngoài ra có một lượt quét dự phòng mỗi giờ.
- **Tải lên:** dùng giao thức resumable của YouTube. Phần còn lại của file được gửi liên tục trong một request duy nhất, không chia nhỏ nên không phải dừng chờ server sau mỗi phần, nhờ đó tốc độ bám sát tốc độ mạng. Session URI và tiến độ được lưu khoảng mỗi 0,5 giây.
- **Khi mạng chập chờn:** app thử lại ngay trong lượt chạy, sau 3 giây, 10 giây rồi 30 giây. Mỗi lần thử, app hỏi server đã nhận đến byte nào rồi gửi tiếp từ đó. Chỉ khi cả 3 lần đều hỏng, app mới để WorkManager thử lại sau.
- **Tốc độ:** thông báo tiến độ hiển thị tốc độ tải lên (MB/s).
- **Điều kiện mạng:** mặc định chỉ tải khi có Wi‑Fi. Có thể bật thêm "chỉ khi đang sạc".
- **Xử lý lỗi:**
  - Token hết hạn: app tự lấy token mới. Nếu Google yêu cầu đăng nhập lại, app báo bằng thông báo; chạm vào đó để cấp quyền.
  - Hết hạn mức: video vẫn ở trong hàng đợi và được thử lại mỗi giờ.
  - Video bị xoá trước khi tải: video được đánh dấu "Bỏ qua".

## Giới hạn cần biết

- **18+ chưa được đảm bảo qua API.** Tài liệu YouTube Data API không liệt kê trường giới hạn độ tuổi (`contentDetails.contentRating.ytRating`) trong danh sách các trường được phép ghi. App vẫn gửi `ytAgeRestricted` khi tải lên, rồi đọc lại video để kiểm tra:
  - Nếu YouTube nhận, video có nhãn **Riêng tư · 18+**.
  - Nếu YouTube bỏ qua, video ghi "18+ chưa xác nhận". Bấm **Mở Studio** và chọn *Có, giới hạn người xem trên 18 tuổi*.
  - Nếu YouTube trả lỗi 400 vì trường này, app tự tải lại mà không kèm trường đó, để video không bị kẹt.

  Lưu ý: video riêng tư thì chỉ bạn xem được, nên nhãn 18+ thực tế chỉ có tác dụng khi sau này bạn chuyển video sang công khai.
- **Hạn mức:** API cho khoảng 100 lượt `videos.insert` mỗi ngày. Kênh cũng có giới hạn tải lên hằng ngày riêng.
- **HyperOS chặn chạy nền:** nếu chưa bật "Chạy nền không giới hạn", Android có thể không cho mở dịch vụ nền. Khi đó app vẫn tải, nhưng theo từng đợt khoảng 10 phút và tiếp tục ở lần chạy sau.

## Build

```
G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.2-bin/*/gradle-9.8.2)/bin/gradle
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" "$G" testDebugUnitTest lintDebug assembleRelease -Proborazzi.test.record=true
```
