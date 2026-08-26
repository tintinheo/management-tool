BUSINESS REQUIREMENTS DOCUMENT

# Saturn Velocity Web Application

*Chuẩn hóa tính sprint capacity và chuẩn bị triển khai trên Streamlit Community Cloud*

Nguồn chính: Saturn Velocity 2024-2025-2026 (1).xlsx

Đích triển khai: Streamlit Community Cloud theo yêu cầu người dùng [Source: UR-REQ]

Trạng thái: **[ASSUME]** Missing data: người phê duyệt và ngày phê duyệt. Reasoning: chưa có workflow sign-off trong nguồn. Draft for validation.

## 1. Executive summary

Workbook hiện tại tổ chức dữ liệu theo từng sprint sheet. Mỗi sheet kết hợp khu vực ticket, lịch nghỉ/public holiday, tham số sprint, capacity theo resource và summary Dev/QC/Biz trong cùng một trang tính. [Source: WB-s192-INPUT; WB-s192-META; WB-s192-SUM; WB-s192-RES]

Công thức mới nhất tính Dev days bằng NETWORKDAYS rồi trừ một hằng số 3 và Back up; Team velocity (Biz) là giá trị nhỏ hơn giữa Dev V và QC V; QC - Dev là QC V trừ Dev V; Buffer in day bằng Buffer nhân Dev days. [Source: WB-s192-CALC]

Đề xuất mục tiêu: **[ASSUME]** Missing data: product owner chưa xác nhận mục tiêu thay thế workbook hay chỉ bổ trợ. Reasoning: nguồn cho thấy dữ liệu, công thức và scenario đang bị phân tán theo sheet. Đề xuất xây một ứng dụng tập trung để nhập liệu, tính toán, so sánh scenario, kiểm tra lỗi và xuất snapshot có truy vết. [Source: WB-INDEX; WB-s189-1; WB-s189-2; WB-s175-ERR]

## 2. Current-state findings

| Khía cạnh | Phát hiện có bằng chứng |
| --- | --- |
| Cấu trúc nhập liệu | Ticket dùng các trường Dev, QC, Category, Current Assignee, Ticket ID, Summary, Priority, Point, Status, Added after Sprint start và Note; khối resource/leave chứa Resource, Leave date, Leave days, Note, Public holiday và Note. [Source: WB-s192-INPUT] |
| Tham số sprint | Sheet s192 ghi Start 30/July/2026, End 19/August/2026; Development Days kết thúc 14/August/2026; Public Holiday 0; Buffer 0.1; Backup 1 day. [Source: WB-s192-META] |
| Capacity theo người | Schema mới nhất có Velocity per day, Leave days, OT in hours, OT (in days), V, V OT, Full V, các biến thể FTE, V %, Others và Type. [Source: WB-s192-RES] |
| Bottleneck | Team velocity (Biz) lấy MIN giữa Dev V và QC V, do đó output Biz bị giới hạn bởi nhánh có capacity thấp hơn. [Source: WB-s192!K44 via WB-s192-CALC] |
| Scenario OT | Hai sheet s189-1stOT và s189-FTEOT dùng cùng cửa sổ sprint nhưng có bộ OT và kết quả capacity khác nhau. [Source: WB-s189-1; WB-s189-2] |
| Schema evolution | Summary giai đoạn s158 kết thúc tại cột O, trong khi s192 mở rộng đến cột Y và bổ sung các biến thể no-AL/no-PH, Regression và Public Holiday. [Source: WB-s158-SUM; WB-s192-SUM] |
| Lỗi nguồn | Bản render của s175 hiển thị #VALUE! tại E44:G44, E48:F51 và E57. [Source: WB-s175-ERR] |
| Ticket data | Không có dòng ticket được điền trong A3:K35 của các worksheet; workbook chỉ cung cấp schema ticket. [Source: WB-ALL-TICKET] |

## 3. Business objective và success definition

- **[ASSUME]** Missing data: tiêu chí thành công chưa được xác nhận. Reasoning: workbook là công cụ tính rời rạc theo sheet. Đề xuất một nguồn dữ liệu logic duy nhất cho sprint, resource, leave, scenario và kết quả. [Source: WB-INDEX; WB-s192-SUM]

