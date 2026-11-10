# imgvault — Sync ảnh lên Postimages + gallery quản lý

Tool này sync ảnh từ thư mục trên máy lên **gallery Postimages** của tài khoản bạn, qua API key chính thức. Tool có kèm một **gallery chạy trên máy** (`http://127.0.0.1:8765`) để xem ảnh, copy link và quản lý những ảnh đã upload.

- Ảnh trùng nội dung chỉ upload một lần (so mã SHA-256). Đổi tên hay chuyển thư mục cũng không bị upload lại.
- Album trong gallery đi theo cây thư mục trên máy. Có thể đưa từng thư mục con vào gallery Postimages riêng (mục `[galleries]` trong config).
- Copy link nhanh: link trực tiếp, trang ảnh, Markdown, BBCode, HTML.
- Mỗi lần sync upload tối đa `batch_limit` ảnh rồi dừng (mặc định 500, chỉnh trong config).
- Đếm lượt upload theo tháng, tự dừng khi chạm `monthly_limit` (gói Free: 1.000 lượt/tháng).

## Cần biết trước về Postimages

- **Gallery chỉ "không công khai", không riêng tư.** Ảnh không hiện trong danh mục chung, nhưng ai có link đều xem được, và Postimages không có mật khẩu bảo vệ.
- **EXIF (kể cả vị trí GPS) được giữ nguyên mặc định.** Nên vào *Settings* của tài khoản Postimages và bật tuỳ chọn xoá EXIF trước khi sync.
- **Postimages không có tài liệu API công khai.** Tool dùng cùng kiểu request với app desktop của Postimages, theo những gì cộng đồng ghi lại. Nếu Postimages thay đổi, upload có thể lỗi. Vì vậy hãy thử `--limit 1` trước.
- **Không có API để liệt kê hay xoá ảnh.** Danh sách ảnh nằm trong chỉ mục trên máy (`data/`). Muốn xoá thì dùng trang xoá của Postimages, gallery sẽ mở trang đó giúp bạn.
- **Gói Free:** link trực tiếp bị giới hạn 1280px, mỗi file tối đa 32 MB.
- **Postimages không cam kết giữ file.** Đừng dùng nó làm nơi backup duy nhất.

## Cài đặt (một lần)

1. Đăng nhập https://postimages.org, mở https://postimages.org/login/api và lấy **API key**.
2. Tạo một gallery tại https://postimg.cc/files. Mở gallery đó và lấy **ID** trong link `postimg.cc/gallery/<ID>`.
3. Chạy:
   ```powershell
   cd D:\portfolio\management-tool\img
   python -m venv .venv
   .venv\Scripts\pip install -r requirements.txt
   copy config.example.toml config.toml   # rồi mở ra, điền api_key, gallery, folders
   ```
4. Thử upload một ảnh:
   ```powershell
   .venv\Scripts\python -m imgvault sync --limit 1
   ```
   Sau đó mở gallery trên postimg.cc để xem ảnh đã vào đúng chỗ chưa.

## Sử dụng

| Lệnh | Tác dụng |
|---|---|
| `python -m imgvault sync` | Upload ảnh mới trong các `folders` |
| `python -m imgvault sync D:\Anh\2025` | Sync một thư mục cụ thể |
| `python -m imgvault sync --dry-run` | Chỉ liệt kê ảnh chưa sync |
| `python -m imgvault sync --limit 20` | Upload tối đa 20 ảnh lần này (ghi đè `batch_limit`; `--limit 0` = không giới hạn) |
| `python -m imgvault sync --watch 300` | Chạy liên tục, quét lại mỗi 5 phút |
| `python -m imgvault serve` | Mở gallery trên trình duyệt |
| `python -m imgvault status` | Số ảnh đã upload, số lượt đã dùng trong tháng, số ảnh trên máy chưa sync |
| `python -m imgvault rebuild` | Khôi phục chỉ mục từ `data/uploads.jsonl` |

**Gallery** cho phép:
- Duyệt ảnh theo album (thư mục), tìm theo tên hoặc đường dẫn.
- Xem ảnh lớn (lấy từ Postimages), dùng phím ← → để chuyển ảnh.
- Copy link, mở trang ảnh trên Postimages, tải file gốc trên máy, xoá ảnh, và bấm nút **Sync**.

**Xoá ảnh:**
1. Bấm **Xoá…**. Gallery mở trang xoá của Postimages trong tab mới.
2. Xác nhận xoá ở tab đó.
3. Quay lại gallery, bấm OK để gỡ ảnh khỏi danh sách.

File trên máy không bị xoá, và ảnh đó sẽ không bị upload lại.

## Thư mục OneDrive và backup

- **File chỉ có trên OneDrive** (biểu tượng đám mây, Files On-Demand) được tự động tải về máy ngay trước khi upload. Mỗi lần tool chỉ tải về đúng số file nó sắp upload. Hết lượt upload trong tháng thì ngừng tải. `--dry-run` và `status` không tải gì về. Ứng dụng OneDrive phải đang chạy.
- **Sau khi upload thành công**, file gốc được đưa vào `backup_dir` và giữ nguyên cấu trúc thư mục con, ví dụ `D:\backup\personal\2024\a.jpg`.
  - `after_upload = "move"`: chuyển hẳn file đi.
    - Tool chỉ xoá file gốc sau khi bản sao trong backup đã khớp mã hash với file gốc.
    - Xoá trong thư mục OneDrive nghĩa là xoá khỏi OneDrive trên mọi máy. File đã xoá vẫn nằm trong Thùng rác của OneDrive khoảng 30 ngày.
  - `after_upload = "copy"`: giữ file gốc, thêm một bản sao vào backup.
- **Trùng tên trong backup** (khác nội dung): file mới được đặt tên kèm số, ví dụ `a (1).jpg`. Tool không bao giờ ghi đè.
- **Backup lỗi** (ví dụ file đang bị khoá): ảnh vẫn được tính là đã upload, và bước backup tự chạy lại ở lần sync sau, không upload lại.
- **Ảnh trùng nội dung** với ảnh đã upload cũng được đưa vào backup, nên thư mục nguồn được dọn sạch.
- Gallery tìm file gốc ở cả thư mục nguồn lẫn thư mục backup.

## Hành vi cần biết

- **Xoá file trên máy:** ảnh vẫn còn trên Postimages.
- **Sửa nội dung ảnh:** coi là ảnh mới và upload thêm. Bản cũ vẫn còn trên Postimages.
- **File vừa ghi chưa đầy 5 giây** được để lại cho lần sync sau, phòng trường hợp file còn đang copy.
- **File lớn hơn `max_file_mb`** bị bỏ qua và được báo trong kết quả.
- **Upload lỗi** (mất mạng, bị giới hạn tốc độ): tool tự thử lại vài lần. Nếu vẫn lỗi thì để lần sync sau.

## Sao lưu

Postimages không cho lấy lại danh sách ảnh của bạn qua API. Vì vậy hãy sao lưu thư mục `data/`, nhất là file `uploads.jsonl`: mỗi lần upload hay xoá ảnh, tool ghi thêm một dòng vào đó. Mất `imgvault.db` thì chạy `rebuild` để khôi phục từ `uploads.jsonl`.

`config.toml` (chứa api_key) và `data/` (chứa link xoá ảnh) đã có trong `.gitignore`. Không chia sẻ hai thứ này.

## Phát triển

```powershell
.venv\Scripts\pip install pytest pytest-asyncio
.venv\Scripts\python -m pytest
```
