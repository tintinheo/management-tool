# BookTrans – Dịch sách EPUB Anh → Việt, tạo sách song ngữ

App desktop (Windows, Python + Tkinter) dịch sách văn chương từ tiếng Anh sang tiếng Việt bằng các
model AI **miễn phí**, theo cách làm của một nhà xuất bản: viết hồ sơ tác phẩm trước, dịch có ngữ
cảnh, rồi biên tập lượt hai. Kết quả là một file EPUB song ngữ (đoạn Anh rồi đến đoạn Việt) hoặc chỉ
có tiếng Việt, giữ nguyên định dạng gốc.

## Chạy

```
run.bat            (hoặc: python -m booktrans)
```

Chỉ cần Python 3.10+ cùng `lxml` và `httpx` (máy này đã có sẵn cả hai). Tiến độ dịch được lưu vào
`<tên-sách>.btproj.db`, đặt cạnh file EPUB. Nhờ vậy có thể dừng bất cứ lúc nào, hôm sau mở lại
dịch tiếp.

## Quy trình 4 bước

1. **Cài đặt model.** Thêm một hoặc nhiều kết nối. Thứ tự trong danh sách là thứ tự ưu tiên: khi
   model đầu hết quota trong ngày, app tự chuyển sang model kế tiếp. Bấm *Kiểm tra kết nối* để dịch
   thử một câu.
2. **Hồ sơ tác phẩm.** AI đọc mẫu cuốn sách (đoạn đầu, giữa và cuối) rồi viết bản chỉ dẫn gồm: thể
   loại và bối cảnh, người kể chuyện và điểm nhìn, văn phong tác giả (có dẫn ví dụ), giọng văn tiếng
   Việt nên hướng tới, **bảng xưng hô giữa các nhân vật**, và bảng thuật ngữ / tên riêng.
   **Bạn nên đọc và sửa phần này.** Mọi lượt dịch đều phải tuân theo nó, và chất lượng cả cuốn sách
   phụ thuộc vào đây nhiều nhất. Ô *Chỉ dẫn riêng* có quyền ưu tiên cao nhất.
3. **Dịch & biên tập.** Dịch theo chương hoặc cả sách. Mỗi lần gửi kèm hồ sơ tác phẩm, những thuật
   ngữ xuất hiện trong đoạn đó và vài đoạn đã dịch ngay trước (để giữ giọng văn và xưng hô liền
   mạch). *Biên tập lượt 2* là một lượt gọi khác, đóng vai biên tập viên: so bản nháp với bản gốc để
   sửa chỗ dịch sai, sót ý, xưng hô lệch, câu văn "dịch" và giọng văn trệch. Bạn có thể sửa tay
   từng đoạn, hoặc chọn nhiều đoạn rồi bấm *Dịch lại* kèm yêu cầu (ví dụ "giọng mỉa mai hơn",
   "lão già nói cộc lốc hơn"). Đoạn bạn đã sửa tay sẽ không bị dịch đè khi dịch hàng loạt.
4. **Xuất sách.** Chọn *Song ngữ* hoặc *Chỉ tiếng Việt*. Đoạn chưa dịch giữ nguyên tiếng Anh, nên
   có thể xuất thử bất cứ lúc nào để đọc kiểm tra trên điện thoại hoặc máy đọc sách.

## Chọn model miễn phí (khảo sát tháng 11/2026)

Free tier thay đổi liên tục, nên app không gắn cứng vào model nào. Hãy dùng nút **Tải danh sách
model** để xem nhà cung cấp đang cho dùng gì.

| Nhà cung cấp | Ưu điểm | Lưu ý |
|---|---|---|
| **Google AI Studio (Gemini)** – khuyên dùng làm model chính | Tiếng Việt tốt nhất trong nhóm miễn phí; ngữ cảnh rất dài nên phân tích được nhiều mẫu | Từ 2026 free tier chủ yếu chỉ còn dòng Flash/Flash-Lite. Giới hạn tính theo project, reset lúc nửa đêm giờ Thái Bình Dương. Google không công bố cố định RPM/RPD, phải xem trong AI Studio. Ở free tier, dữ liệu có thể được Google dùng để cải thiện sản phẩm. |
| **OpenRouter** (model có đuôi `:free`) | Nhiều model mở cỡ lớn (Qwen, gpt-oss, Nemotron…) | Model miễn phí xoay vòng và hay bị nghẽn giờ cao điểm. Số yêu cầu/ngày thấp nếu tài khoản chưa nạp credit. Nên khai báo 2–3 model `:free` làm dự phòng. |
| **Groq** | Rất nhanh | Giới hạn token/phút chặt: đặt *Số ký tự gốc mỗi lần gửi* khoảng 2000–2500. |
| **Ollama / LM Studio** (chạy trên máy) | Miễn phí hoàn toàn, offline, không giới hạn | Cần GPU mạnh. Với tiếng Việt nên dùng Qwen (bản 14B trở lên). Văn chương vẫn kém Gemini rõ rệt. |