- **[ASSUME]** Missing data: chuẩn tính toán chính thức chưa được sign-off. Reasoning: s175 có lỗi hiển thị và schema thay đổi theo thời gian. Đề xuất chỉ coi các test case đã được business duyệt là golden master. [Source: WB-s175-ERR; WB-s158-SUM; WB-s192-SUM]

- **[ASSUME]** Missing data: mức truy vết mong muốn chưa được nêu. Reasoning: workbook dùng nhiều công thức structured reference khó đọc. Đề xuất mỗi output hiển thị input, rule version và công thức nghiệp vụ đã áp dụng. [Source: WB-s192-CALC]

- **[ASSUME]** Missing data: chính sách deploy miễn phí chưa chi tiết. Reasoning: Community Cloud triển khai từ GitHub và cung cấp URL streamlit.app. Đề xuất MVP tương thích quy trình đó. [Source: UR-REQ; ST-CC; ST-DEPLOY]

## 4. Stakeholders và quyền

| Vai trò đề xuất | Nhu cầu | Cơ sở / trạng thái |
| --- | --- | --- |
| Planner / Editor | **[ASSUME]** Missing data: ai nhập sprint, leave và OT. Reasoning: các vùng này là input trực tiếp trong workbook. Đề xuất quyền tạo/sửa scenario và chạy tính toán. [Source: WB-s192-INPUT; WB-s192-RES] | Chưa có role trong nguồn. |
| Reviewer / Approver | **[ASSUME]** Missing data: ai phê duyệt velocity. Reasoning: có lỗi và nhiều schema nên cần sign-off trước khi khóa snapshot. Đề xuất quyền review, acknowledge warning và approve. [Source: WB-s175-ERR; WB-s192-SUM] | Chưa có workflow trong nguồn. |
| Viewer | **[ASSUME]** Missing data: đối tượng chỉ xem. Reasoning: dashboard và export có thể cần chia sẻ mà không cho sửa. Đề xuất quyền read-only. [Source: UR-REQ] | Chưa có access matrix. |
| Administrator | **[ASSUME]** Missing data: người quản trị cấu hình. Reasoning: các constant và mapping schema cần quản trị tập trung. Đề xuất quyền quản lý rule version, danh mục và import mapping. [Source: WB-s192-CALC; WB-s158-SUM; WB-s192-SUM] | Chưa có owner. |

## 5. Scope

### 5.1 In scope cho MVP

- **[ASSUME]** Missing data: phạm vi MVP chưa được chốt. Reasoning: workbook có dữ liệu và logic cốt lõi. Đề xuất quản lý sprint, development window, public holidays, buffer, backup và các hệ số summary. [Source: WB-s192-META; WB-s192-SUM]

- **[ASSUME]** Missing data: resource master chưa tách khỏi sprint. Reasoning: resource và velocity/day lặp lại trong mỗi sheet. Đề xuất resource master có override theo sprint/scenario. [Source: WB-s158-SUM; WB-s192-RES]

- **[ASSUME]** Missing data: cách lập kế hoạch leave chưa chính thức. Reasoning: workbook lưu Leave date/Leave days và có giá trị TBD. Đề xuất leave event có status Planned hoặc TBD thay vì trộn text vào cột ngày. [Source: WB-s192-INPUT]

- **[ASSUME]** Missing data: mô hình scenario chưa có UI. Reasoning: s189 dùng hai sheet cho hai phương án OT. Đề xuất clone scenario và so sánh kết quả cạnh nhau. [Source: WB-s189-1; WB-s189-2]

- **[ASSUME]** Missing data: quy tắc ticket chưa đủ. Reasoning: chỉ có header, không có record mẫu. Đề xuất giữ ticket grid ở mức optional và không cho ticket ảnh hưởng capacity cho đến khi có rule sign-off. [Source: WB-ALL-TICKET]

- **[ASSUME]** Missing data: định dạng export chưa được chỉ định. Reasoning: người dùng đang vận hành bằng Excel. Đề xuất tải xuống snapshot XLSX/CSV và báo cáo summary. [Source: WB-INDEX; UR-REQ]

### 5.2 Out of scope mặc định

