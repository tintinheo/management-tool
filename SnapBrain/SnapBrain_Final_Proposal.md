# BẢN ĐỀ XUẤT DỰ ÁN CHI TIẾT (FINAL PROJECT PROPOSAL)
## ỨNG DỤNG DI ĐỘNG: SNAPBRAIN (ANTI-DOOMSCROLLING FOR ANDROID/XIAOMI)

---

## 1. THÔNG TIN TỔNG QUAN DỰ ÁN (PROJECT OVERVIEW)
* **Tên ứng dụng:** SnapBrain (Tên phiên bản Việt hóa dự kiến: Mọt 60s / Nhấm Nháp)
* **Khẩu hiệu (Slogan):** *From Doomscrolling to Growth-scrolling* (Từ cuộn vô vọng đến cuộn trưởng thành).
* **Nền tảng mục tiêu:** Android (Tối ưu hóa sâu cho hệ sinh thái Xiaomi HyperOS / MIUI).
* **Mục tiêu cốt lõi:** Bẻ hướng phản xạ mở mạng xã hội vô thức (TikTok, Facebook, Instagram) của người dùng thành các phiên nạp kiến thức vi mô (micro-learning) dưới 30 giây đầy hứng khởi, thông qua cơ chế kích thích dopamine lành mạnh và hệ thống kiểm tra tương tác cao.

---

## 2. BỐI CẢNH VÀ VẤN ĐỀ THỊ TRƯỜNG (PROBLEM & MARKET CONTEXT)
* **Hành vi Doomscrolling:** Người dùng smartphone hiện đại có phản xạ "nghiện mở app" vô điều kiện ngay khi có 1-2 phút rảnh rỗi hoặc cảm thấy buồn chán. Phản xạ này bị thao túng bởi các thuật toán tối ưu dopamine của TikTok/Facebook.
* **Điểm yếu của giải pháp hiện tại:** Các ứng dụng Digital Wellbeing hoặc app chặn (như Opal, AppBlock) hoạt động theo cơ chế cấm đoán, trừng phạt (khóa cứng app). Điều này gây ức chế tâm lý, khiến người dùng nhanh chóng tắt tính năng hoặc gỡ cài đặt app.
* **Cơ hội thị trường:** Khoảng trống giữa các app thuần chặn (thiếu nội dung thay thế) và các app thuần học micro-learning như Deepstash, Nibble (đòi hỏi người dùng phải tự giác mở app). SnapBrain đóng vai trò là "cây cầu nối" chủ động đánh tráo hành vi.

---

## 3. KIẾN TRÚC TÍNH NĂNG CỐT LÕI (CORE PRODUCT ARCHITECTURE)

### A. Bộ Chặn Đánh Tráo Thông Minh (The Interceptor)
* **Cơ chế hoạt động:** Sử dụng `Accessibility Service` và `UsageStatsManager` trên Android để giám sát tầng hệ thống. Khi phát hiện gói ứng dụng (Package Name) của TikTok/Facebook/Instagram vừa được kích hoạt, SnapBrain sẽ lập tức hiển thị một màn hình đè full-screen trước khi ứng dụng kia kịp tải nội dung.
* **Lời thoại đánh thức lý trí (Hook UI):** *"Lại lướt vô thức nữa rồi! Cho xin 45 giây nạp nhanh 1 mẹo Excel/1 từ vựng rồi lướt tiếp, chịu không?"*
* **Cơ chế giảm tải ức chế (Dopamine Choice):** 
  * Nút lớn: **"Ok, nạp ngay"** (Kích hoạt phiên học 30 giây).
  * Nút nhỏ ẩn dưới: **"Không, tôi bận việc thật"** (Bỏ qua thẳng vào MXH, giới hạn 3 lần/ngày).

### B. Dòng Tin Tri Thức Vi Mô (The Anti-Feed)
* **Giao diện:** Thiết kế vuốt dọc (vertical swipe) mô phỏng chính xác trải nghiệm TikTok.
* **Cấu trúc nội dung (Cây kỹ năng):**
  * *Kỹ năng thực dụng:* Mẹo Excel/Word (ảnh động GIF 15s), mẫu câu Business English, cách viết Prompt AI.
  * *Kích thích tò mò:* Hiệu ứng tâm lý học hành vi, kiến thức tài chính cá nhân độc lạ, sự thật lịch sử/khoa học băm nhỏ.

### C. Tính Năng Kiểm Tra Tức Thì (Instant Quiz)
* **Vị trí:** Xuất hiện ngay tại giây thứ 15–20 của mỗi thẻ kiến thức để chốt chặn ghi nhớ.
* **Cơ chế tương tác ("Vuốt Tinder"):** Người dùng giữ ngón tay vào thẻ câu hỏi:
  * Vuốt sang **PHẢI** nếu nghĩ câu nhận định đó là **ĐÚNG**.
  * Vuốt sang **TRÁI** nếu nghĩ câu nhận định đó là **SAI**.
* **Phản hồi xúc giác (Haptic Logic):**
  * *Trả lời Đúng:* Máy rung nhẹ một nhịp sướng tai (Haptic click) + Pháo hoa UI + Cộng 1 Điểm Tri Thức.
  * *Trả lời Sai:* Máy rung giật 2 cái ngắn (Cảnh báo) + Hiện giải thích ngắn dưới 15 chữ + Gắn tag `#Chưa_Thuộc` để đẩy vào kho ôn tập ngắt quãng (SRS).