**Cấu hình gợi ý:** (1) Gemini Flash vừa dịch vừa biên tập → (2) một model `:free` lớn trên
OpenRouter làm dự phòng → (3) Ollama Qwen làm phương án cuối khi tất cả đã hết quota.

**Ước lượng khối lượng:** một tiểu thuyết khoảng 500.000 ký tự cần chừng 140 yêu cầu dịch, cộng 140
yêu cầu biên tập (với 3.500 ký tự mỗi lần gửi). Với hạn mức free vài trăm yêu cầu mỗi ngày, mất
khoảng 1–3 ngày. Tab 3 có hiển thị số yêu cầu còn lại.

Nguồn khảo sát: [Gemini API free tier 2026 – apidog](https://apidog.com/blog/gemini-api-free-tier/),
[Thay đổi giá Gemini 4/2026 – agentdeals](https://agentdeals.dev/gemini-api-pricing-changes),
[Google AI Studio free tier – benchlm](https://benchlm.ai/free-tier/google-ai-studio),
[OpenRouter free models](https://openrouter.ai/models?fmt=free),
[Danh sách model free OpenRouter 2026 – buldrr](https://buldrr.com/openrouter-free-models-list-2026-all-27-models-ranked-tested/),
[Xếp hạng model dịch – benchlm](https://www.benchlm.ai/best/translation),
[LLM mã nguồn mở cho tiếng Việt – SiliconFlow](https://www.siliconflow.com/articles/best-open-source-llm-for-vietnamese).

## Các lựa chọn thiết kế để bản dịch sát tinh thần tác phẩm

- **Hồ sơ tác phẩm trước, dịch sau.** Tiếng Việt buộc người dịch phải chọn đại từ (anh/em, tôi/cậu,
  ông/cháu, hắn/gã/lão…) mà tiếng Anh không nói ra. Vì vậy bảng xưng hô được quyết định một lần, cho
  cả cuốn sách, chứ không để model tự đoán lại ở mỗi đoạn.
- **Nguyên tắc dịch** (xem `booktrans/prompts.py`): tín – đạt – nhã theo đúng thứ tự ưu tiên. Không
  tóm tắt, không thêm thắt. Giữ nhịp câu, ẩn dụ, giọng mỉa mai, độ "thô" và độ trang trọng của bản
  gốc. Tránh văn dịch ("một cách", "được/bị", "việc", "sự"…). Chỉ thay thành ngữ khi cùng sắc thái.
  Lời thoại phải nghe như lời nói, giữ được giọng riêng của từng nhân vật.
- **Ngữ cảnh liên tục:** mỗi lần gửi kèm vài đoạn Anh–Việt đã dịch ngay trước.
- **Bảng thuật ngữ sống:** khi dịch, model đề xuất thêm thuật ngữ mới (nguồn `auto`), và chỉ những
  mục xuất hiện trong đoạn đang dịch mới được gửi đi, để tiết kiệm token.
- **Giữ định dạng:** in nghiêng (thường là nhấn mạnh, suy nghĩ thầm hoặc tên tác phẩm), chú thích,
  liên kết và xuống dòng trong thơ được thay bằng thẻ đánh số như `<i1>…</i1>` hay `<x2/>`. Model
  dịch kèm thẻ, rồi app khôi phục lại phần tử gốc. Nếu model làm hỏng thẻ, app vẫn giữ chữ, không
  để mất ảnh và không để gãy liên kết chú thích.

## Giới hạn hiện tại

- Không đọc được EPUB có DRM (app sẽ báo lỗi).
- Chưa dùng Batch API, và chưa có bước "đọc toàn bộ chương trước khi dịch".
- Chưa đóng gói thành file `.exe` (có thể làm bằng PyInstaller khi cần).

## Cấu trúc mã

```
booktrans/
  epub_io.py   đọc EPUB, tách đoạn, mã hoá định dạng inline, xuất song ngữ / tiếng Việt
  prompts.py   prompt phân tích – dịch – biên tập
  llm.py       client OpenAI-compatible: giới hạn tốc độ, retry 429/5xx, chuyển model khi hết quota
  pipeline.py  điều phối: chia đoạn, ngữ cảnh, thuật ngữ, kiểm tra kết quả, biên tập
  project.py   lưu tiến độ (SQLite)
  gui.py       giao diện Tkinter
tests/         pytest: EPUB mẫu nhiều bẫy, server LLM giả lập
```