- **[ASSUME]** Missing data: hệ thống ticket nguồn chưa được nêu. Reasoning: workbook không có ticket record. Jira/API integration nằm ngoài MVP. [Source: WB-ALL-TICKET]

- **[ASSUME]** Missing data: payroll/timekeeping rules không có trong nguồn. Reasoning: OT chỉ phục vụ capacity. Payroll calculation nằm ngoài scope. [Source: WB-s189-1; WB-s189-2]

- **[ASSUME]** Missing data: retention và multi-user concurrency chưa có. Reasoning: local storage trên Community Cloud không được bảo đảm. Persistent shared database nằm ngoài MVP stateless và chỉ bật khi có quyết định. [Source: ST-PERSIST]

- **[ASSUME]** Missing data: enterprise authorization chưa được cung cấp. Reasoning: Streamlit OIDC xác thực người dùng nhưng không tự cung cấp authorization. Fine-grained RBAC nằm ngoài MVP nếu chưa có role matrix. [Source: ST-AUTH]

## 6. Functional requirements

| ID | Capability | Requirement | Acceptance |
| --- | --- | --- | --- |
| FR-01 | Sprint setup | **[ASSUME]** Missing data: UI chưa được mô tả. Reasoning: metadata hiện nằm ở A36:A40. Cho phép nhập Start/End, development window, public holiday, buffer và backup. [Source: WB-s192-META] | Dữ liệu hợp lệ được lưu trong session/snapshot và hiển thị lại đúng. |
| FR-02 | Resource setup | **[ASSUME]** Missing data: resource master chưa tồn tại. Reasoning: cùng trường lặp theo sprint. Cho phép nhập resource, velocity/day, V%, Others và Type. [Source: WB-s192-RES] | Mỗi resource có một record rõ ràng trong scenario. |
| FR-03 | Leave & holiday | **[ASSUME]** Missing data: rule TBD chưa có. Reasoning: Leave date có thể là ngày hoặc TBD. Cho phép event có ngày/ngày công hoặc trạng thái TBD tách biệt. [Source: WB-s192-INPUT] | Không trộn text TBD vào kiểu dữ liệu date; cảnh báo event chưa định ngày. |
| FR-04 | OT input | **[ASSUME]** Missing data: hours-per-day chưa được chốt. Reasoning: workbook có OT hours và OT days. Cho phép nhập hours và cấu hình conversion rule có version. [Source: WB-s189-1; WB-s189-2] | UI hiển thị cả OT hours, OT days và rule chuyển đổi. |
| FR-05 | Calculation | **[ASSUME]** Missing data: công thức chuẩn chưa được sign-off. Reasoning: dùng logic s192 làm baseline nhưng không ẩn constant. Tính capacity theo resource và tổng hợp Dev/QC/Biz. [Source: WB-s192-CALC] | Kết quả golden case khớp expected đã được business phê duyệt. |
| FR-06 | Scenario compare | **[ASSUME]** Missing data: số scenario đồng thời chưa nêu. Reasoning: s189 có hai phiên bản OT. Cho phép clone, thay đổi OT/leave/buffer và so sánh. [Source: WB-s189-1; WB-s189-2] | Hiển thị chênh lệch input và output giữa các scenario. |
| FR-07 | Dashboard | **[ASSUME]** Missing data: KPI ưu tiên chưa được nêu. Reasoning: summary có Dev V, QC V, Team velocity Biz và QC-Dev. Hiển thị các output này cùng warning. [Source: WB-s192-SUM] | Mỗi KPI có nguồn input/rule và trạng thái validation. |
| FR-08 | Legacy import | **[ASSUME]** Missing data: có cần import toàn bộ lịch sử hay không. Reasoning: schema s158 và s192 khác nhau. Importer nhận diện version/mapping và không silently drop field. [Source: WB-s158-SUM; WB-s192-SUM] | Tạo report mapped, skipped và warning theo sheet. |
| FR-09 | Ticket grid | **[ASSUME]** Missing data: rule ticket chưa có record mẫu. Reasoning: giữ schema hiện có nhưng tách khỏi engine capacity. [Source: WB-s192-INPUT; WB-ALL-TICKET] | Có thể nhập/xuất ticket; không ảnh hưởng capacity khi chưa có approved rule. |
| FR-10 | Validation | **[ASSUME]** Missing data: severity matrix chưa có. Reasoning: s175 hiển thị #VALUE!. Hệ thống phải chặn publish khi có error và cho acknowledge warning. [Source: WB-s175-ERR] | Không có output lỗi bị trình bày như kết quả hợp lệ. |
| FR-11 | Export | **[ASSUME]** Missing data: format bàn giao chưa chốt. Reasoning: nguồn vận hành là Excel. Cho phép tải input, output, warnings và rule version. [Source: WB-INDEX] | File tải xuống có thể tái nhập và truy vết về snapshot. |
| FR-12 | Audit snapshot | **[ASSUME]** Missing data: retention chưa có. Reasoning: cần giải thích chênh lệch giữa scenario và schema. Snapshot chứa inputs, outputs, warnings và calculation version. [Source: WB-s189-1; WB-s189-2; WB-s175-ERR] | Một snapshot không đổi khi calculation rule tương lai thay đổi. |