### D. Đấu Trường Màn Hình Chính (Đấu Trường Widget)
* **Thiết kế:** Widget kích thước 2x2 hoặc 4x2 đồng bộ ngôn ngữ phẳng, bo góc của Xiaomi HyperOS.
* **Cơ chế hoạt động:** Mỗi 24 giờ, hệ thống quét các thẻ người dùng từng xem (hoặc làm sai). Tự động đẩy thành các câu hỏi trắc nghiệm trượt (Carousel) trực tiếp trên màn hình chính.
* **Tương tác RemoteViews:** Người dùng chạm chọn đáp án A, B, C ngay trên màn hình nền mà không cần mở app. Trả lời đúng sẽ cộng điểm trực tiếp và đổi màu Widget sang xanh lá.

### E. Hệ Thống Đánh Đổi Sòng Phẳng (Gamification & Streak)
* **Hệ thống Chuỗi (Streak):** Tính theo số ngày liên tiếp người dùng thực hiện ít nhất 1 phiên bẻ hướng thành công.
* **Cơ chế Cứu Chuỗi (Streak Saver):** Nếu sắp đứt chuỗi, app gửi thông báo khẩn cấp. Người dùng phải trả lời đúng liên tiếp 3 câu hỏi ôn tập cũ để bảo toàn Streak.
* **Vé lướt mạng (Soft-lock system):** Dùng Điểm Tri Thức (Knowledge Points) tích lũy từ Instant Quiz và Widget để mua thời gian lướt MXH tự do (Ví dụ: 5 Points = 10 phút lướt tự do).

---

## 4. TỐI ƯU HÓA KỸ THUẬT CHO HỆ ĐIỀU HÀNH ANDROID (XIAOMI)
* **Xử lý tiến trình chạy ngầm:** Để tránh việc hệ thống HyperOS tối ưu pin và diệt tiến trình chạy ngầm (kill background process) của bộ chặn, dự án triển khai kiến trúc thông qua `WorkManager` kết hợp với một `Foreground Service` có hiển thị thông báo nhẹ, dịu mắt trên thanh trạng thái.
* **Tận dụng Haptic Feedback:** Tùy biến sâu qua thư viện `Vibrator` của Android để đồng bộ các nhịp rung cơ học chất lượng cao trên các dòng máy Redmi/Xiaomi, mang lại cảm giác phản hồi cao cấp như nút Thả tim trên TikTok.

---

## 5. CẤU TRÚC KỊCH BẢN NỘI DUNG MẪU 30 GIÂY (CONTENT SCRIPT)

### Kịch bản 1: Chủ đề Mẹo công sở (Flash Fill trong Excel)
* **00:00 - 00:03 (Hook):** Màn hình hiện icon Excel bị nổ tung + Chữ lớn: *"Đừng ngồi gõ tay tách họ tên nữa!"* + Máy rung mạnh.
* **00:03 - 00:15 (Body):** Ảnh động GIF chạy mượt thao tác gõ hàng đầu -> Bấm **Ctrl + E** -> 1000 dòng tự điền trong 0.5 giây. Giọng đọc ngắn gọn giải thích tính năng Flash Fill.
* **00:15 - 00:25 (Instant Quiz):** Thẻ thu nhỏ thành dạng câu đố Tinder: *"Tổ hợp phím Ctrl + E dùng để kích hoạt Flash Fill?"* -> Người dùng vuốt sang PHẢI (Đúng).
* **00:25 - 00:30 (Reward):** Hiệu ứng Ting Ting + Rung nhẹ + Hiện thông báo: *"Nhận 1 Vé lướt mạng 5 phút. Bạn đã thông minh hơn hôm qua!"*

---

## 6. LỘ TRÌNH TRIỂN KHAI DỰ ÁN (ROADMAP)

### Giai đoạn 1: Khảo sát & Phát triển MVP (Tháng 1 - Tháng 2)
* Phát triển hệ thống Widget hiển thị đố vui trên Xiaomi để đo lường mức độ tương tác tự nguyện.
* Ứng dụng AI (ChatGPT/Claude) để băm nhỏ dữ liệu và xây dựng kho 200 mẩu tri thức đầu tiên.

### Giai đoạn 2: Hoàn thiện Tính năng Cốt lõi (Tháng 3 - Tháng 4)
* Lập trình bộ chặn `Interceptor` sử dụng quyền Trợ năng trên Android.
* Triển khai tính năng vuốt chọn Đúng/Sai (Instant Quiz) và logic "Vé lướt mạng".

### Giai đoạn 3: Tăng trưởng & Hệ sinh thái (Tháng 5 trở đi)
* Hợp tác với các Content Creator hệ kiến thức sạch tại Việt Nam để lấy nguồn video ngắn độc quyền.
* Mở tính năng User-Generated Content (UGC) cho phép cộng đồng tự đóng góp câu hỏi tri thức và duyệt nội dung chéo lẫn nhau.

---
*Bản đề xuất được lập ngày 06/11/2026 bởi Đội ngũ Phát triển Dự án SnapBrain.*