## 7. Business rules và calculation baseline

| Rule | Output | Logic grounded từ workbook |
| --- | --- | --- |
| BR-01 | Dev days | NETWORKDAYS(Start Date, End Date, Public Holidays) - 3 - Back up. Hằng số 3 chưa có nhãn nghiệp vụ. [Source: WB-s192!C44 via WB-s192-CALC] |
| BR-02 | Remaining dev Days | NETWORKDAYS(TODAY(), End Date), vì vậy kết quả phụ thuộc ngày chạy. [Source: WB-s192!D44 via WB-s192-CALC] |
| BR-03 | Leave days | SUMIFS tổng Leave days theo Resource từ bảng leave của sprint. [Source: WB-s192!C47:C52 via WB-s192-CALC] |
| BR-04 | FTE noOT | Velocity per day × (Dev days - Leave days) × V %. [Source: WB-s192!K47:K52 via WB-s192-CALC] |
| BR-05 | Full V | Velocity per day × (Dev days - Leave days + OT in days) × V %. [Source: WB-s192!H47:H52 via WB-s192-CALC] |
| BR-06 | Buffered V | V = FTE noOT × (1 - Buffer); V OT = Full V × (1 - Buffer). [Source: WB-s192!F47:G52 via WB-s192-CALC] |
| BR-07 | Team totals | Full Dev V/Dev V/Full QC V/QC V là SUMIFS theo Type Dev hoặc QC. [Source: WB-s192!E44:H44 via WB-s192-CALC] |
| BR-08 | Team velocity (Biz) | MIN(Dev V, QC V). [Source: WB-s192!K44 via WB-s192-CALC] |
| BR-09 | QC - Dev | QC V - Dev V. [Source: WB-s192!P44 via WB-s192-CALC] |
| BR-10 | Buffer in day | Buffer × Dev days. [Source: WB-s192!R44 via WB-s192-CALC] |

Rule governance: **[ASSUME]** Missing data: business chưa xác nhận rule nào là authoritative. Reasoning: schema thay đổi và s175 có lỗi. Mỗi rule cần rule_id, version, effective status, owner và golden test trước khi sử dụng cho snapshot approved. [Source: WB-s158-SUM; WB-s192-CALC; WB-s175-ERR]

## 8. Logical data model

| Entity | Thuộc tính chính | Nguồn / giả định |
| --- | --- | --- |
| Sprint | id, name, start_date, end_date, development_end_date, public_holiday_count, buffer, backup | WB-s192-META |
| Resource | id, display_name, default_velocity_per_day, default_type | WB-s192-RES |
| SprintResource | sprint/scenario, resource, velocity_per_day, V%, Others, Type | WB-s192-RES |
| LeaveEvent | resource, date hoặc TBD status, leave_days, note | WB-s192-INPUT |
| Holiday | date hoặc count, note | WB-s192-INPUT; WB-s192-SUM |
| Scenario | name, base sprint, overrides, calculation version | WB-s189-1; WB-s189-2 |
| Ticket | Dev, QC, Category, Assignee, ID, Summary, Priority, Point, Status, Added-after flag, Note | WB-s192-INPUT |
| CalculationSnapshot | inputs, outputs, warnings, rule version, approval status | **[ASSUME]** Missing data: snapshot/audit schema. Reasoning: cần truy vết scenario và lỗi nguồn. [Source: WB-s189-1; WB-s175-ERR] |

## 9. User workflow

1. **[ASSUME]** Missing data: quy trình thao tác chưa được mô tả. Reasoning: workbook yêu cầu nhiều nhóm input. Tạo hoặc import sprint. [Source: WB-s192-INPUT; WB-s192-META]

1. **[ASSUME]** Missing data: thứ tự nhập liệu chưa được chốt. Reasoning: resource, leave và OT là driver của capacity. Cấu hình resource và event trước khi chạy. [Source: WB-s192-RES; WB-s192-CALC]

1. **[ASSUME]** Missing data: cơ chế scenario chưa có. Reasoning: s189 dùng hai sheet. Clone scenario, thay override, rồi tính lại. [Source: WB-s189-1; WB-s189-2]

1. **[ASSUME]** Missing data: quy tắc review chưa có. Reasoning: s175 có lỗi. Kiểm tra warning/error và giải trình trước khi approve. [Source: WB-s175-ERR]

1. **[ASSUME]** Missing data: cách phát hành chưa có. Reasoning: cần bàn giao kết quả có truy vết. Khóa snapshot và tải report. [Source: UR-REQ]

## 10. Non-functional requirements

| ID | Quality | Requirement |
| --- | --- | --- |
| NFR-A | Auditability | **[ASSUME]** Missing data: chuẩn audit chưa có. Reasoning: công thức structured reference và schema drift làm khó kiểm chứng. Mọi output phải truy ngược được input/rule. [Source: WB-s192-CALC; WB-s158-SUM; WB-s192-SUM] |
| NFR-B | Data quality | **[ASSUME]** Missing data: severity matrix chưa có. Reasoning: #VALUE! đã xuất hiện. Error phải chặn publish; warning phải có acknowledgement. [Source: WB-s175-ERR] |
| NFR-C | Privacy | **[ASSUME]** Missing data: classification dữ liệu nhân sự. Reasoning: workbook chứa tên và ngày nghỉ; Community Cloud có tùy chọn public/private. Không được public app hoặc dữ liệu khi chưa có phê duyệt. [Source: WB-s192-INPUT; ST-SHARE; ST-SECURITY] |
| NFR-D | Portability | **[ASSUME]** Missing data: hosting dự phòng. Reasoning: yêu cầu hiện tại là Streamlit Community Cloud. Tách domain/calculation khỏi UI để có thể chuyển hosting. [Source: UR-REQ; ST-CC] |
| NFR-E | Maintainability | **[ASSUME]** Missing data: release governance. Reasoning: dependencies trên Community Cloud nên được khai báo và pin. [Source: ST-DEPS] |
| NFR-F | Persistence | **[ASSUME]** Missing data: retention/concurrency. Reasoning: Community Cloud không bảo đảm local file persistence. MVP mặc định stateless, persistent mode cần external store. [Source: ST-PERSIST] |

## 11. Data migration và reconciliation

- **[ASSUME]** Missing data: lịch sử nào cần migrate. Reasoning: workbook có nhiều schema. Importer phải detect header và map alias thay vì phụ thuộc vị trí cột cố định. [Source: WB-s158-SUM; WB-s192-SUM]

- **[ASSUME]** Missing data: authoritative scenario cho s189. Reasoning: có hai sheet cùng sprint. Import thành hai scenario riêng, không overwrite. [Source: WB-s189-1; WB-s189-2]

- **[ASSUME]** Missing data: policy với lỗi s175. Reasoning: render nguồn có #VALUE!. Import input nhưng gắn error flag; không dùng output lỗi làm baseline. [Source: WB-s175-ERR]

- **[ASSUME]** Missing data: historical meaning of TODAY-based field. Reasoning: Remaining dev Days thay đổi theo ngày chạy. Khi migrate, lưu cả cached source value và recalculated value có timestamp. [Source: WB-s192!D44 via WB-s192-CALC]

## 12. Risk and anomaly register

| ID | Risk | Confidence và evidence | Mitigation đề xuất |
| --- | --- | --- | --- |
| R-01 | Visible formula errors | **[ASSUME]** Confidence score: 100%. Missing data: root cause chưa có. Reasoning: #VALUE! nhìn thấy trực tiếp tại các ô nguồn. [Source: WB-s175-ERR] | Dùng s175 làm negative test; chặn publish khi có error. |
| R-02 | Schema drift | **[ASSUME]** Confidence score: 100%. Missing data: canonical schema chưa được tuyên bố. Reasoning: s158 và s192 có tập cột summary khác nhau. [Source: WB-s158-SUM; WB-s192-SUM] | Canonical schema + versioned import adapters. |
| R-03 | Volatile historical output | **[ASSUME]** Confidence score: 100%. Missing data: historical cutoff rule. Reasoning: Remaining dev Days dùng TODAY(). [Source: WB-s192-CALC] | Snapshot as-of date; không dùng wall-clock ngầm. |
| R-04 | Unexplained constant | **[ASSUME]** Confidence score: 100%. Missing data: ý nghĩa số 3. Reasoning: công thức C44 trừ literal 3 nhưng header không định nghĩa. [Source: WB-s192-CALC] | Đưa thành named setting; cần business sign-off. |
| R-05 | OT ambiguity | **[ASSUME]** Confidence score: 95%. Missing data: quy tắc OT chuẩn. Reasoning: hai scenario s189 dùng OT khác nhau, không có mô tả policy. [Source: WB-s189-1; WB-s189-2] | Versioned conversion rule và scenario notes. |
| R-06 | Ticket module underdefined | **[ASSUME]** Confidence score: 100%. Missing data: record mẫu và rule ticket. Reasoning: A3:K35 không có dữ liệu. [Source: WB-ALL-TICKET] | Giữ optional; không nối capacity trước sign-off. |
| R-07 | Mixed date semantics | **[ASSUME]** Confidence score: 100%. Missing data: ý nghĩa TBD. Reasoning: Leave date chứa cả ngày và TBD. [Source: WB-s192-INPUT] | Tách status khỏi date field. |
| R-08 | Privacy exposure | **[ASSUME]** Confidence score: 100%. Missing data: data classification. Reasoning: workbook chứa tên/ngày nghỉ và app có thể được chia sẻ public. [Source: WB-s192-INPUT; ST-SHARE] | Private app/repo hoặc dữ liệu giả lập cho demo công khai. |

## 13. BRD acceptance gate

- **[ASSUME]** Missing data: người phê duyệt chưa có. Reasoning: rule và role chưa sign-off. BRD chỉ chuyển sang Approved khi owner được chỉ định. [Source: CRITICAL DATA MISSING trong tài liệu này]

- **[ASSUME]** Missing data: tolerance chưa có. Reasoning: phải tránh coi lỗi nguồn là chuẩn. Mỗi golden case cần expected inputs/outputs và disposition cho s175. [Source: WB-s175-ERR; WB-s192-SUM]

- **[ASSUME]** Missing data: privacy approval chưa có. Reasoning: deployment có dữ liệu nhân sự. Phải chốt private/public mode trước production. [Source: WB-s192-INPUT; ST-SHARE; ST-SECURITY]

- **[ASSUME]** Missing data: persistence decision chưa có. Reasoning: local files không bền vững. Phải chọn stateless download hoặc external database. [Source: ST-PERSIST]

## Critical data missing và quyết định cần chốt

CRITICAL DATA MISSING: Ý nghĩa nghiệp vụ của hằng số 3 bị trừ trong công thức Dev days - Not found in context. [Source: WB-s192-CALC]

CRITICAL DATA MISSING: Quy tắc chuẩn chuyển OT in hours sang OT (in days) - Not found in context. [Source: WB-s189-1; WB-s189-2]

CRITICAL DATA MISSING: Định nghĩa đầy đủ của EzA, SC, SE và Regression - Not found in context. [Source: WB-s192-SUM]

CRITICAL DATA MISSING: Vai trò người dùng, quyền xem/sửa/phê duyệt và người sở hữu dữ liệu - Not found in context. [Source: WB-s192-INPUT]

CRITICAL DATA MISSING: Chính sách xử lý Leave date = TBD - Not found in context. [Source: WB-s192-INPUT]

CRITICAL DATA MISSING: Nguồn ticket, trạng thái hợp lệ và quy tắc Dev/QC/Point/Priority - Not found in context. [Source: WB-ALL-TICKET]

CRITICAL DATA MISSING: Mức sai số cho phép khi đối chiếu kết quả web với workbook - Not found in context. [Source: WB-s192-SUM; WB-s175-ERR]

CRITICAL DATA MISSING: Chính sách lưu trữ, thời hạn giữ dữ liệu và yêu cầu đồng thời nhiều người dùng - Not found in context. [Source: UR-REQ]

CRITICAL DATA MISSING: Quy định dữ liệu nhân sự có được đưa lên dịch vụ cloud công khai hay không - Not found in context. [Source: WB-s192-INPUT; ST-SECURITY]

## Source register

Mọi citation dạng [Source: mã] trong tài liệu được giải nghĩa ở bảng dưới đây. Với workbook, locator là sheet và vùng ô; với Streamlit, locator là trang tài liệu chính thức.

| Mã | Nguồn | Locator / phạm vi |
| --- | --- | --- |
| UR-REQ | Yêu cầu người dùng | Phân tích workbook; viết BRD và technical proposal cho web application có thể deploy miễn phí trên streamlit.io. |
| WB-INDEX | Saturn Velocity 2024-2025-2026 (1).xlsx | Worksheet index: s158 đến s192, gồm các biến thể s189-1stOT và s189-FTEOT. |
| WB-s158-META | Workbook, sheet s158 | A36:A40 - cửa sổ sprint/dev, public holiday, buffer, backup. |
| WB-s158-SUM | Workbook, sheet s158 | A43:O44 - schema summary/capacity giai đoạn đầu. |
| WB-s192-INPUT | Workbook, sheet s192 | A2:R12 - header ticket và dữ liệu resource/leave/public holiday. |
| WB-s192 | Workbook, sheet s192 | A2:Y70 - toàn bộ vùng dữ liệu, công thức và summary của sheet. |
| WB-s192-META | Workbook, sheet s192 | A36:A40 - Start/End, Development Days, Public Holiday, Buffer, Backup. |
| WB-s192-SUM | Workbook, sheet s192 | A43:Y44 - schema và kết quả summary/capacity mới nhất trong workbook. |
| WB-s192-CALC | Workbook, sheet s192 | C44:R44 và C47:L52 - công thức sprint và công thức capacity theo resource. |
| WB-s192-RES | Workbook, sheet s192 | A46:O52 - resource, velocity/day, leave, OT, V, FTE, V%, Others, Type. |
| WB-s189-1 | Workbook, sheet s189-1stOT | A36:Y53 - cùng sprint s189 với một bộ OT và kết quả capacity. |
| WB-s189-2 | Workbook, sheet s189-FTEOT | A36:Y53 - cùng sprint s189 với bộ OT khác và kết quả capacity khác. |
| WB-s175-ERR | Workbook, sheet s175 | Các giá trị hiển thị tại E44:G44, E48:F51 và E57 là #VALUE! trong bản render nguồn. |
| WB-ALL-TICKET | Workbook, tất cả worksheet | A3:K35 - không có dòng ticket được điền; chỉ có header tại A2:K2. |
| ST-CC | Streamlit Docs - Community Cloud | https://docs.streamlit.io/deploy/streamlit-community-cloud |
| ST-DEPLOY | Streamlit Docs - Deploy your app | https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/deploy |
| ST-DEPS | Streamlit Docs - App dependencies | https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/app-dependencies |
| ST-PERSIST | Streamlit Docs - Connecting to data | https://docs.streamlit.io/develop/concepts/connections/connecting-to-data |
| ST-SECRETS | Streamlit Docs - Community Cloud secrets | https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/secrets-management |
| ST-SHARE | Streamlit Docs - Share your app | https://docs.streamlit.io/deploy/streamlit-community-cloud/share-your-app |
| ST-AUTH | Streamlit Docs - Authentication | https://docs.streamlit.io/develop/concepts/connections/authentication |
| ST-STATUS | Streamlit Docs - Status and limitations | https://docs.streamlit.io/deploy/streamlit-community-cloud/status |
| ST-SECURITY | Streamlit Docs - Trust and security | https://docs.streamlit.io/deploy/streamlit-community-cloud/get-started/trust-and-security |
